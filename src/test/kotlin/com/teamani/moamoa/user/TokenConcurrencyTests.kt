package com.teamani.moamoa.user

import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.repository.AppUserRepository
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import com.teamani.moamoa.user.service.TokenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// 실제 트랜잭션·DB에서 같은 사용자의 토큰 발급이 동시에 들어올 때 사용자 단위로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class TokenConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var tokenService: TokenService

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    // 요청을 원하는 시점에 멈춰 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean
    lateinit var refreshTokenRepository: RefreshTokenRepository

    // 다른 테스트 데이터는 남겨 두도록 이 테스트에서 만든 사용자만 정리
    private val savedUserIds = mutableListOf<Long>()

    init {
        afterTest {
            reset(refreshTokenRepository)
            savedUserIds.forEach { userId ->
                refreshTokenRepository.deleteAllInBatch(refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId))
            }
            appUserRepository.deleteAllByIdInBatch(savedUserIds)
            savedUserIds.clear()
        }

        Given("토큰이 2개 있는 사용자가") {
            When("두 기기에서 동시에 로그인해 첫 발급이 토큰 수를 확인한 직후 두 번째 발급이 들어오면") {
                Then("두 번째 발급은 첫 발급이 끝날 때까지 기다리고, 최종 토큰은 최대 기기 수인 3개다") {
                    val userId = savedUser()
                    repeat(2) { tokenService.issueTokens(userId) }
                    // 첫 발급이 사용자를 잠그고 기존 토큰 수를 확인한 직후 멈춤
                    val pause = Pause()
                    doAnswer(afterReal(pause::hold)).`when`(refreshTokenRepository)
                        .findAllByUserIdOrderByCreatedAtAscIdAsc(anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val first = executor.start { tokenService.issueTokens(userId) }
                        pause.awaitPaused()
                        val second = executor.start { tokenService.issueTokens(userId) }

                        shouldThrow<TimeoutException> { second.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        first.get(10, TimeUnit.SECONDS).shouldBeNull()
                        second.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(refreshTokenRepository)
                    refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId).size shouldBe 3
                }
            }
        }
    }

    private fun savedUser(): Long =
        checkNotNull(
            appUserRepository.save(
                AppUser(
                    name = "김모아",
                    email = "${UUID.randomUUID()}@example.com",
                    password = "encoded",
                    termsAgreedAt = OffsetDateTime.now()
                )
            ).id
        ).also { savedUserIds.add(it) }

    // 실제 Repository 메서드 호출 후 끼워 넣은 동작 실행 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun afterReal(action: () -> Unit) = Answer { invocation ->
        val result = mockingDetails(refreshTokenRepository).mockCreationSettings.defaultAnswer.answer(invocation)
        action()
        result
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기 (사용자 잠금을 쥔 채 멈추므로 H2 잠금 대기 2초 안에 해제)
    private class Pause {
        private val paused = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        fun hold() {
            if (first.getAndSet(false)) {
                paused.countDown()
                released.await(5, TimeUnit.SECONDS)
            }
        }

        fun awaitPaused() {
            paused.await(5, TimeUnit.SECONDS) shouldBe true
        }

        fun release() = released.countDown()
    }

    // 작업을 비동기로 실행하고 작업에서 발생한 예외(성공이면 null) 반환
    private fun ExecutorService.start(task: () -> Any?): Future<Throwable?> =
        submit(Callable { runCatching { task() }.exceptionOrNull() })

    companion object {
        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

package com.teamani.moamoa.user

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.LogoutRequest
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.entity.RefreshToken
import com.teamani.moamoa.user.repository.AppUserRepository
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import com.teamani.moamoa.user.service.AuthService
import com.teamani.moamoa.user.service.UserService
import com.teamani.moamoa.user.service.sha256
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실제 DB(H2)에서 로그인·재발급·로그아웃과 기기 수 제한을 검증
@SpringBootTest
@ActiveProfiles("test")
class AuthIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var authService: AuthService

    @Autowired
    lateinit var userService: UserService

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    @Autowired
    lateinit var refreshTokenRepository: RefreshTokenRepository

    init {
        Given("가입한 사용자가 있을 때") {
            val email = "login-user@example.com"
            signUp(email)

            When("맞는 이메일과 비밀번호로 로그인하면") {
                val response = authService.login(LoginRequest(email, PASSWORD))

                Then("토큰을 발급한다") {
                    response.accessToken shouldNotBe ""
                    refreshTokenRepository.findByTokenHash(sha256(response.refreshToken)) shouldNotBe null
                }
            }

            When("대소문자만 다른 이메일로 로그인하면") {
                val response = authService.login(LoginRequest(email.uppercase(), PASSWORD))

                Then("같은 계정으로 로그인된다") {
                    response.accessToken shouldNotBe ""
                }
            }

            When("비밀번호가 틀리면") {
                val exception = shouldThrow<ApiException> { authService.login(LoginRequest(email, "wrong1234")) }

                Then("UNAUTHORIZED 예외가 발생한다") {
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }
            }

            When("72자를 넘는 비밀번호로 로그인하면") {
                val exception = shouldThrow<ApiException> {
                    authService.login(LoginRequest(email, "a1" + "b".repeat(100)))
                }

                Then("서버 오류 없이 UNAUTHORIZED 예외가 발생한다") {
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }
            }

            When("없는 이메일이면") {
                val exception = shouldThrow<ApiException> {
                    authService.login(LoginRequest("none@example.com", PASSWORD))
                }

                Then("비밀번호가 틀린 경우와 같은 응답이다") {
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                    exception.message shouldBe "이메일 또는 비밀번호가 올바르지 않습니다."
                }
            }
        }

        Given("탈퇴한 사용자가 있을 때") {
            val email = "login-withdrawn@example.com"
            val user = appUserRepository.findById(signUp(email)).orElseThrow()
            user.deletedAt = OffsetDateTime.now()
            appUserRepository.save(user)

            When("로그인하면") {
                val exception = shouldThrow<ApiException> { authService.login(LoginRequest(email, PASSWORD)) }

                Then("UNAUTHORIZED 예외가 발생한다") {
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }
            }
        }

        Given("가입으로 기기 1대가 등록된 사용자가 3번 더 로그인할 때") {
            val email = "login-devices@example.com"
            val userId = signUp(email)
            val signUpToken = refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId).single()

            When("4번째 기기로 로그인하면") {
                repeat(3) { authService.login(LoginRequest(email, PASSWORD)) }

                Then("기기는 3대로 유지되고 가장 오래된 기기의 토큰이 삭제된다") {
                    val tokens = refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId)

                    tokens.size shouldBe 3
                    tokens.none { it.id == signUpToken.id } shouldBe true
                }
            }
        }

        Given("유효한 리프레시 토큰이 있을 때") {
            val email = "refresh-user@example.com"
            signUp(email)
            val first = authService.login(LoginRequest(email, PASSWORD))

            When("재발급하면") {
                val reissued = authService.refresh(TokenRefreshRequest(first.refreshToken))

                Then("새 토큰을 발급하고 기존 토큰은 다시 쓸 수 없다") {
                    reissued.refreshToken shouldNotBe first.refreshToken

                    val exception = shouldThrow<ApiException> {
                        authService.refresh(TokenRefreshRequest(first.refreshToken))
                    }
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }
            }
        }

        Given("같은 리프레시 토큰으로 재발급 요청이 동시에 들어올 때") {
            val email = "refresh-concurrent@example.com"
            signUp(email)
            val login = authService.login(LoginRequest(email, PASSWORD))

            When("요청을 동시에 실행하면") {
                val results = runConcurrently(
                    List(4) { { authService.refresh(TokenRefreshRequest(login.refreshToken)) } }
                )

                Then("한 요청만 성공하고 나머지는 UNAUTHORIZED다") {
                    results.count { it == null } shouldBe 1
                    results.filterNotNull().map { (it as ApiException).errorCode }.toSet() shouldBe
                        setOf(ErrorCode.UNAUTHORIZED)
                }
            }
        }

        Given("없거나 만료된 리프레시 토큰이 주어졌을 때") {
            val userId = signUp("refresh-expired@example.com")
            refreshTokenRepository.save(
                RefreshToken(
                    userId = userId,
                    tokenHash = sha256("expired-token"),
                    expiresAt = OffsetDateTime.now().minusSeconds(1)
                )
            )

            When("없는 토큰으로 재발급하면") {
                val exception = shouldThrow<ApiException> { authService.refresh(TokenRefreshRequest("unknown")) }

                Then("UNAUTHORIZED 예외가 발생한다") {
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }
            }

            When("만료된 토큰으로 재발급하면") {
                val exception = shouldThrow<ApiException> {
                    authService.refresh(TokenRefreshRequest("expired-token"))
                }

                Then("UNAUTHORIZED 예외가 발생한다") {
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }
            }
        }

        Given("로그인한 기기가 있을 때") {
            val email = "logout-user@example.com"
            signUp(email)
            val login = authService.login(LoginRequest(email, PASSWORD))

            When("로그아웃하면") {
                authService.logout(LogoutRequest(login.refreshToken))

                Then("기존 리프레시 토큰으로 재발급할 수 없다") {
                    val exception = shouldThrow<ApiException> {
                        authService.refresh(TokenRefreshRequest(login.refreshToken))
                    }
                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }

                Then("같은 토큰으로 다시 로그아웃해도 오류가 나지 않는다") {
                    authService.logout(LogoutRequest(login.refreshToken))
                }
            }
        }
    }

    private fun signUp(email: String): Long =
        userService.signUp(
            UserSignUpRequest(name = "김모아", email = email, password = PASSWORD, agreedToTerms = true)
        ).userId

    // 모든 작업을 같은 시점에 시작하고, 작업별 예외(성공이면 null)를 순서대로 반환
    private fun runConcurrently(tasks: List<() -> Any?>): List<Throwable?> {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tasks.size)

        try {
            val futures = tasks.map { task ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        start.await()
                        runCatching { task() }.exceptionOrNull()
                    }
                )
            }
            ready.await()
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val PASSWORD = "moamoa1234"
    }
}

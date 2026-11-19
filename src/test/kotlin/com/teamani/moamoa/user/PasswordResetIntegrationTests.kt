package com.teamani.moamoa.user

import com.jayway.jsonpath.JsonPath
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.PasswordResetMailRequest
import com.teamani.moamoa.user.dto.PasswordResetRequest
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.entity.PasswordResetToken
import com.teamani.moamoa.user.repository.AppUserRepository
import com.teamani.moamoa.user.repository.PasswordResetTokenRepository
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import com.teamani.moamoa.user.service.AuthService
import com.teamani.moamoa.user.service.PasswordResetService
import com.teamani.moamoa.user.service.sha256
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.after
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 실제 트랜잭션·DB에서 비밀번호 재설정 메일 요청(7-5)·재설정(7-6)과 로그인·토큰 재발급 동시 실행을 검증 (메일 발송은 mock)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var mailSender: JavaMailSender

    @MockitoSpyBean
    lateinit var appUserRepository: AppUserRepository

    @MockitoSpyBean
    lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @Autowired
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    lateinit var passwordResetService: PasswordResetService

    @Autowired
    lateinit var authService: AuthService

    init {
        Given("가입한 이메일로 재설정 메일을 요청할 때") {
            val email = newEmail()
            signUp(email)

            When("요청하면") {
                Then("200이고 커밋 후 그 이메일로 재설정 링크가 담긴 메일이 발송된다") {
                    val token = requestResetAndGetToken(email)
                    token.isNotBlank() shouldBe true
                }
            }
        }

        Given("가입하지 않은 이메일로 재설정 메일을 요청할 때") {
            When("요청하면") {
                Then("가입한 이메일과 같은 200 응답이고 메일은 보내지 않는다") {
                    clearInvocations(mailSender)
                    post("/v1/auth/password-reset-requests", """{ "email": "${newEmail()}" }""").andExpect {
                        status { isOk() }
                        jsonPath("$.data.success") { value(true) }
                    }
                    verify(mailSender, after(500).never()).send(any(SimpleMailMessage::class.java))
                }
            }
        }

        Given("재설정 링크를 받은 사용자가 있을 때") {
            val email = newEmail()
            val oldRefreshToken = signUp(email)
            val token = requestResetAndGetToken(email)

            When("새 비밀번호로 재설정하면") {
                post("/v1/auth/password-resets", """{ "token": "$token", "newPassword": "newpass123" }""").andExpect {
                    status { isOk() }
                    jsonPath("$.data.success") { value(true) }
                }

                Then("새 비밀번호로만 로그인되고, 기존 리프레시 토큰은 재발급에 쓸 수 없으며, 같은 링크는 다시 쓸 수 없다") {
                    login(email, "newpass123").andExpect { status { isOk() } }
                    login(email, OLD_PASSWORD).andExpect { status { isUnauthorized() } }
                    post("/v1/auth/refresh", """{ "refreshToken": "$oldRefreshToken" }""").andExpect { status { isUnauthorized() } }
                    resetWith(token).andExpectInvalidToken()
                }
            }
        }

        Given("쓸 수 없는 재설정 토큰일 때") {
            val email = newEmail()
            signUp(email)
            val userId = checkNotNull(appUserRepository.findByEmail(email)?.id)
            val expired = "expired-${UUID.randomUUID()}"
            passwordResetTokenRepository.save(
                PasswordResetToken(userId = userId, tokenHash = sha256(expired), expiresAt = OffsetDateTime.now().minusMinutes(1))
            )
            val previous = requestResetAndGetToken(email)
            // 새로 요청하면 이전 링크는 무효
            requestResetAndGetToken(email)

            listOf("없는 토큰" to "unknown-${UUID.randomUUID()}", "10분이 지난 토큰" to expired, "새로 요청해 무효가 된 토큰" to previous)
                .forEach { (case, token) ->
                    When("$case 으로 재설정하면") {
                        Then("400 VALIDATION_ERROR이고 details[0].field가 token이며 비밀번호는 바뀌지 않는다") {
                            resetWith(token).andExpectInvalidToken()
                            login(email, OLD_PASSWORD).andExpect { status { isOk() } }
                        }
                    }
                }
        }

        Given("비밀번호 규칙에 맞지 않는 새 비밀번호일 때") {
            val email = newEmail()
            signUp(email)
            val token = requestResetAndGetToken(email)

            When("영문만으로 된 비밀번호로 재설정하면") {
                Then("400 VALIDATION_ERROR이고 details[0].field가 newPassword이며 링크는 그대로 쓸 수 있다") {
                    post("/v1/auth/password-resets", """{ "token": "$token", "newPassword": "onlyletters" }""").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                        jsonPath("$.error.details[0].field") { value("newPassword") }
                    }
                    resetWith(token).andExpect { status { isOk() } }
                }
            }
        }

        Given("같은 사용자가 재설정 메일을 동시에 두 번 요청할 때") {
            val email = newEmail()
            signUp(email)
            val userId = checkNotNull(appUserRepository.findByEmail(email)?.id)

            When("앞 요청이 이전 토큰을 지운 직후 멈춘 사이에 뒤 요청이 들어오면") {
                // 앞 요청의 이전 토큰 삭제(첫 호출) 직후 멈춤. 잠금이 없으면 뒤 요청이 끝까지 진행해 두 토큰이 모두 남음
                val pause = Pause()
                doAnswer(afterReal(passwordResetTokenRepository) { pause.hold() })
                    .`when`(passwordResetTokenRepository).deleteAllByUserId(anyLong())
                val executor = Executors.newFixedThreadPool(2)
                val errors = try {
                    val first = executor.submit(Callable {
                        runCatching { passwordResetService.requestReset(PasswordResetMailRequest(email)) }.exceptionOrNull()
                    })
                    pause.awaitPaused()
                    val second = executor.submit(Callable {
                        runCatching { passwordResetService.requestReset(PasswordResetMailRequest(email)) }.exceptionOrNull()
                    })
                    Thread.sleep(WAIT_MILLIS)
                    pause.release()
                    listOf(first, second).map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                    reset(passwordResetTokenRepository)
                }

                Then("두 요청 모두 성공하고 유효한 재설정 토큰은 하나만 남는다") {
                    errors.filterNotNull() shouldBe emptyList()
                    passwordResetTokenRepository.findAll().count { it.userId == userId } shouldBe 1
                }
            }
        }

        Given("재설정이 사용자를 잠근 채 멈춘 사이에 토큰 재발급이나 이전 비밀번호 로그인이 들어올 때") {
            val email = newEmail()
            val refreshToken = signUp(email)
            val userId = checkNotNull(appUserRepository.findByEmail(email)?.id)
            val token = requestResetAndGetToken(email)

            When("재설정이 끝나면") {
                // 재설정의 사용자 잠금 조회(첫 호출) 직후 멈춤
                val pause = Pause()
                doAnswer(afterReal(appUserRepository) { pause.hold() }).`when`(appUserRepository).findByIdForUpdate(anyLong())
                val executor = Executors.newFixedThreadPool(3)
                val results = try {
                    val resetting = executor.submit(Callable {
                        runCatching { passwordResetService.resetPassword(PasswordResetRequest(token, "newpass123")) }.exceptionOrNull()
                    })
                    pause.awaitPaused()
                    val refreshing = executor.submit(Callable {
                        runCatching { authService.refresh(TokenRefreshRequest(refreshToken)) }.exceptionOrNull()
                    })
                    val loggingIn = executor.submit(Callable {
                        runCatching { authService.login(LoginRequest(email, OLD_PASSWORD)) }.exceptionOrNull()
                    })
                    // 재발급·로그인이 사용자 잠금을 기다리는 상태가 되도록 잠시 둔 뒤 재설정을 진행
                    Thread.sleep(WAIT_MILLIS)
                    pause.release()
                    listOf(resetting, refreshing, loggingIn).map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                    reset(appUserRepository)
                }

                Then("재발급과 이전 비밀번호 로그인은 재설정이 끝난 뒤 처리되어 401이고, 리프레시 토큰은 남지 않는다") {
                    results[0] shouldBe null
                    (results[1] as ApiException).errorCode shouldBe ErrorCode.UNAUTHORIZED
                    (results[2] as ApiException).errorCode shouldBe ErrorCode.UNAUTHORIZED
                    refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId).size shouldBe 0
                }
            }
        }
    }

    private fun post(url: String, body: String) = mockMvc.post(url) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun login(email: String, password: String) =
        post("/v1/auth/login", """{ "email": "$email", "password": "$password" }""")

    private fun resetWith(token: String) =
        post("/v1/auth/password-resets", """{ "token": "$token", "newPassword": "newpass123" }""")

    private fun ResultActionsDsl.andExpectInvalidToken() = andExpect {
        status { isBadRequest() }
        jsonPath("$.error.code") { value("VALIDATION_ERROR") }
        jsonPath("$.error.details[0].field") { value("token") }
    }

    // 가입 후 리프레시 토큰 반환
    private fun signUp(email: String): String {
        val body = post(
            "/v1/users",
            """{ "name": "재설정", "email": "$email", "password": "$OLD_PASSWORD", "agreedToTerms": true }"""
        ).andReturn().response.contentAsString
        return JsonPath.read(body, "$.data.refreshToken")
    }

    // 재설정 메일을 요청하고, 커밋 후 발송된 메일의 링크에서 토큰을 꺼냄
    private fun requestResetAndGetToken(email: String): String {
        clearInvocations(mailSender)
        post("/v1/auth/password-reset-requests", """{ "email": "$email" }""").andExpect { status { isOk() } }
        val captor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(mailSender, timeout(5000)).send(captor.capture() ?: SimpleMailMessage())
        val message = captor.value
        message.to?.toList() shouldBe listOf(email)
        return checkNotNull(Regex("/reset-password\\?token=([A-Za-z0-9_-]+)").find(message.text.orEmpty())).groupValues[1]
    }

    private fun newEmail() = "reset-${UUID.randomUUID()}@moamoa.test"

    // 실제 Repository 메서드를 호출한 뒤 끼워 넣은 동작 실행 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun afterReal(spy: Any, action: () -> Unit) = Answer { invocation ->
        val result = mockingDetails(spy).mockCreationSettings.defaultAnswer.answer(invocation)
        action()
        result
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기
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

    companion object {
        private const val OLD_PASSWORD = "password1"

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

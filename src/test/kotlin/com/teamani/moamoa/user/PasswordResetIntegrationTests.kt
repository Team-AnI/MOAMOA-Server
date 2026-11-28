package com.teamani.moamoa.user

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import com.teamani.moamoa.user.service.PasswordResetMailSender
import com.teamani.moamoa.user.service.PasswordResetService
import com.teamani.moamoa.user.service.sha256
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.after
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.MailSendException
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

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var environment: Environment

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
            val previous = requestResetAndGetToken(email)
            // 새로 요청하면 이전 링크는 무효
            requestResetAndGetToken(email)
            // 메일 요청이 그 사용자의 기존 토큰을 지우므로, 만료 토큰은 요청이 끝난 뒤 저장해 DB에 남아 있는 상태로 검사
            val expired = "expired-${UUID.randomUUID()}"
            passwordResetTokenRepository.save(
                PasswordResetToken(userId = userId, tokenHash = sha256(expired), expiresAt = OffsetDateTime.now().minusMinutes(1))
            )

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
                // 두 요청이 모두 사용자 잠금 조회(findByEmailForUpdate)를 호출했는지 확인한 뒤 앞 요청을 풀어 줌
                val arrived = CountDownLatch(2)
                doAnswer(beforeReal(appUserRepository) { arrived.countDown() })
                    .`when`(appUserRepository).findByEmailForUpdate(anyString())
                val executor = Executors.newFixedThreadPool(2)
                val errors = try {
                    val first = executor.submit(Callable {
                        runCatching { passwordResetService.requestReset(PasswordResetMailRequest(email)) }.exceptionOrNull()
                    })
                    pause.awaitPaused()
                    val second = executor.submit(Callable {
                        runCatching { passwordResetService.requestReset(PasswordResetMailRequest(email)) }.exceptionOrNull()
                    })
                    arrived.await(5, TimeUnit.SECONDS) shouldBe true
                    pause.release()
                    listOf(first, second).map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    pause.release()
                    executor.shutdownNow()
                    reset(passwordResetTokenRepository, appUserRepository)
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
                // 재설정의 사용자 잠금 조회(첫 호출) 직후 멈춤. 뒤 요청(재발급 findByIdForUpdate, 로그인 findByEmailForUpdate)은
                // 잠금 조회를 호출하는 순간 래치를 내려, 둘 다 잠금 조회에 도달한 뒤에 재설정을 풀어 줌
                val pause = Pause()
                val arrived = CountDownLatch(2)
                val firstLock = AtomicBoolean(true)
                doAnswer { invocation ->
                    if (firstLock.getAndSet(false)) {
                        val result = realAnswer(appUserRepository, invocation)
                        pause.hold()
                        result
                    } else {
                        arrived.countDown()
                        realAnswer(appUserRepository, invocation)
                    }
                }.`when`(appUserRepository).findByIdForUpdate(anyLong())
                doAnswer(beforeReal(appUserRepository) { arrived.countDown() })
                    .`when`(appUserRepository).findByEmailForUpdate(anyString())
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
                    arrived.await(5, TimeUnit.SECONDS) shouldBe true
                    pause.release()
                    listOf(resetting, refreshing, loggingIn).map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    pause.release()
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

        Given("재설정이 사용자 잠금을 기다리는 사이에 링크가 만료될 때") {
            val email = newEmail()
            signUp(email)
            val token = requestResetAndGetToken(email)

            When("잠금을 얻은 뒤 재설정을 이어가면") {
                // 처음 확인(만료 전)은 통과하고 사용자 잠금 조회를 호출하기 직전에 멈춘 사이, 만료 시각을 지금으로 당김
                val pause = Pause()
                doAnswer(beforeReal(appUserRepository) { pause.hold() }).`when`(appUserRepository).findByIdForUpdate(anyLong())
                val executor = Executors.newSingleThreadExecutor()
                val error = try {
                    val resetting = executor.submit(Callable {
                        runCatching { passwordResetService.resetPassword(PasswordResetRequest(token, "newpass123")) }.exceptionOrNull()
                    })
                    pause.awaitPaused()
                    jdbcTemplate.update(
                        "update password_reset_token set expires_at = ? where token_hash = ?",
                        OffsetDateTime.now(),
                        sha256(token)
                    )
                    // 만료 시각이 확실히 지나도록 잠깐 둔 뒤 진행
                    Thread.sleep(EXPIRE_MARGIN_MILLIS)
                    pause.release()
                    resetting.get(10, TimeUnit.SECONDS)
                } finally {
                    pause.release()
                    executor.shutdownNow()
                    reset(appUserRepository)
                }

                Then("잠금을 얻은 뒤의 시각으로 만료를 판단해 400 VALIDATION_ERROR(field=token)이고 비밀번호는 바뀌지 않는다") {
                    val exception = error as ApiException
                    exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                    exception.details?.single()?.field shouldBe "token"
                    login(email, OLD_PASSWORD).andExpect { status { isOk() } }
                }
            }
        }

        Given("메일 서버가 수신자 주소가 들어간 사유로 발송을 거절할 때") {
            val email = newEmail()
            signUp(email)
            val logger = LoggerFactory.getLogger(PasswordResetMailSender::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }

            When("재설정 메일을 요청하면") {
                logger.addAppender(appender)
                try {
                    doThrow(MailSendException("550 5.1.1 <$email> recipient rejected"))
                        .`when`(mailSender).send(any(SimpleMailMessage::class.java))
                    post("/v1/auth/password-reset-requests", """{ "email": "$email" }""").andExpect { status { isOk() } }
                    // 커밋 후 별도 스레드에서 발송·로그가 끝날 때까지 대기
                    val deadline = System.currentTimeMillis() + 5000
                    while (appender.list.isEmpty() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50)
                    }
                } finally {
                    logger.detachAppender(appender)
                    reset(mailSender)
                }

                Then("실패 로그에는 userId와 예외 종류만 남고 수신자 주소와 예외 메시지·스택은 남지 않는다") {
                    val event = appender.list.single()
                    event.formattedMessage.contains("MailSendException") shouldBe true
                    event.formattedMessage.contains(email) shouldBe false
                    event.throwableProxy shouldBe null
                }
            }
        }

        Given("메일 발송 설정을 확인할 때") {
            When("서버 설정을 읽으면") {
                Then("STARTTLS 필수와 연결·읽기·쓰기 제한 시간이 설정되어 있다") {
                    environment.getProperty("spring.mail.properties.mail.smtp.starttls.required") shouldBe "true"
                    environment.getProperty("spring.mail.properties.mail.smtp.connectiontimeout") shouldBe "5000"
                    environment.getProperty("spring.mail.properties.mail.smtp.timeout") shouldBe "5000"
                    environment.getProperty("spring.mail.properties.mail.smtp.writetimeout") shouldBe "5000"
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

    // Spring Data 프록시 spy의 기본 응답(실제 Repository 위임)으로 실제 메서드 호출
    private fun realAnswer(spy: Any, invocation: InvocationOnMock): Any? =
        mockingDetails(spy).mockCreationSettings.defaultAnswer.answer(invocation)

    // 실제 Repository 메서드를 호출한 뒤 끼워 넣은 동작 실행
    private fun afterReal(spy: Any, action: () -> Unit) = Answer { invocation ->
        val result = realAnswer(spy, invocation)
        action()
        result
    }

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출
    private fun beforeReal(spy: Any, action: () -> Unit) = Answer { invocation ->
        action()
        realAnswer(spy, invocation)
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

        // 당긴 만료 시각이 잠금 뒤 판단 시각보다 확실히 앞서도록 두는 간격
        private const val EXPIRE_MARGIN_MILLIS = 20L
    }
}

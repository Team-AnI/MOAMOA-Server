package com.teamani.moamoa.user

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.entity.ProfileColor
import com.teamani.moamoa.user.repository.AppUserRepository
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import com.teamani.moamoa.user.service.UserService
import com.teamani.moamoa.user.service.sha256
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실제 DB(H2)와 BCrypt, JWT로 회원가입 저장·토큰 발급·중복 처리를 검증
@SpringBootTest
@ActiveProfiles("test")
class UserSignUpIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var userService: UserService

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    @Autowired
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Value("\${jwt.secret}")
    lateinit var jwtSecret: String

    init {
        Given("처음 가입하는 이메일이 주어졌을 때") {
            val email = "signup-success@example.com"

            When("회원가입하면") {
                val response = userService.signUp(request(email))

                Then("사용자가 저장되고 비밀번호는 BCrypt 해시로 저장된다") {
                    val user = appUserRepository.findById(response.userId).orElseThrow()

                    user.email shouldBe email
                    user.password shouldNotBe "moamoa1234"
                    passwordEncoder.matches("moamoa1234", user.password) shouldBe true
                    user.profileColor shouldBe ProfileColor.LIGHT_BLUE
                }

                Then("액세스 토큰의 subject는 사용자 ID이고 만료는 1시간 뒤다") {
                    val claims = Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(jwtSecret.toByteArray()))
                        .build()
                        .parseSignedClaims(response.accessToken)
                        .payload

                    claims.subject shouldBe response.userId.toString()
                    (claims.expiration.time - claims.issuedAt.time) shouldBe 60 * 60 * 1000L
                }

                Then("리프레시 토큰은 원문이 아니라 해시로 저장되고 만료는 30일 뒤다") {
                    val token = refreshTokenRepository.findAll().single { it.userId == response.userId }

                    token.tokenHash shouldBe sha256(response.refreshToken)
                    token.tokenHash shouldNotBe response.refreshToken
                    token.expiresAt.isAfter(OffsetDateTime.now().plusDays(29)) shouldBe true
                    token.expiresAt.isBefore(OffsetDateTime.now().plusDays(31)) shouldBe true
                }
            }
        }

        Given("이미 가입된 이메일이 있을 때") {
            val email = "signup-duplicate@example.com"
            userService.signUp(request(email))

            When("같은 이메일로 회원가입하면") {
                val exception = shouldThrow<ApiException> { userService.signUp(request(email)) }

                Then("CONFLICT 예외가 발생하고 사용자는 늘어나지 않는다") {
                    exception.errorCode shouldBe ErrorCode.CONFLICT
                    appUserRepository.findAll().count { it.email == email } shouldBe 1
                }
            }
        }

        Given("대소문자만 다른 이메일로 가입할 때") {
            val response = userService.signUp(request("Signup-Case@Example.COM"))

            When("소문자 이메일로 다시 회원가입하면") {
                val exception = shouldThrow<ApiException> { userService.signUp(request("signup-case@example.com")) }

                Then("이메일은 소문자로 저장되고 같은 이메일로 처리되어 CONFLICT 예외가 발생한다") {
                    response.email shouldBe "signup-case@example.com"
                    exception.errorCode shouldBe ErrorCode.CONFLICT
                }
            }
        }

        Given("같은 이메일의 첫 가입 요청이 동시에 들어올 때") {
            val email = "signup-concurrent@example.com"

            When("두 요청을 동시에 실행하면") {
                val results = runConcurrently(List(4) { { userService.signUp(request(email)) } })

                Then("한 요청만 성공하고 나머지는 CONFLICT이며 사용자는 한 명만 저장된다") {
                    results.count { it == null } shouldBe 1
                    results.filterNotNull().map { (it as ApiException).errorCode }.toSet() shouldBe
                        setOf(ErrorCode.CONFLICT)
                    appUserRepository.findAll().count { it.email == email } shouldBe 1
                }
            }
        }

        Given("탈퇴한 지 7일이 지나지 않은 이메일이 있을 때") {
            val email = "signup-withdrawn@example.com"
            appUserRepository.save(withdrawnUser(email, deletedAt = OffsetDateTime.now().minusDays(6)))

            When("대소문자만 다른 같은 이메일로 회원가입하면") {
                val exception = shouldThrow<ApiException> { userService.signUp(request(email.uppercase())) }

                Then("CONFLICT 예외가 발생한다") {
                    exception.errorCode shouldBe ErrorCode.CONFLICT
                    exception.message shouldBe "탈퇴 후 7일 동안 같은 이메일로 가입할 수 없습니다."
                }
            }
        }

        Given("탈퇴한 지 7일이 지난 이메일이 있을 때") {
            val email = "signup-rejoin@example.com"
            appUserRepository.save(withdrawnUser(email, deletedAt = OffsetDateTime.now().minusDays(8)))

            When("같은 이메일로 회원가입하면") {
                val response = userService.signUp(request(email))

                Then("새 계정으로 가입된다") {
                    appUserRepository.findById(response.userId).orElseThrow().deletedAt shouldBe null
                }
            }
        }
    }

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

    private fun request(email: String) = UserSignUpRequest(
        name = "김모아",
        email = email,
        password = "moamoa1234",
        agreedToTerms = true
    )

    // 탈퇴 계정은 이메일 원문 없이 해시만 남은 상태
    private fun withdrawnUser(email: String, deletedAt: OffsetDateTime) = AppUser(
        name = "탈퇴한 사용자",
        email = "withdrawn-$deletedAt@deleted.local",
        password = "withdrawn",
        termsAgreedAt = OffsetDateTime.now(),
        deletedAt = deletedAt,
        withdrawnEmailHash = sha256(email)
    )
}

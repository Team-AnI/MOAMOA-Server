package com.teamani.moamoa.global.auth

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.Date

class JwtTokenProviderTests : BehaviorSpec({

    val secret = "test-only-secret-key-for-moamoa-jwt-0123456789"
    val jwtTokenProvider = JwtTokenProvider(secret)

    fun tokenOf(signSecret: String, subject: String, expiresAt: Instant) = Jwts.builder()
        .subject(subject)
        .expiration(Date.from(expiresAt))
        .signWith(Keys.hmacShaKeyFor(signSecret.toByteArray()))
        .compact()

    Given("발급한 액세스 토큰을 검증할 때") {
        When("서명과 만료 시각이 유효하면") {
            Then("발급할 때 넣은 userId를 반환한다") {
                val token = jwtTokenProvider.createAccessToken(7L)

                jwtTokenProvider.getUserId(token) shouldBe 7L
            }
        }
    }

    Given("유효하지 않은 액세스 토큰을 검증할 때") {
        val invalidTokens = listOf(
            "만료된 토큰" to tokenOf(secret, "7", Instant.now().minusSeconds(60)),
            "다른 키로 서명한 토큰" to tokenOf("another-secret-key-for-moamoa-jwt-0123456789", "7", Instant.now().plusSeconds(60)),
            "subject가 숫자가 아닌 토큰" to tokenOf(secret, "moa", Instant.now().plusSeconds(60)),
            "JWT 형식이 아닌 문자열" to "not-a-jwt",
            "빈 문자열" to ""
        )

        invalidTokens.forEach { (case, token) ->
            When("${case}이면") {
                Then("401 UNAUTHORIZED") {
                    val exception = shouldThrow<ApiException> { jwtTokenProvider.getUserId(token) }

                    exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                }
            }
        }
    }
})

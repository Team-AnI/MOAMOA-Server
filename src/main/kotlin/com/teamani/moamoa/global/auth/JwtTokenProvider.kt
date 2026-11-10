package com.teamani.moamoa.global.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date

// JWT 액세스 토큰 발급 (요청 검증은 공통 인증 #66에서 추가)
@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") secret: String
) {

    // HS256 서명 키. secret이 32바이트보다 짧으면 앱 시작 시 실패
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun createAccessToken(userId: Long): String {
        val now = Instant.now()

        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ACCESS_TOKEN_VALIDITY)))
            .signWith(key)
            .compact()
    }

    companion object {
        private val ACCESS_TOKEN_VALIDITY = Duration.ofHours(1)
    }
}

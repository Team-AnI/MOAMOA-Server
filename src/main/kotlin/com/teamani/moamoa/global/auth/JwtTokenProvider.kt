package com.teamani.moamoa.global.auth

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date

// JWT 액세스 토큰 발급 및 검증
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

    // 서명·만료를 확인하고 subject의 userId를 반환. 위조·만료·형식 오류는 모두 401
    fun getUserId(accessToken: String): Long {
        val subject = try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(accessToken)
                .payload
                .subject
        } catch (e: JwtException) {
            throw ApiException(ErrorCode.UNAUTHORIZED)
        } catch (e: IllegalArgumentException) {
            throw ApiException(ErrorCode.UNAUTHORIZED)
        }
        return subject?.toLongOrNull() ?: throw ApiException(ErrorCode.UNAUTHORIZED)
    }

    companion object {
        private val ACCESS_TOKEN_VALIDITY = Duration.ofHours(1)
    }
}

package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.user.entity.RefreshToken
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64

// 액세스·리프레시 토큰 쌍
data class IssuedTokens(
    val accessToken: String,
    val refreshToken: String
)

@Service
class TokenService(
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenRepository: RefreshTokenRepository
) {

    // 리프레시 토큰은 해시만 저장하고 원문은 응답으로만 전달
    @Transactional
    fun issueTokens(userId: Long): IssuedTokens {
        val refreshToken = generateRefreshToken()

        refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                tokenHash = sha256(refreshToken),
                expiresAt = OffsetDateTime.now().plusDays(REFRESH_TOKEN_DAYS)
            )
        )

        return IssuedTokens(
            accessToken = jwtTokenProvider.createAccessToken(userId),
            refreshToken = refreshToken
        )
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val REFRESH_TOKEN_DAYS = 30L
        private const val REFRESH_TOKEN_BYTES = 32
        private val secureRandom = SecureRandom()
    }
}

// SHA-256 해시를 소문자 16진수 문자열로 변환 (리프레시 토큰, 탈퇴 이메일 비교용)
internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

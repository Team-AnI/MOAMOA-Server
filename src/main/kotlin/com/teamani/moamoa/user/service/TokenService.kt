package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.entity.RefreshToken
import com.teamani.moamoa.user.repository.AppUserRepository
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
    private val refreshTokenRepository: RefreshTokenRepository,
    private val appUserRepository: AppUserRepository
) {

    // 리프레시 토큰은 해시만 저장하고 원문은 응답으로만 전달
    @Transactional
    fun issueTokens(userId: Long): IssuedTokens {
        lockActiveUser(userId)
        return createTokens(userId)
    }

    // 기존 토큰을 삭제하고 새 토큰으로 교체 (이미 사용했거나 만료된 토큰, 탈퇴한 사용자의 토큰은 401)
    @Transactional
    fun reissueTokens(refreshToken: String): IssuedTokens {
        val tokenHash = sha256(refreshToken)
        val token = refreshTokenRepository.findByTokenHash(tokenHash)
            ?: throw ApiException(ErrorCode.UNAUTHORIZED, INVALID_REFRESH_TOKEN_MESSAGE)
        if (token.expiresAt.isBefore(OffsetDateTime.now())) {
            throw ApiException(ErrorCode.UNAUTHORIZED, INVALID_REFRESH_TOKEN_MESSAGE)
        }

        // 잠금 순서는 발급과 같이 사용자 → 토큰
        lockActiveUser(token.userId)

        // 같은 토큰의 동시 요청은 삭제에 성공한 한 요청만 재발급
        if (refreshTokenRepository.deleteByTokenHash(tokenHash) == 0) {
            throw ApiException(ErrorCode.UNAUTHORIZED, INVALID_REFRESH_TOKEN_MESSAGE)
        }

        return createTokens(token.userId)
    }

    // 이미 삭제된 토큰이어도 예외 없이 처리
    @Transactional
    fun revokeToken(refreshToken: String) {
        refreshTokenRepository.deleteByTokenHash(sha256(refreshToken))
    }

    // 같은 사용자의 발급을 한 번에 하나씩 처리해 최대 기기 수를 지키고, 없거나 탈퇴한 사용자는 401
    private fun lockActiveUser(userId: Long) {
        val user = appUserRepository.findByIdForUpdate(userId)
        if (user == null || user.deletedAt != null) {
            throw ApiException(ErrorCode.UNAUTHORIZED)
        }
    }

    // 사용자 잠금을 얻은 뒤에만 호출
    private fun createTokens(userId: Long): IssuedTokens {
        deleteOldestTokensOverLimit(userId)

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

    // 새 토큰을 넣어도 최대 기기 수를 넘지 않도록 가장 오래된 토큰부터 삭제
    private fun deleteOldestTokensOverLimit(userId: Long) {
        val tokens = refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId)
        val overLimit = tokens.size - MAX_DEVICES + 1
        if (overLimit > 0) {
            refreshTokenRepository.deleteAllByIdInBatch(tokens.take(overLimit).mapNotNull { it.id })
        }
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val REFRESH_TOKEN_DAYS = 30L
        private const val REFRESH_TOKEN_BYTES = 32
        private const val MAX_DEVICES = 3
        private const val INVALID_REFRESH_TOKEN_MESSAGE = "리프레시 토큰이 유효하지 않습니다."
        private val secureRandom = SecureRandom()
    }
}

// SHA-256 해시를 소문자 16진수 문자열로 변환 (리프레시 토큰, 탈퇴 이메일 비교용)
internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.global.response.ValidationDetail
import com.teamani.moamoa.user.dto.PasswordResetMailRequest
import com.teamani.moamoa.user.dto.PasswordResetRequest
import com.teamani.moamoa.user.dto.PasswordResetResponse
import com.teamani.moamoa.user.entity.PasswordResetToken
import com.teamani.moamoa.user.repository.AppUserRepository
import com.teamani.moamoa.user.repository.PasswordResetTokenRepository
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64

@Service
class PasswordResetService(
    private val appUserRepository: AppUserRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val secureRandom = SecureRandom()

    // 7-5: 가입 여부가 드러나지 않도록 미가입·탈퇴 이메일도 같은 응답
    // 메일은 커밋 후 별도 스레드에서 보내 응답 내용·시간이 가입 여부와 상관없이 같음 (PasswordResetMailSender)
    @Transactional
    fun requestReset(request: PasswordResetMailRequest): PasswordResetResponse {
        val email = requireNotNull(request.email) { "이메일은 필수입니다." }.lowercase()
        // 같은 사용자의 동시 요청은 사용자 잠금으로 차례로 처리해, 나중 요청이 앞 요청의 링크를 지우게 함 (유효한 링크는 하나)
        val user = appUserRepository.findByEmailForUpdate(email)?.takeIf { it.deletedAt == null }
            ?: return PasswordResetResponse()
        val userId = checkNotNull(user.id) { "사용자 식별자가 없습니다." }

        passwordResetTokenRepository.deleteAllByUserId(userId)
        val token = generateToken()
        passwordResetTokenRepository.save(
            PasswordResetToken(userId = userId, tokenHash = sha256(token), expiresAt = OffsetDateTime.now().plus(TOKEN_VALIDITY))
        )
        eventPublisher.publishEvent(PasswordResetMailEvent(userId = userId, email = user.email, token = token))

        return PasswordResetResponse()
    }

    // 7-6: 10분·1회용 토큰으로 비밀번호 변경 후 모든 기기의 리프레시 토큰 삭제
    @Transactional
    fun resetPassword(request: PasswordResetRequest): PasswordResetResponse {
        val tokenHash = sha256(requireNotNull(request.token) { "재설정 토큰은 필수입니다." })
        val now = OffsetDateTime.now()
        val resetToken = passwordResetTokenRepository.findByTokenHash(tokenHash)
            ?.takeIf { it.usedAt == null && it.expiresAt.isAfter(now) }
            ?: throw invalidToken()

        // 잠금 순서는 토큰 발급·재발급과 같이 사용자 → 토큰
        val user = appUserRepository.findByIdForUpdate(resetToken.userId)?.takeIf { it.deletedAt == null }
            ?: throw invalidToken()
        // 같은 토큰의 동시 요청은 사용 처리에 성공한 한 요청만 진행
        if (passwordResetTokenRepository.markUsed(tokenHash, now) == 0) {
            throw invalidToken()
        }

        user.changePassword(checkNotNull(passwordEncoder.encode(requireNotNull(request.newPassword) { "새 비밀번호는 필수입니다." })) {
            "비밀번호 암호화에 실패했습니다."
        })
        refreshTokenRepository.deleteAllByUserId(resetToken.userId)

        return PasswordResetResponse()
    }

    // 링크 만료·사용됨은 비밀번호 규칙 위반(newPassword)과 구분되도록 details.field를 token으로 (프론트 요청)
    private fun invalidToken() = ApiException(
        ErrorCode.VALIDATION_ERROR,
        INVALID_TOKEN_MESSAGE,
        listOf(ValidationDetail(field = "token", reason = INVALID_TOKEN_MESSAGE, rejectedValue = null))
    )

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private val TOKEN_VALIDITY: Duration = Duration.ofMinutes(10)
        private const val TOKEN_BYTES = 32
        private const val INVALID_TOKEN_MESSAGE = "재설정 링크가 만료되었거나 이미 사용되었습니다. 다시 요청해 주세요."
    }
}

// 커밋 후 메일 발송용 이벤트 (토큰 원문은 메일 링크에만 사용)
data class PasswordResetMailEvent(val userId: Long, val email: String, val token: String)

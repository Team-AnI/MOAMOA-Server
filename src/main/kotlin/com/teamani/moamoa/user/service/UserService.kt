package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.dto.UserSignUpResponse
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.repository.AppUserRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class UserService(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService
) {

    @Transactional
    fun signUp(request: UserSignUpRequest): UserSignUpResponse {
        // 이메일은 대소문자를 구분하지 않도록 소문자로 저장
        val email = requireNotNull(request.email) { "이메일은 필수입니다." }.lowercase()
        validateSignUpEmail(email)

        val rawPassword = requireNotNull(request.password) { "비밀번호는 필수입니다." }
        val now = OffsetDateTime.now()
        val user = try {
            appUserRepository.saveAndFlush(
                AppUser(
                    name = requireNotNull(request.name) { "이름은 필수입니다." },
                    email = email,
                    password = checkNotNull(passwordEncoder.encode(rawPassword)) { "비밀번호 암호화에 실패했습니다." },
                    phone = request.phone,
                    termsAgreedAt = now,
                    createdAt = now
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            // 중복 확인 직후 동시 요청이 같은 이메일을 먼저 저장한 경우에만 409, 그 외 무결성 오류는 그대로 전달
            if (!ex.violates(AppUser.UK_EMAIL)) {
                throw ex
            }
            throw ApiException(ErrorCode.CONFLICT, EMAIL_CONFLICT_MESSAGE)
        }
        val userId = checkNotNull(user.id) { "사용자 식별자가 생성되지 않았습니다." }

        val tokens = tokenService.issueTokens(userId)

        return UserSignUpResponse(
            userId = userId,
            name = user.name,
            email = user.email,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken
        )
    }

    // 사용 중인 이메일과 탈퇴 후 재가입 제한 기간의 이메일은 가입 불가
    private fun validateSignUpEmail(email: String) {
        if (appUserRepository.existsByEmail(email)) {
            throw ApiException(ErrorCode.CONFLICT, EMAIL_CONFLICT_MESSAGE)
        }

        val restrictedAfter = OffsetDateTime.now().minusDays(REJOIN_RESTRICTION_DAYS)
        if (appUserRepository.existsByWithdrawnEmailHashAndDeletedAtAfter(sha256(email), restrictedAfter)) {
            throw ApiException(ErrorCode.CONFLICT, "탈퇴 후 7일 동안 같은 이메일로 가입할 수 없습니다.")
        }
    }

    // 위반한 제약 이름 확인 (PostgreSQL은 제약 이름 그대로, H2는 "public.<이름> INDEX ..." 형태라 포함 여부로 비교)
    private fun DataIntegrityViolationException.violates(constraintName: String): Boolean {
        val violation = cause as? ConstraintViolationException ?: return false
        return violation.constraintName?.contains(constraintName, ignoreCase = true) == true
    }

    companion object {
        private const val REJOIN_RESTRICTION_DAYS = 7L
        private const val EMAIL_CONFLICT_MESSAGE = "이미 가입된 이메일입니다."
    }
}

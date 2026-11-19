package com.teamani.moamoa.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String?,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    val password: String?
)

data class TokenRefreshRequest(
    @field:NotBlank(message = "리프레시 토큰은 필수입니다.")
    val refreshToken: String?
)

data class LogoutRequest(
    @field:NotBlank(message = "리프레시 토큰은 필수입니다.")
    val refreshToken: String?
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String
)

// 성공 응답은 data가 null이 아니어야 하므로 처리 결과만 반환
data class LogoutResponse(
    val success: Boolean = true
)

data class PasswordResetMailRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    @field:Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
    val email: String?
)

// 새 비밀번호 규칙은 회원가입(7-1)과 같음
data class PasswordResetRequest(
    @field:NotBlank(message = "재설정 토큰은 필수입니다.")
    val token: String?,

    @field:NotBlank(message = "새 비밀번호는 필수입니다.")
    @field:Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]+$",
        message = "비밀번호는 영문과 숫자를 포함해야 합니다."
    )
    val newPassword: String?
)

// 성공 응답은 data가 null이 아니어야 하므로 처리 결과만 반환
data class PasswordResetResponse(
    val success: Boolean = true
)

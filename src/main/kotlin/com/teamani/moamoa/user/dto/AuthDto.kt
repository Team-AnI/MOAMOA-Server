package com.teamani.moamoa.user.dto

import jakarta.validation.constraints.NotBlank

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

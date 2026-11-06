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

package com.teamani.moamoa.user.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UserSignUpRequest(
    @field:NotBlank(message = "이름은 필수입니다.")
    @field:Size(max = 20, message = "이름은 20자 이하여야 합니다.")
    val name: String?,

    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    @field:Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
    val email: String?,

    // BCrypt는 72바이트까지만 처리하므로 영문·숫자·기호(1글자 1바이트)로 72자까지 허용
    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]+$",
        message = "비밀번호는 영문과 숫자를 포함해야 합니다."
    )
    val password: String?,

    @field:Size(max = 20, message = "휴대폰 번호는 20자 이하여야 합니다.")
    val phone: String? = null,

    @field:NotNull(message = "약관 동의는 필수입니다.")
    @field:AssertTrue(message = "약관에 동의해야 가입할 수 있습니다.")
    val agreedToTerms: Boolean?
)

data class UserSignUpResponse(
    val userId: Long,
    val name: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String
)

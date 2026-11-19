package com.teamani.moamoa.user.controller

import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.user.dto.PasswordResetMailRequest
import com.teamani.moamoa.user.dto.PasswordResetRequest
import com.teamani.moamoa.user.dto.PasswordResetResponse
import com.teamani.moamoa.user.service.PasswordResetService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/auth")
class PasswordResetController(
    private val passwordResetService: PasswordResetService
) {

    @PostMapping("/password-reset-requests")
    fun requestPasswordReset(
        @Valid @RequestBody request: PasswordResetMailRequest
    ): ResponseEntity<CommonResponse<PasswordResetResponse>> =
        ResponseEntity.ok(CommonResponse(data = passwordResetService.requestReset(request)))

    @PostMapping("/password-resets")
    fun resetPassword(
        @Valid @RequestBody request: PasswordResetRequest
    ): ResponseEntity<CommonResponse<PasswordResetResponse>> =
        ResponseEntity.ok(CommonResponse(data = passwordResetService.resetPassword(request)))
}

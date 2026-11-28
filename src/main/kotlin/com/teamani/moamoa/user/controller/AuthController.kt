package com.teamani.moamoa.user.controller

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.LogoutRequest
import com.teamani.moamoa.user.dto.LogoutResponse
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.dto.TokenResponse
import com.teamani.moamoa.user.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<CommonResponse<TokenResponse>> =
        ResponseEntity.ok(CommonResponse(data = authService.login(request)))

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: TokenRefreshRequest): ResponseEntity<CommonResponse<TokenResponse>> =
        ResponseEntity.ok(CommonResponse(data = authService.refresh(request)))

    // 액세스 토큰이 없거나 유효하지 않으면 401, 요청한 사용자의 리프레시 토큰만 폐기
    @PostMapping("/logout")
    fun logout(
        @CurrentUserId currentUserId: Long,
        @Valid @RequestBody request: LogoutRequest
    ): ResponseEntity<CommonResponse<LogoutResponse>> {
        authService.logout(currentUserId, request)

        return ResponseEntity.ok(CommonResponse(data = LogoutResponse()))
    }
}

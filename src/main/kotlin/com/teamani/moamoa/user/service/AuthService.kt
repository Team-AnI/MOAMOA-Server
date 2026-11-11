package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.LogoutRequest
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.dto.TokenResponse
import com.teamani.moamoa.user.repository.AppUserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService
) {

    @Transactional
    fun login(request: LoginRequest): TokenResponse {
        val email = requireNotNull(request.email) { "이메일은 필수입니다." }.lowercase()
        val password = requireNotNull(request.password) { "비밀번호는 필수입니다." }

        // 무엇이 틀렸는지 구분하지 않도록 같은 응답 사용
        val user = appUserRepository.findByEmail(email)
            ?.takeIf { it.deletedAt == null && passwordEncoder.matches(password, it.password) }
            ?: throw ApiException(ErrorCode.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")

        return toResponse(tokenService.issueTokens(checkNotNull(user.id) { "사용자 식별자가 없습니다." }))
    }

    @Transactional
    fun refresh(request: TokenRefreshRequest): TokenResponse =
        toResponse(tokenService.reissueTokens(requireNotNull(request.refreshToken) { "리프레시 토큰은 필수입니다." }))

    @Transactional
    fun logout(request: LogoutRequest) {
        tokenService.revokeToken(requireNotNull(request.refreshToken) { "리프레시 토큰은 필수입니다." })
    }

    private fun toResponse(tokens: IssuedTokens) =
        TokenResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
}

package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.LogoutRequest
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.repository.AppUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.OffsetDateTime

class AuthServiceTests : BehaviorSpec({

    val appUserRepository: AppUserRepository = mock(AppUserRepository::class.java)
    val tokenService: TokenService = mock(TokenService::class.java)
    val passwordEncoder = BCryptPasswordEncoder(4)
    val authService = AuthService(appUserRepository, passwordEncoder, tokenService)

    val encodedPassword = checkNotNull(passwordEncoder.encode("moamoa1234"))

    fun user(deletedAt: OffsetDateTime? = null) = AppUser(
        id = 7L,
        name = "김모아",
        email = "moa@example.com",
        password = encodedPassword,
        termsAgreedAt = OffsetDateTime.parse("2026-11-01T10:00:00+09:00"),
        deletedAt = deletedAt
    )

    beforeEach {
        reset(appUserRepository, tokenService)
        `when`(tokenService.issueTokens(7L)).thenReturn(IssuedTokens("access", "refresh"))
    }

    Given("가입된 이메일과 맞는 비밀번호로 로그인 요청이 주어졌을 때") {
        When("이메일을 대문자가 섞인 채로 보내도") {
            Then("소문자로 바꿔 조회하고 토큰을 발급한다") {
                `when`(appUserRepository.findByEmail("moa@example.com")).thenReturn(user())

                val response = authService.login(LoginRequest(email = "MOA@Example.com", password = "moamoa1234"))

                response.accessToken shouldBe "access"
                response.refreshToken shouldBe "refresh"
                verify(tokenService).issueTokens(7L)
            }
        }
    }

    Given("로그인할 수 없는 요청이 주어졌을 때") {
        When("비밀번호가 틀리면") {
            Then("401 UNAUTHORIZED이고 토큰을 발급하지 않는다") {
                `when`(appUserRepository.findByEmail("moa@example.com")).thenReturn(user())

                val exception = shouldThrow<ApiException> {
                    authService.login(LoginRequest(email = "moa@example.com", password = "wrong-password"))
                }

                exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                verify(tokenService, never()).issueTokens(anyLong())
            }
        }

        When("가입되지 않은 이메일이면") {
            Then("비밀번호가 틀린 경우와 같은 401 응답이다") {
                val exception = shouldThrow<ApiException> {
                    authService.login(LoginRequest(email = "none@example.com", password = "moamoa1234"))
                }

                exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                exception.message shouldBe "이메일 또는 비밀번호가 올바르지 않습니다."
                verify(tokenService, never()).issueTokens(anyLong())
            }
        }

        When("탈퇴한 계정이면 비밀번호가 맞아도") {
            Then("401 UNAUTHORIZED이고 토큰을 발급하지 않는다") {
                `when`(appUserRepository.findByEmail("moa@example.com"))
                    .thenReturn(user(deletedAt = OffsetDateTime.parse("2026-11-02T10:00:00+09:00")))

                val exception = shouldThrow<ApiException> {
                    authService.login(LoginRequest(email = "moa@example.com", password = "moamoa1234"))
                }

                exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                verify(tokenService, never()).issueTokens(anyLong())
            }
        }
    }

    Given("토큰 재발급과 로그아웃 요청이 주어졌을 때") {
        When("재발급을 요청하면") {
            Then("리프레시 토큰으로 새 토큰 쌍을 받아 반환한다") {
                `when`(tokenService.reissueTokens("old-refresh")).thenReturn(IssuedTokens("new-access", "new-refresh"))

                val response = authService.refresh(TokenRefreshRequest(refreshToken = "old-refresh"))

                response.accessToken shouldBe "new-access"
                response.refreshToken shouldBe "new-refresh"
            }
        }

        When("로그아웃을 요청하면") {
            Then("해당 리프레시 토큰을 폐기한다") {
                authService.logout(LogoutRequest(refreshToken = "old-refresh"))

                verify(tokenService).revokeToken("old-refresh")
            }
        }
    }
})

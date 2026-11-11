package com.teamani.moamoa.user.controller

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.LogoutRequest
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.dto.TokenResponse
import com.teamani.moamoa.user.service.AuthService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

// 실제 MVC 설정(요청 검증 + GlobalExceptionHandler)으로 로그인·재발급·로그아웃 응답 형식을 검증
@WebMvcTest(controllers = [AuthController::class])
class AuthControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var authService: AuthService

    init {
        Given("로그인 요청(POST /v1/auth/login)이 주어졌을 때") {
            val request = LoginRequest(email = "moa@example.com", password = "moamoa1234")

            When("이메일과 비밀번호가 맞으면") {
                Then("200 OK와 토큰을 반환한다") {
                    `when`(authService.login(request))
                        .thenReturn(TokenResponse(accessToken = "access", refreshToken = "refresh"))

                    mockMvc.post("/v1/auth/login") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "email": "moa@example.com", "password": "moamoa1234" }"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.accessToken") { value("access") }
                        jsonPath("$.data.refreshToken") { value("refresh") }
                    }
                }
            }

            When("이메일 또는 비밀번호가 틀리면") {
                Then("401 Unauthorized와 UNAUTHORIZED 공통 오류 응답을 반환한다") {
                    `when`(authService.login(request))
                        .thenThrow(ApiException(ErrorCode.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."))

                    mockMvc.post("/v1/auth/login") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "email": "moa@example.com", "password": "moamoa1234" }"""
                    }.andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.error.code") { value("UNAUTHORIZED") }
                    }
                }
            }
        }

        Given("필수값이 누락된 요청이 주어졌을 때") {
            val invalidRequests = listOf(
                Triple("로그인 email 누락", "/v1/auth/login", """{ "password": "moamoa1234" }"""),
                Triple("로그인 password 누락", "/v1/auth/login", """{ "email": "moa@example.com" }"""),
                Triple("재발급 refreshToken 누락", "/v1/auth/refresh", "{}"),
                Triple("로그아웃 refreshToken 누락", "/v1/auth/logout", "{}")
            )

            invalidRequests.forEach { (case, path, requestJson) ->
                When("$case 요청을 보내면") {
                    Then("400 Bad Request와 VALIDATION_ERROR를 반환한다") {
                        mockMvc.post(path) {
                            contentType = MediaType.APPLICATION_JSON
                            content = requestJson
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                        }
                    }
                }
            }
        }

        Given("토큰 재발급 요청(POST /v1/auth/refresh)이 주어졌을 때") {
            val request = TokenRefreshRequest(refreshToken = "refresh")

            When("유효한 리프레시 토큰이면") {
                Then("200 OK와 새 토큰을 반환한다") {
                    `when`(authService.refresh(request))
                        .thenReturn(TokenResponse(accessToken = "new-access", refreshToken = "new-refresh"))

                    mockMvc.post("/v1/auth/refresh") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "refreshToken": "refresh" }"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.accessToken") { value("new-access") }
                        jsonPath("$.data.refreshToken") { value("new-refresh") }
                    }
                }
            }
        }

        Given("로그아웃 요청(POST /v1/auth/logout)이 주어졌을 때") {
            When("API를 호출하면") {
                Then("200 OK와 data.success=true를 반환한다") {
                    mockMvc.post("/v1/auth/logout") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "refreshToken": "refresh" }"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.success") { value(true) }
                        jsonPath("$.error") { value(null as Any?) }
                    }

                    verify(authService).logout(LogoutRequest(refreshToken = "refresh"))
                }
            }
        }
    }
}

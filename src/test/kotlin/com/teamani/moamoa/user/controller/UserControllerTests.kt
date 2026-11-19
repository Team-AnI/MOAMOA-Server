package com.teamani.moamoa.user.controller

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.dto.UserSignUpResponse
import com.teamani.moamoa.user.service.UserService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

// 실제 MVC 설정(요청 검증 + GlobalExceptionHandler)으로 회원가입 API 응답 형식을 검증
@WebMvcTest(controllers = [UserController::class])
class UserControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var userService: UserService

    private val valid = mapOf(
        "name" to "\"김모아\"",
        "email" to "\"moa@example.com\"",
        "password" to "\"moamoa1234\"",
        "agreedToTerms" to "true"
    )

    private fun json(fields: Map<String, String>) =
        fields.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\": ${it.value}" }

    init {
        Given("유효한 회원가입 요청(POST /v1/users)이 주어졌을 때") {
            val request = UserSignUpRequest(
                name = "김모아",
                email = "moa@example.com",
                password = "moamoa1234",
                agreedToTerms = true
            )

            When("API를 호출하면") {
                Then("201 Created와 공통 응답 형식으로 사용자 정보와 토큰을 반환한다") {
                    `when`(userService.signUp(request)).thenReturn(
                        UserSignUpResponse(
                            userId = 7L,
                            name = "김모아",
                            email = "moa@example.com",
                            accessToken = "access-token",
                            refreshToken = "refresh-token"
                        )
                    )

                    mockMvc.post("/v1/users") {
                        contentType = MediaType.APPLICATION_JSON
                        content = json(valid)
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.userId") { value(7) }
                        jsonPath("$.data.name") { value("김모아") }
                        jsonPath("$.data.email") { value("moa@example.com") }
                        jsonPath("$.data.accessToken") { value("access-token") }
                        jsonPath("$.data.refreshToken") { value("refresh-token") }
                        jsonPath("$.error") { value(null as Any?) }
                    }
                }
            }
        }

        Given("필수값이 누락되었거나 잘못된 회원가입 요청이 주어졌을 때") {
            val invalidRequests = mapOf(
                "name 누락" to json(valid - "name"),
                "공백 name" to json(valid + ("name" to "\"   \"")),
                "20자 초과 name" to json(valid + ("name" to "\"${"가".repeat(21)}\"")),
                "email 누락" to json(valid - "email"),
                "잘못된 email 형식" to json(valid + ("email" to "\"moa\"")),
                "password 누락" to json(valid - "password"),
                "8자 미만 password" to json(valid + ("password" to "\"moa1234\"")),
                "숫자 없는 password" to json(valid + ("password" to "\"moamoamoa\"")),
                "영문 없는 password" to json(valid + ("password" to "\"12345678\"")),
                "72자 초과 password" to json(valid + ("password" to "\"a1${"b".repeat(71)}\"")),
                "한글 포함 password" to json(valid + ("password" to "\"moa가나다1234\"")),
                "20자 초과 phone" to json(valid + ("phone" to "\"${"1".repeat(21)}\"")),
                "agreedToTerms 누락" to json(valid - "agreedToTerms"),
                "약관 미동의" to json(valid + ("agreedToTerms" to "false"))
            )

            invalidRequests.forEach { (case, requestJson) ->
                When("$case 요청으로 회원가입 API를 호출하면") {
                    Then("400 Bad Request와 VALIDATION_ERROR를 반환한다") {
                        mockMvc.post("/v1/users") {
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

        Given("이미 가입된 이메일로 회원가입 요청이 주어졌을 때") {
            val request = UserSignUpRequest(
                name = "김모아",
                email = "moa@example.com",
                password = "moamoa1234",
                agreedToTerms = true
            )

            When("API를 호출하면") {
                Then("409 Conflict와 CONFLICT 공통 오류 응답을 반환한다") {
                    `when`(userService.signUp(request))
                        .thenThrow(ApiException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다."))

                    mockMvc.post("/v1/users") {
                        contentType = MediaType.APPLICATION_JSON
                        content = json(valid)
                    }.andExpect {
                        status { isConflict() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.error.code") { value("CONFLICT") }
                    }
                }
            }
        }
    }
}

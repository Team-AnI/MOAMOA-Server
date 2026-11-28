package com.teamani.moamoa.user

import com.jayway.jsonpath.JsonPath
import com.teamani.moamoa.user.repository.AppUserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

// 실제 가입 토큰으로 내 정보 조회·수정(7-7·7-8)과 구성원 목록(1-7)의 이름·프로필 색을 검증
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyInfoIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    init {
        Given("가입한 사용자가 있을 때") {
            val (token, userId) = signUp(name = "김모아", phone = "010-1234-5678")

            When("토큰으로 내 정보를 조회하면") {
                Then("200과 이름·이메일·휴대폰 번호·기본 프로필 색을 반환한다") {
                    mockMvc.get("/v1/me") { header(HttpHeaders.AUTHORIZATION, token) }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.userId") { value(userId) }
                        jsonPath("$.data.name") { value("김모아") }
                        jsonPath("$.data.phone") { value("010-1234-5678") }
                        jsonPath("$.data.profileColor") { value("LIGHT_BLUE") }
                    }
                }
            }

            When("프로필 색만 보내 수정하면") {
                Then("프로필 색만 바뀌고 이름·휴대폰 번호는 유지된다") {
                    patchMe(token, """{ "profileColor": "BLUE" }""").andExpect {
                        status { isOk() }
                        jsonPath("$.data.profileColor") { value("BLUE") }
                        jsonPath("$.data.name") { value("김모아") }
                        jsonPath("$.data.phone") { value("010-1234-5678") }
                    }
                }
            }

            When("휴대폰 번호를 null로 보내 수정하면") {
                Then("휴대폰 번호만 비워진다") {
                    patchMe(token, """{ "phone": null }""").andExpect {
                        status { isOk() }
                        jsonPath("$.data.phone") { value(null as Any?) }
                        jsonPath("$.data.name") { value("김모아") }
                    }
                    appUserRepository.findById(userId).orElseThrow().phone shouldBe null
                }
            }

            listOf(
                "이름 공백" to """{ "name": "   " }""",
                "이름 null" to """{ "name": null }""",
                "이름 21자" to """{ "name": "${"가".repeat(21)}" }""",
                "프로필 색 null" to """{ "profileColor": null }""",
                "허용되지 않는 프로필 색" to """{ "profileColor": "PINK" }""",
                "휴대폰 번호 21자" to """{ "phone": "${"1".repeat(21)}" }"""
            ).forEach { (case, body) ->
                When("$case 로 수정하면") {
                    Then("400 VALIDATION_ERROR이고 내 정보는 바뀌지 않는다") {
                        patchMe(token, body).andExpect {
                            status { isBadRequest() }
                            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                        }
                        appUserRepository.findById(userId).orElseThrow().name shouldBe "김모아"
                    }
                }
            }

            When("모임을 만들고 구성원 목록을 조회하면") {
                Then("구성원의 실제 이름과 프로필 색을 반환한다") {
                    val meeting = mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, token)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "name": "모아모아" }"""
                    }.andReturn().response.contentAsString
                    val meetingId = JsonPath.read<Int>(meeting, "$.data.meetingId")

                    mockMvc.get("/v1/meetings/$meetingId/members") { header(HttpHeaders.AUTHORIZATION, token) }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.members[0].userName") { value("김모아") }
                        jsonPath("$.data.members[0].profileColor") { value("BLUE") }
                    }
                }
            }
        }

        Given("탈퇴한 사용자의 아직 만료되지 않은 토큰이 있을 때") {
            val (token, userId) = signUp(name = "탈퇴예정", phone = null)
            appUserRepository.save(appUserRepository.findById(userId).orElseThrow().apply { deletedAt = OffsetDateTime.now() })

            When("내 정보를 조회하거나 수정하면") {
                Then("401 UNAUTHORIZED이고 정보는 바뀌지 않는다") {
                    mockMvc.get("/v1/me") { header(HttpHeaders.AUTHORIZATION, token) }.andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.error.code") { value("UNAUTHORIZED") }
                    }
                    patchMe(token, """{ "profileColor": "DARK" }""").andExpect { status { isUnauthorized() } }
                    appUserRepository.findById(userId).orElseThrow().profileColor.name shouldBe "LIGHT_BLUE"
                }
            }
        }

        Given("토큰 없이 요청할 때") {
            When("내 정보를 조회하거나 수정하면") {
                Then("401 UNAUTHORIZED다") {
                    mockMvc.get("/v1/me").andExpect { status { isUnauthorized() } }
                    mockMvc.patch("/v1/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "profileColor": "BLUE" }"""
                    }.andExpect { status { isUnauthorized() } }
                }
            }
        }
    }

    private fun patchMe(token: String, body: String) = mockMvc.patch("/v1/me") {
        header(HttpHeaders.AUTHORIZATION, token)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    // 회원가입 응답의 액세스 토큰으로 "Bearer {토큰}"과 사용자 ID 반환
    private fun signUp(name: String, phone: String?): Pair<String, Long> {
        val phoneJson = phone?.let { "\"$it\"" } ?: "null"
        val body = mockMvc.post("/v1/users") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "name": "$name", "email": "me-${UUID.randomUUID()}@moamoa.test", "password": "password1", "phone": $phoneJson, "agreedToTerms": true }"""
        }.andReturn().response.contentAsString
        return "Bearer " + JsonPath.read<String>(body, "$.data.accessToken") to JsonPath.read<Number>(body, "$.data.userId").toLong()
    }
}

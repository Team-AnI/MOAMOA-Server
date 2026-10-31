package com.teamani.moamoa.settlement.controller

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.settlement.dto.RegularDuesCreateRequest
import com.teamani.moamoa.settlement.dto.RegularDuesCreateResponse
import com.teamani.moamoa.settlement.dto.RegularDuesDeleteResponse
import com.teamani.moamoa.settlement.dto.RegularDuesListResponse
import com.teamani.moamoa.settlement.dto.RegularDuesResponse
import com.teamani.moamoa.settlement.dto.RegularDuesUpdateRequest
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.service.RegularDuesService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.Optional

// 실제 MVC 설정(요청 검증 + GlobalExceptionHandler)으로 정기 회비 API 응답 형식을 검증
@WebMvcTest(controllers = [RegularDuesController::class])
class RegularDuesControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var regularDuesService: RegularDuesService

    private val response = RegularDuesResponse(
        regularDuesId = 7L,
        title = "월 회비",
        amount = 15000,
        cycleType = CycleType.MONTHLY,
        cycleDay = "10",
        sendTime = "09:00",
        dueDays = 7,
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아",
        isActive = false,
        postNotice = false
    )

    init {
        Given("유효한 정기 회비 등록 요청(POST /v1/meetings/{meetingId}/regular-dues)이 주어졌을 때") {
            val request = RegularDuesCreateRequest(
                title = "월 회비",
                amount = 10000,
                cycleType = "MONTHLY",
                cycleDay = "10",
                sendTime = "09:00",
                dueDays = 7,
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아",
                postNotice = false
            )

            When("API를 호출하면") {
                Then("201 Created와 공통 응답 형식으로 등록 요약을 반환한다") {
                    `when`(regularDuesService.createRegularDues(1L, 1L, request)).thenReturn(
                        RegularDuesCreateResponse(regularDuesId = 7L, title = "월 회비", isActive = true)
                    )

                    mockMvc.post("/v1/meetings/1/regular-dues") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """
                            {
                              "title": "월 회비",
                              "amount": 10000,
                              "cycleType": "MONTHLY",
                              "cycleDay": "10",
                              "sendTime": "09:00",
                              "dueDays": 7,
                              "bankName": "국민은행",
                              "accountNumber": "123456789012",
                              "accountHolder": "김모아",
                              "postNotice": false
                            }
                        """.trimIndent()
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.regularDuesId") { value(7) }
                        jsonPath("$.data.title") { value("월 회비") }
                        jsonPath("$.data.isActive") { value(true) }
                        jsonPath("$.error") { value(null as Any?) }
                    }
                }
            }
        }

        Given("당일 마감(dueDays 0)과 한 자리 시(9:00)로 정기 회비 등록 요청이 주어졌을 때") {
            val request = RegularDuesCreateRequest(
                title = "월 회비",
                amount = 10000,
                cycleType = "WEEKLY",
                cycleDay = "MON",
                sendTime = "9:00",
                dueDays = 0,
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아"
            )

            When("API를 호출하면") {
                Then("검증을 통과해 201 Created를 반환한다") {
                    `when`(regularDuesService.createRegularDues(1L, 1L, request)).thenReturn(
                        RegularDuesCreateResponse(regularDuesId = 8L, title = "월 회비", isActive = true)
                    )

                    mockMvc.post("/v1/meetings/1/regular-dues") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """
                            {
                              "title": "월 회비",
                              "amount": 10000,
                              "cycleType": "WEEKLY",
                              "cycleDay": "MON",
                              "sendTime": "9:00",
                              "dueDays": 0,
                              "bankName": "국민은행",
                              "accountNumber": "123456789012",
                              "accountHolder": "김모아"
                            }
                        """.trimIndent()
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.data.regularDuesId") { value(8) }
                    }
                }
            }
        }

        Given("필수값이 누락되었거나 잘못된 정기 회비 등록 요청이 주어졌을 때") {
            val valid = mapOf(
                "title" to "\"월 회비\"",
                "amount" to "10000",
                "cycleType" to "\"MONTHLY\"",
                "cycleDay" to "\"10\"",
                "dueDays" to "7",
                "bankName" to "\"국민은행\"",
                "accountNumber" to "\"123456789012\"",
                "accountHolder" to "\"김모아\""
            )
            fun json(fields: Map<String, String>) = fields.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\": ${it.value}" }

            val invalidRequests = mapOf(
                "title 누락" to json(valid - "title"),
                "공백 title" to json(valid + ("title" to "\"   \"")),
                "50자 초과 title" to json(valid + ("title" to "\"${"가".repeat(51)}\"")),
                "amount 0" to json(valid + ("amount" to "0")),
                "cycleType 누락" to json(valid - "cycleType"),
                "잘못된 cycleType" to json(valid + ("cycleType" to "\"DAILY\"")),
                "cycleDay 누락" to json(valid - "cycleDay"),
                "잘못된 sendTime 형식" to json(valid + ("sendTime" to "\"9시\"")),
                "범위를 벗어난 sendTime" to json(valid + ("sendTime" to "\"24:00\"")),
                "음수 dueDays" to json(valid + ("dueDays" to "-1")),
                "accountHolder 누락" to json(valid - "accountHolder")
            )

            invalidRequests.forEach { (case, requestJson) ->
                When("$case 요청으로 등록 API를 호출하면") {
                    Then("400 Bad Request와 VALIDATION_ERROR를 반환한다") {
                        mockMvc.post("/v1/meetings/1/regular-dues") {
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

        Given("정기 회비 목록 조회 요청(GET /v1/meetings/{meetingId}/regular-dues)이 주어졌을 때") {
            When("API를 호출하면") {
                Then("200 OK와 정기 회비 목록을 반환한다") {
                    `when`(regularDuesService.getRegularDuesList(1L, 1L))
                        .thenReturn(RegularDuesListResponse(regularDues = listOf(response)))

                    mockMvc.get("/v1/meetings/1/regular-dues").andExpect {
                        status { isOk() }
                        jsonPath("$.data.regularDues[0].regularDuesId") { value(7) }
                        jsonPath("$.data.regularDues[0].cycleType") { value("MONTHLY") }
                        jsonPath("$.data.regularDues[0].sendTime") { value("09:00") }
                        jsonPath("$.data.regularDues[0].bankName") { value("국민은행") }
                        jsonPath("$.data.regularDues[0].accountNumber") { value("123456789012") }
                        jsonPath("$.data.regularDues[0].accountHolder") { value("김모아") }
                        jsonPath("$.data.regularDues[0].isActive") { value(false) }
                        jsonPath("$.data.regularDues[0].postNotice") { value(false) }
                    }
                }
            }
        }

        Given("일부 필드만 담은 정기 회비 수정 요청(PATCH)이 주어졌을 때") {
            When("API를 호출하면") {
                Then("보내지 않은 필드는 null(미전송)로 전달되고 200 OK와 수정 결과를 반환한다") {
                    val request = RegularDuesUpdateRequest(amount = Optional.of(15000), isActive = Optional.of(false))
                    `when`(regularDuesService.updateRegularDues(1L, 1L, 7L, request)).thenReturn(response)

                    mockMvc.patch("/v1/meetings/1/regular-dues/7") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "amount": 15000, "isActive": false }"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.amount") { value(15000) }
                        jsonPath("$.data.bankName") { value("국민은행") }
                        jsonPath("$.data.isActive") { value(false) }
                    }
                }
            }
        }

        Given("필드를 null로 보낸 정기 회비 수정 요청이 주어졌을 때") {
            When("API를 호출하면") {
                Then("null 전송은 Optional.empty로 구분되어 Service에 전달된다") {
                    val request = RegularDuesUpdateRequest(title = Optional.empty())
                    `when`(regularDuesService.updateRegularDues(1L, 1L, 7L, request))
                        .thenThrow(ApiException(ErrorCode.VALIDATION_ERROR, "회비명은(는) 비울 수 없습니다."))

                    mockMvc.patch("/v1/meetings/1/regular-dues/7") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "title": null }"""
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                    }
                }
            }
        }

        Given("잘못된 값을 담은 정기 회비 수정 요청이 주어졌을 때") {
            val invalidRequests = mapOf(
                "공백 title" to """{ "title": "   " }""",
                "50자 초과 title" to """{ "title": "${"가".repeat(51)}" }""",
                "amount 0" to """{ "amount": 0 }""",
                "잘못된 cycleType" to """{ "cycleType": "DAILY", "cycleDay": "MON" }""",
                "잘못된 sendTime" to """{ "sendTime": "25:00" }""",
                "음수 dueDays" to """{ "dueDays": -1 }"""
            )

            invalidRequests.forEach { (case, requestJson) ->
                When("$case 요청으로 수정 API를 호출하면") {
                    Then("400 Bad Request와 VALIDATION_ERROR를 반환한다") {
                        mockMvc.patch("/v1/meetings/1/regular-dues/7") {
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

        Given("정기 회비 삭제 요청(DELETE)이 주어졌을 때") {
            When("API를 호출하면") {
                Then("200 OK와 삭제한 정기 회비 ID를 반환한다") {
                    `when`(regularDuesService.deleteRegularDues(1L, 1L, 7L))
                        .thenReturn(RegularDuesDeleteResponse(regularDuesId = 7L))

                    mockMvc.delete("/v1/meetings/1/regular-dues/7").andExpect {
                        status { isOk() }
                        jsonPath("$.data.regularDuesId") { value(7) }
                    }
                }
            }
        }

        Given("없거나 다른 모임에 속한 정기 회비의 삭제 요청이 주어졌을 때") {
            When("API를 호출하면") {
                Then("404 Not Found와 NOT_FOUND 공통 에러 응답을 반환한다") {
                    `when`(regularDuesService.deleteRegularDues(1L, 1L, 999L))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "정기 회비를 찾을 수 없습니다."))

                    mockMvc.delete("/v1/meetings/1/regular-dues/999").andExpect {
                        status { isNotFound() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.error.code") { value("NOT_FOUND") }
                    }
                }
            }
        }
    }
}

package com.teamani.moamoa.settlement.controller

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.settlement.dto.PaymentApproveResponse
import com.teamani.moamoa.settlement.dto.PaymentRejectResponse
import com.teamani.moamoa.settlement.dto.PaymentSubmitResponse
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.dto.SettlementCreateResponse
import com.teamani.moamoa.settlement.dto.SettlementListResponse
import com.teamani.moamoa.settlement.dto.SettlementSummaryResponse
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.service.SettlementService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime

// 실제 MVC 설정(요청 검증 + GlobalExceptionHandler)으로 정산 API 응답 형식을 검증
@WebMvcTest(controllers = [SettlementController::class])
class SettlementControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var settlementService: SettlementService

    init {
        // 요청 일시는 역직렬화 시 오프셋이 바뀌어도 equals가 유지되도록 UTC(Z)로 작성
        Given("유효한 정산 생성 요청(POST /v1/meetings/{meetingId}/settlements)이 주어졌을 때") {
            val request = SettlementCreateRequest(
                scheduleId = 20L,
                title = "10월 정기 모임 식비",
                totalAmount = 45000,
                memberIds = listOf(3L, 5L, 7L),
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아",
                dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
            )

            When("API를 호출하면") {
                Then("201 Created와 공통 응답 형식으로 정산 요약을 반환한다") {
                    `when`(settlementService.createSettlement(1L, 1L, request)).thenReturn(
                        SettlementCreateResponse(
                            settlementId = 40L,
                            title = "10월 정기 모임 식비",
                            totalAmount = 45000,
                            memberCount = 3,
                            status = SettlementStatus.IN_PROGRESS,
                            noticeId = null
                        )
                    )

                    mockMvc.post("/v1/meetings/1/settlements") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """
                            {
                              "scheduleId": 20,
                              "title": "10월 정기 모임 식비",
                              "totalAmount": 45000,
                              "memberIds": [3, 5, 7],
                              "bankName": "국민은행",
                              "accountNumber": "123456789012",
                              "accountHolder": "김모아",
                              "dueAt": "2026-10-20T14:59:00Z"
                            }
                        """.trimIndent()
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.settlementId") { value(40) }
                        jsonPath("$.data.memberCount") { value(3) }
                        jsonPath("$.data.status") { value("IN_PROGRESS") }
                        jsonPath("$.data.noticeId") { value(null as Any?) }
                        jsonPath("$.error") { value(null as Any?) }
                    }
                }
            }
        }

        Given("필수값이 누락되었거나 잘못된 정산 생성 요청이 주어졌을 때") {
            val valid = mapOf(
                "title" to "\"10월 정기 모임 식비\"",
                "totalAmount" to "45000",
                "memberIds" to "[3, 5, 7]",
                "bankName" to "\"국민은행\"",
                "accountNumber" to "\"123456789012\"",
                "accountHolder" to "\"김모아\"",
                "dueAt" to "\"2026-10-20T14:59:00Z\""
            )
            fun json(fields: Map<String, String>) = fields.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\": ${it.value}" }

            val invalidRequests = mapOf(
                "title 누락" to json(valid - "title"),
                "공백 title" to json(valid + ("title" to "\"   \"")),
                "50자 초과 title" to json(valid + ("title" to "\"${"가".repeat(51)}\"")),
                "totalAmount 0" to json(valid + ("totalAmount" to "0")),
                "memberIds 누락" to json(valid - "memberIds"),
                "빈 memberIds" to json(valid + ("memberIds" to "[]")),
                "bankName 누락" to json(valid - "bankName"),
                "dueAt 누락" to json(valid - "dueAt")
            )

            invalidRequests.forEach { (case, requestJson) ->
                When("$case 요청으로 생성 API를 호출하면") {
                    Then("400 Bad Request와 VALIDATION_ERROR를 반환한다") {
                        mockMvc.post("/v1/meetings/1/settlements") {
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

        Given("정산 목록 조회 요청(GET /v1/meetings/{meetingId}/settlements)이 주어졌을 때") {
            When("API를 호출하면") {
                Then("200 OK와 정산 목록을 반환하고 대상이 아닌 정산의 내 정보는 null이다") {
                    `when`(settlementService.getSettlements(1L, 1L)).thenReturn(
                        SettlementListResponse(
                            settlements = listOf(
                                SettlementSummaryResponse(
                                    settlementId = 40L,
                                    title = "10월 정기 모임 식비",
                                    scheduleId = null,
                                    totalAmount = 45000,
                                    memberCount = 3,
                                    dueAt = OffsetDateTime.parse("2026-10-20T23:59:00+09:00"),
                                    createdAt = OffsetDateTime.parse("2026-10-14T10:00:00+09:00"),
                                    status = SettlementStatus.IN_PROGRESS,
                                    myAmount = null,
                                    myPaymentStatus = null,
                                    myConfirmedAt = null
                                )
                            )
                        )
                    )

                    mockMvc.get("/v1/meetings/1/settlements").andExpect {
                        status { isOk() }
                        jsonPath("$.data.settlements[0].settlementId") { value(40) }
                        jsonPath("$.data.settlements[0].memberCount") { value(3) }
                        jsonPath("$.data.settlements[0].scheduleId") { value(null as Any?) }
                        jsonPath("$.data.settlements[0].myPaymentStatus") { value(null as Any?) }
                    }
                }
            }
        }

        Given("없거나 다른 모임에 속한 정산의 상세 조회 요청이 주어졌을 때") {
            When("상세 API를 호출하면") {
                Then("404 Not Found와 NOT_FOUND 공통 에러 응답을 반환한다") {
                    `when`(settlementService.getSettlement(1L, 1L, 999L))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "정산을 찾을 수 없습니다."))

                    mockMvc.get("/v1/meetings/1/settlements/999").andExpect {
                        status { isNotFound() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.error.code") { value("NOT_FOUND") }
                    }
                }
            }
        }

        Given("납부 완료 제출 요청(POST .../payments/me/submissions)이 주어졌을 때") {
            When("미납 상태에서 API를 호출하면") {
                Then("200 OK와 확인 대기 상태를 반환한다") {
                    `when`(settlementService.submitPayment(1L, 1L, 40L)).thenReturn(
                        PaymentSubmitResponse(
                            paymentId = 81L,
                            status = PaymentStatus.PENDING,
                            submittedAt = OffsetDateTime.parse("2026-10-14T10:00:00+09:00")
                        )
                    )

                    mockMvc.post("/v1/meetings/1/settlements/40/payments/me/submissions").andExpect {
                        status { isOk() }
                        jsonPath("$.data.paymentId") { value(81) }
                        jsonPath("$.data.status") { value("PENDING") }
                        jsonPath("$.data.submittedAt") { exists() }
                    }
                }
            }

            When("이미 제출한 상태에서 API를 호출하면") {
                Then("409 Conflict와 CONFLICT 공통 에러 응답을 반환한다") {
                    `when`(settlementService.submitPayment(1L, 1L, 41L))
                        .thenThrow(ApiException(ErrorCode.CONFLICT, "이미 납부 완료를 제출했거나 완납한 정산입니다."))

                    mockMvc.post("/v1/meetings/1/settlements/41/payments/me/submissions").andExpect {
                        status { isConflict() }
                        jsonPath("$.error.code") { value("CONFLICT") }
                    }
                }
            }
        }

        Given("납부 승인 요청(POST .../payments/{paymentId}/approval)이 주어졌을 때") {
            When("API를 호출하면") {
                Then("200 OK와 완납 상태를 반환한다") {
                    `when`(settlementService.approvePayment(1L, 1L, 40L, 81L)).thenReturn(
                        PaymentApproveResponse(
                            paymentId = 81L,
                            status = PaymentStatus.PAID,
                            confirmedAt = OffsetDateTime.parse("2026-10-14T10:00:00+09:00")
                        )
                    )

                    mockMvc.post("/v1/meetings/1/settlements/40/payments/81/approval").andExpect {
                        status { isOk() }
                        jsonPath("$.data.status") { value("PAID") }
                        jsonPath("$.data.confirmedAt") { exists() }
                    }
                }
            }
        }

        Given("납부 반려 요청(POST .../payments/{paymentId}/rejection)이 주어졌을 때") {
            When("반려 사유와 함께 호출하면") {
                Then("200 OK와 미납 상태, 반려 사유를 반환한다") {
                    `when`(settlementService.rejectPayment(1L, 1L, 40L, 81L, "입금 내역을 확인할 수 없습니다.")).thenReturn(
                        PaymentRejectResponse(81L, PaymentStatus.UNPAID, "입금 내역을 확인할 수 없습니다.")
                    )

                    mockMvc.post("/v1/meetings/1/settlements/40/payments/81/rejection") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "reason": "입금 내역을 확인할 수 없습니다." }"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.status") { value("UNPAID") }
                        jsonPath("$.data.rejectionReason") { value("입금 내역을 확인할 수 없습니다.") }
                    }
                }
            }

            When("요청 본문 없이 호출하면") {
                Then("반려 사유 없이 반려한다") {
                    `when`(settlementService.rejectPayment(1L, 1L, 40L, 82L, null)).thenReturn(
                        PaymentRejectResponse(82L, PaymentStatus.UNPAID, null)
                    )

                    mockMvc.post("/v1/meetings/1/settlements/40/payments/82/rejection").andExpect {
                        status { isOk() }
                        jsonPath("$.data.paymentId") { value(82) }
                        jsonPath("$.data.rejectionReason") { value(null as Any?) }
                    }
                }
            }

            When("255자를 초과한 반려 사유로 호출하면") {
                Then("400 Bad Request와 VALIDATION_ERROR를 반환한다") {
                    mockMvc.post("/v1/meetings/1/settlements/40/payments/81/rejection") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "reason": "${"가".repeat(256)}" }"""
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                    }
                }
            }
        }

        Given("숫자가 아닌 정산 ID로 상세 조회 요청이 주어졌을 때") {
            When("상세 API를 호출하면") {
                Then("400 Bad Request와 INPUT_ERROR를 반환한다") {
                    mockMvc.get("/v1/meetings/1/settlements/abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.error.code") { value("INPUT_ERROR") }
                    }
                }
            }
        }
    }
}

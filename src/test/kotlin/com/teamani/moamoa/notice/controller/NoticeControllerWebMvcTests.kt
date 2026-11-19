package com.teamani.moamoa.notice.controller

import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.notice.dto.NoticeListResponse
import com.teamani.moamoa.notice.dto.NoticeUpdateRequest
import com.teamani.moamoa.notice.dto.NoticeUpdateResponse
import com.teamani.moamoa.notice.service.NoticeService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import java.util.Optional

// 실제 Spring MVC 설정(요청 검증 + GlobalExceptionHandler)으로 목록 조회 페이징 파라미터와 수정 API 응답 형식을 검증
@WebMvcTest(controllers = [NoticeController::class])
class NoticeControllerWebMvcTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var noticeService: NoticeService

    @MockitoBean
    lateinit var jwtTokenProvider: JwtTokenProvider

    init {
        beforeTest {
            `when`(jwtTokenProvider.getUserId(ACCESS_TOKEN)).thenReturn(1L)
        }

        Given("허용 범위를 벗어난 페이징 파라미터로 목록 조회 요청이 주어졌을 때") {
            val invalidParams = mapOf(
                "page=-1" to ("page" to "-1"),
                "size=0" to ("size" to "0"),
                "size=101" to ("size" to "101")
            )

            invalidParams.forEach { (case, param) ->
                When("$case 로 목록 조회 API를 호출하면") {
                    Then("400 Bad Request와 VALIDATION_ERROR 공통 오류 응답을 반환한다") {
                        mockMvc.get("/v1/meetings/1/notices") {
                            header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                            param(param.first, param.second)
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.success") { value(false) }
                            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                        }
                    }
                }
            }
        }

        Given("페이징 파라미터 없이 목록 조회 요청이 주어졌을 때") {
            When("목록 조회 API를 호출하면") {
                Then("기본값 page=0, size=20으로 조회한다") {
                    `when`(noticeService.getNotices(1L, 0, 20, 1L)).thenReturn(
                        NoticeListResponse(notices = emptyList(), page = 0, size = 20, hasNext = false)
                    )

                    mockMvc.get("/v1/meetings/1/notices") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.page") { value(0) }
                        jsonPath("$.data.size") { value(20) }
                    }
                }
            }
        }

        Given("공지사항 수정 요청(PATCH /v1/meetings/{meetingId}/notices/{noticeId})이 주어졌을 때") {
            When("제목만 보내면") {
                Then("내용은 미전송(null)으로 전달되고 200 OK와 공통 응답 형식으로 수정 결과를 반환한다") {
                    `when`(noticeService.updateNotice(1L, 30L, 1L, NoticeUpdateRequest(title = Optional.of("수정된 제목"))))
                        .thenReturn(NoticeUpdateResponse(noticeId = 30L, title = "수정된 제목", content = "기존 내용"))

                    mockMvc.patch("/v1/meetings/1/notices/30") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "title": "수정된 제목" }"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.noticeId") { value(30) }
                        jsonPath("$.data.title") { value("수정된 제목") }
                        jsonPath("$.data.content") { value("기존 내용") }
                        jsonPath("$.error") { value(null as Any?) }
                        jsonPath("$.timestamp") { exists() }
                    }
                }
            }

            When("내용만 보내면") {
                Then("제목은 미전송(null)으로 전달되고 기존 제목이 유지된 결과를 반환한다") {
                    `when`(noticeService.updateNotice(1L, 30L, 1L, NoticeUpdateRequest(content = Optional.of("수정된 내용"))))
                        .thenReturn(NoticeUpdateResponse(noticeId = 30L, title = "기존 제목", content = "수정된 내용"))

                    mockMvc.patch("/v1/meetings/1/notices/30") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "content": "수정된 내용" }"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.title") { value("기존 제목") }
                        jsonPath("$.data.content") { value("수정된 내용") }
                    }
                }
            }

            When("빈 요청 객체({})를 보내면") {
                Then("변경 없이 200 OK와 기존 값을 반환한다") {
                    `when`(noticeService.updateNotice(1L, 30L, 1L, NoticeUpdateRequest()))
                        .thenReturn(NoticeUpdateResponse(noticeId = 30L, title = "기존 제목", content = "기존 내용"))

                    mockMvc.patch("/v1/meetings/1/notices/30") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = "{}"
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.title") { value("기존 제목") }
                        jsonPath("$.data.content") { value("기존 내용") }
                    }
                }
            }
        }

        Given("잘못된 값을 담은 공지사항 수정 요청이 주어졌을 때") {
            val invalidRequests = mapOf(
                "50자 초과 제목" to """{ "title": "${"가".repeat(51)}" }""",
                "공백 제목" to """{ "title": "   " }""",
                "공백 내용" to """{ "content": "" }""",
                "null 제목" to """{ "title": null }""",
                "null 내용" to """{ "content": null }"""
            )

            invalidRequests.forEach { (case, requestJson) ->
                When("$case 으로 수정 API를 호출하면") {
                    Then("400 Bad Request와 VALIDATION_ERROR 공통 오류 응답을 반환한다") {
                        mockMvc.patch("/v1/meetings/1/notices/30") {
                            header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                            contentType = MediaType.APPLICATION_JSON
                            content = requestJson
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.success") { value(false) }
                            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                        }
                    }
                }
            }
        }

        Given("없거나 다른 모임에 속한 공지사항의 수정 요청이 주어졌을 때") {
            When("수정 API를 호출하면") {
                Then("404 Not Found와 NOT_FOUND 공통 오류 응답을 반환한다") {
                    `when`(noticeService.updateNotice(1L, 999L, 1L, NoticeUpdateRequest(title = Optional.of("수정된 제목"))))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다."))

                    mockMvc.patch("/v1/meetings/1/notices/999") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{ "title": "수정된 제목" }"""
                    }.andExpect {
                        status { isNotFound() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.data") { value(null as Any?) }
                        jsonPath("$.error.code") { value("NOT_FOUND") }
                    }
                }
            }
        }
    }

    companion object {
        // 토큰 검증은 mock으로 대체: ACCESS_TOKEN을 보내면 사용자 1로 인증
        private const val ACCESS_TOKEN = "test-access-token"
        private const val BEARER_TOKEN = "Bearer $ACCESS_TOKEN"
    }
}

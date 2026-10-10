package com.teamani.moamoa.notice.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.dto.NoticeListResponse
import com.teamani.moamoa.notice.dto.NoticeSummaryResponse
import com.teamani.moamoa.notice.dto.NoticeUpdateRequest
import com.teamani.moamoa.notice.dto.NoticeUpdateResponse
import com.teamani.moamoa.notice.exception.NoticeNotFoundException
import com.teamani.moamoa.notice.service.NoticeService
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.OffsetDateTime

class NoticeControllerTests : BehaviorSpec({

    val noticeService: NoticeService = mock(NoticeService::class.java)
    val noticeController = NoticeController(noticeService)
    val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(noticeController).build()
    val objectMapper = ObjectMapper()

    Given("유효한 공지사항 작성 요청(POST /v1/meetings/{meetingId}/notices)이 주어졌을 때") {
        val meetingId = 1L
        val request = NoticeCreateRequest(
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다."
        )
        val mockResponse = NoticeCreateResponse(
            noticeId = 30L,
            title = "10월 회비 안내"
        )

        `when`(noticeService.createNotice(meetingId, 1L, request)).thenReturn(mockResponse)

        When("API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/$meetingId/notices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )

            Then("201 Created 응답과 공통 Envelope 포맷이 반환된다") {
                resultActions
                    .andExpect(status().isCreated)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.data.title").value("10월 회비 안내"))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }
    Given("제목이 50자를 초과하거나 내용이 빈 공지 작성 요청이 주어졌을 때") {
        val meetingId = 1L
        val longTitleRequest = NoticeCreateRequest(title = "a".repeat(51), content = "내용")
        val blankContentRequest = NoticeCreateRequest(title = "제목", content = "   ")

        When("50자를 초과한 제목으로 작성 API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/$meetingId/notices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(longTitleRequest))
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }

        When("공백 내용으로 작성 API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/$meetingId/notices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(blankContentRequest))
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }
    }

    Given("공지사항 삭제 요청(DELETE)이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val mockResponse = NoticeDeleteResponse(noticeId = noticeId)

        `when`(noticeService.deleteNotice(meetingId, noticeId, 1L)).thenReturn(mockResponse)

        When("삭제 API를 호출하면") {
            val resultActions = mockMvc.perform(
                delete("/v1/meetings/$meetingId/notices/$noticeId")
            )

            Then("200 OK 응답과 Envelope 포맷이 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("특정 공지사항 상세 조회 요청(GET)이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val now = OffsetDateTime.now()
        val mockResponse = NoticeDetailResponse(
            noticeId = noticeId,
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다.",
            createdAt = now
        )

        `when`(noticeService.getNoticeDetail(meetingId, noticeId, 1L)).thenReturn(mockResponse)

        When("상세 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices/$noticeId")
            )

            Then("200 OK 응답과 상세 데이터 규격이 Envelope 형식으로 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.data.title").value("10월 회비 안내"))
                    .andExpect(jsonPath("$.data.content").value("10월 회비는 10일까지 납부 부탁드립니다."))
                    .andExpect(jsonPath("$.data.createdAt").exists())
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("공지사항 목록 조회 요청(GET)이 주어졌을 때") {
        val meetingId = 1L
        val page = 0
        val size = 20
        val now = OffsetDateTime.now()
        val mockResponse = NoticeListResponse(
            notices = listOf(
                NoticeSummaryResponse(
                    noticeId = 30L,
                    title = "10월 회비 안내",
                    createdAt = now
                )
            ),
            page = 0,
            size = 20,
            hasNext = false
        )

        `when`(noticeService.getNotices(meetingId, page, size, 1L)).thenReturn(mockResponse)

        When("목록 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices")
                    .param("page", "0")
                    .param("size", "20")
            )

            Then("200 OK 응답과 notices 배열 및 페이징 정보가 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.notices[0].noticeId").value(30L))
                    .andExpect(jsonPath("$.data.notices[0].title").value("10월 회비 안내"))
                    .andExpect(jsonPath("$.data.notices[0].createdAt").exists())
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("page가 음수이거나 size가 허용 범위를 벗어난 목록 조회 요청이 주어졌을 때") {
        val meetingId = 1L

        When("음수 page로 목록 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices")
                    .param("page", "-1")
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }

        When("100을 초과한 size로 목록 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices")
                    .param("size", "101")
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }
    }

    Given("제목만 담긴 공지사항 수정 요청(PATCH)이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val request = NoticeUpdateRequest(title = "수정된 제목")
        val mockResponse = NoticeUpdateResponse(
            noticeId = noticeId,
            title = "수정된 제목",
            content = "기존 내용"
        )

        `when`(noticeService.updateNotice(meetingId, noticeId, 1L, request)).thenReturn(mockResponse)

        When("수정 API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/$meetingId/notices/$noticeId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )

            Then("200 OK 응답과 수정된 데이터가 Envelope 형식으로 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.data.title").value("수정된 제목"))
                    .andExpect(jsonPath("$.data.content").value("기존 내용"))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("존재하지 않거나 다른 모임의 공지사항 수정 요청이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 999L
        val request = NoticeUpdateRequest(title = "수정된 제목")

        `when`(noticeService.updateNotice(meetingId, noticeId, 1L, request))
            .thenThrow(NoticeNotFoundException("공지사항을 찾을 수 없습니다. (ID: $noticeId)"))

        When("수정 API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/$meetingId/notices/$noticeId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )

            Then("404 Not Found를 반환한다") {
                resultActions.andExpect(status().isNotFound)
            }
        }
    }

    Given("공백 제목 또는 공백 내용이 담긴 공지사항 수정 요청이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val blankTitleRequest = NoticeUpdateRequest(title = "   ")
        val emptyContentRequest = NoticeUpdateRequest(content = "")

        When("공백 제목으로 수정 API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/$meetingId/notices/$noticeId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(blankTitleRequest))
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }

        When("빈 문자열 내용으로 수정 API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/$meetingId/notices/$noticeId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(emptyContentRequest))
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }
    }
})

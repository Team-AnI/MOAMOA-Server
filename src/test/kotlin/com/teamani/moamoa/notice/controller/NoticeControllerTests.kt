package com.teamani.moamoa.notice.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.service.NoticeService
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.OffsetDateTime
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

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
})

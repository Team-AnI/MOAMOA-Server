package com.teamani.moamoa.notice.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.service.NoticeService
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

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
})

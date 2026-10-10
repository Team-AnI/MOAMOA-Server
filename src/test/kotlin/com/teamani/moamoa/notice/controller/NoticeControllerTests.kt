//package com.teamani.moamoa.notice.controller
//
//import com.fasterxml.jackson.databind.ObjectMapper
//import com.teamani.moamoa.notice.dto.NoticeCreateRequest
//import com.teamani.moamoa.notice.dto.NoticeCreateResponse
//import com.teamani.moamoa.notice.service.NoticeService
//import org.junit.jupiter.api.BeforeEach
//import org.junit.jupiter.api.DisplayName
//import org.junit.jupiter.api.Test
//import org.mockito.Mockito.`when`
//import org.mockito.Mockito.mock
//import org.springframework.http.MediaType
//import org.springframework.test.web.servlet.MockMvc
//import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
//import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
//import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
//import org.springframework.test.web.servlet.setup.MockMvcBuilders
//
//class NoticeControllerTests {
//
//    private val noticeService: NoticeService = mock(NoticeService::class.java)
//    private val noticeController = NoticeController(noticeService)
//    private lateinit var mockMvc: MockMvc
//    private val objectMapper = ObjectMapper()
//
//    @BeforeEach
//    fun setUp() {
//        mockMvc = MockMvcBuilders.standaloneSetup(noticeController).build()
//    }
//
//    @Test
//    @DisplayName("공지 작성 API 호출 시 201 Created 및 Envelope 규격이 반환된다")
//    fun createNoticeSuccess() {
//        val meetingId = 1L
//        val request = NoticeCreateRequest(
//            title = "10월 회비 안내",
//            content = "10월 회비는 10일까지 납부 부탁드립니다."
//        )
//        val mockResponse = NoticeCreateResponse(
//            noticeId = 30L,
//            title = "10월 회비 안내"
//        )
//
//        `when`(noticeService.createNotice(meetingId, 1L, request)).thenReturn(mockResponse)
//
//        // when & then: 201 Created 및 규격 검증
//        mockMvc.perform(
//            post("/v1/meetings/$meetingId/notices")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(request))
//        )
//            .andExpect(status().isCreated)
//            .andExpect(jsonPath("$.success").value(true))
//            .andExpect(jsonPath("$.data.title").value("10월 회비 안내"))
//            .andExpect(jsonPath("$.data.noticeId").value(30L))
//            .andExpect(jsonPath("$.error").doesNotExist())
//    }
//
//    @Test
//    @DisplayName("공지 제목이 50자를 초과하면 400 Bad Request를 반환한다")
//    fun createNoticeFailWhenTitleExceeds50() {
//        // given: 51글자 제목 생성
//        val meetingId = 1L
//        val longTitle = "a".repeat(51)
//        val request = NoticeCreateRequest(
//            title = longTitle,
//            content = "정상적인 공지 내용"
//        )
//
//        // when & then: 400 검증
//        mockMvc.perform(
//            post("/v1/meetings/$meetingId/notices")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(request))
//        )
//            .andExpect(status().isBadRequest)
//    }
//}


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
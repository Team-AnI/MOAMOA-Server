package com.teamani.moamoa.schedule.controller

import com.teamani.moamoa.schedule.dto.ScheduleCreateRequest
import com.teamani.moamoa.schedule.dto.ScheduleCreateResponse
import com.teamani.moamoa.schedule.service.ScheduleService
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.OffsetDateTime

class ScheduleControllerTests : BehaviorSpec({

    val scheduleService: ScheduleService = mock(ScheduleService::class.java)
    val scheduleController = ScheduleController(scheduleService)
    val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(scheduleController).build()

    // 요청 일시는 역직렬화 시 오프셋이 바뀌어도 equals가 유지되도록 UTC(Z)로 작성
    Given("유효한 일정 생성 요청(POST /v1/meetings/{meetingId}/schedules)이 주어졌을 때") {
        val meetingId = 1L
        val requestJson = """
            {
              "title": "10월 정기 러닝",
              "description": "반포 한강공원 집합",
              "startAt": "2026-10-10T22:00:00Z",
              "endAt": "2026-10-11T00:00:00Z",
              "location": "반포 한강공원"
            }
        """.trimIndent()
        val request = ScheduleCreateRequest(
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startAt = OffsetDateTime.parse("2026-10-10T22:00:00Z"),
            endAt = OffsetDateTime.parse("2026-10-11T00:00:00Z"),
            location = "반포 한강공원"
        )
        val mockResponse = ScheduleCreateResponse(
            scheduleId = 20L,
            title = "10월 정기 러닝",
            startAt = OffsetDateTime.parse("2026-10-10T22:00:00Z")
        )

        `when`(scheduleService.createSchedule(meetingId, 1L, request)).thenReturn(mockResponse)

        When("API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/$meetingId/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson)
            )

            Then("201 Created 응답과 공통 Envelope 포맷이 반환된다") {
                resultActions
                    .andExpect(status().isCreated)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.scheduleId").value(20L))
                    .andExpect(jsonPath("$.data.title").value("10월 정기 러닝"))
                    .andExpect(jsonPath("$.data.startAt").exists())
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("필수값이 누락되었거나 일시 순서가 잘못된 일정 생성 요청이 주어졌을 때") {
        val meetingId = 1L
        val invalidRequests = mapOf(
            "title 누락" to """{ "startAt": "2026-10-10T22:00:00Z" }""",
            "공백 title" to """{ "title": "   ", "startAt": "2026-10-10T22:00:00Z" }""",
            "startAt 누락" to """{ "title": "10월 정기 러닝" }""",
            "endAt이 startAt보다 이전" to """
                { "title": "10월 정기 러닝", "startAt": "2026-10-10T22:00:00Z", "endAt": "2026-10-10T21:00:00Z" }
            """.trimIndent()
        )

        invalidRequests.forEach { (case, requestJson) ->
            When("$case 요청으로 생성 API를 호출하면") {
                val resultActions = mockMvc.perform(
                    post("/v1/meetings/$meetingId/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                )

                Then("400 Bad Request를 반환한다") {
                    resultActions.andExpect(status().isBadRequest)
                }
            }
        }
    }
})

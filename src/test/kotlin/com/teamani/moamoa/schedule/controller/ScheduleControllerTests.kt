package com.teamani.moamoa.schedule.controller

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.global.exception.GlobalExceptionHandler
import com.teamani.moamoa.schedule.dto.ScheduleCreateRequest
import com.teamani.moamoa.schedule.dto.ScheduleCreateResponse
import com.teamani.moamoa.schedule.dto.ScheduleDeleteResponse
import com.teamani.moamoa.schedule.dto.ScheduleDetailResponse
import com.teamani.moamoa.schedule.dto.ScheduleListResponse
import com.teamani.moamoa.schedule.dto.ScheduleSummaryResponse
import com.teamani.moamoa.schedule.dto.ScheduleUpdateRequest
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.service.ScheduleService
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional

class ScheduleControllerTests : BehaviorSpec({

    val scheduleService: ScheduleService = mock(ScheduleService::class.java)
    val scheduleController = ScheduleController(scheduleService)
    val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(scheduleController)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    // 요청 일시는 역직렬화 시 오프셋이 바뀌어도 equals가 유지되도록 UTC(Z)로 작성
    // 요청 시작 일시는 과거 검증을 통과하도록 미래 날짜로 작성
    Given("유효한 일정 생성 요청(POST /v1/meetings/{meetingId}/schedules)이 주어졌을 때") {
        val meetingId = 1L
        val requestJson = """
            {
              "title": "10월 정기 러닝",
              "description": "반포 한강공원 집합",
              "startAt": "2099-10-10T22:00:00Z",
              "endAt": "2099-10-11T00:00:00Z",
              "location": "반포 한강공원"
            }
        """.trimIndent()
        val request = ScheduleCreateRequest(
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startAt = OffsetDateTime.parse("2099-10-10T22:00:00Z"),
            endAt = OffsetDateTime.parse("2099-10-11T00:00:00Z"),
            location = "반포 한강공원"
        )
        val mockResponse = ScheduleCreateResponse(
            scheduleId = 20L,
            title = "10월 정기 러닝",
            startAt = OffsetDateTime.parse("2099-10-11T07:00:00+09:00")
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

    Given("필수값이 누락되었거나 일시가 잘못된 일정 생성 요청이 주어졌을 때") {
        val meetingId = 1L
        val invalidRequests = mapOf(
            "title 누락" to """{ "startAt": "2099-10-10T22:00:00Z" }""",
            "공백 title" to """{ "title": "   ", "startAt": "2099-10-10T22:00:00Z" }""",
            "startAt 누락" to """{ "title": "10월 정기 러닝" }""",
            "과거 startAt" to """{ "title": "10월 정기 러닝", "startAt": "2020-10-10T22:00:00Z" }""",
            "50자 초과 title" to """{ "title": "${"가".repeat(51)}", "startAt": "2099-10-10T22:00:00Z" }"""
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

    Given("종료 일시가 시작 일시보다 이전인 일정 생성 요청이 주어졌을 때") {
        val request = ScheduleCreateRequest(
            title = "10월 정기 러닝",
            startAt = OffsetDateTime.parse("2099-10-10T22:00:00Z"),
            endAt = OffsetDateTime.parse("2099-10-10T21:00:00Z")
        )

        `when`(scheduleService.createSchedule(1L, 1L, request))
            .thenThrow(ApiException(ErrorCode.VALIDATION_ERROR, "종료 일시는 시작 일시 이후여야 합니다."))

        When("생성 API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/1/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "title": "10월 정기 러닝", "startAt": "2099-10-10T22:00:00Z", "endAt": "2099-10-10T21:00:00Z" }""")
            )

            Then("400 Bad Request와 VALIDATION_ERROR 공통 에러 응답을 반환한다") {
                resultActions
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            }
        }
    }

    Given("유효한 일정 목록 조회 요청(GET /v1/meetings/{meetingId}/schedules)이 주어졌을 때") {
        val meetingId = 1L
        val from = LocalDate.parse("2026-10-01")
        val to = LocalDate.parse("2026-10-31")
        val mockResponse = ScheduleListResponse(
            schedules = listOf(
                ScheduleSummaryResponse(
                    scheduleId = 20L,
                    title = "10월 정기 러닝",
                    startAt = OffsetDateTime.parse("2026-10-11T07:00:00+09:00"),
                    endAt = OffsetDateTime.parse("2026-10-11T09:00:00+09:00"),
                    location = "반포 한강공원",
                    status = ScheduleStatus.VOTING
                )
            )
        )

        `when`(scheduleService.getSchedules(meetingId, 1L, from, to)).thenReturn(mockResponse)

        When("API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/schedules")
                    .param("from", "2026-10-01")
                    .param("to", "2026-10-31")
            )

            Then("200 OK 응답과 공통 Envelope 포맷으로 일정 목록이 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.schedules[0].scheduleId").value(20L))
                    .andExpect(jsonPath("$.data.schedules[0].title").value("10월 정기 러닝"))
                    .andExpect(jsonPath("$.data.schedules[0].startAt").exists())
                    .andExpect(jsonPath("$.data.schedules[0].endAt").exists())
                    .andExpect(jsonPath("$.data.schedules[0].location").value("반포 한강공원"))
                    .andExpect(jsonPath("$.data.schedules[0].status").value("VOTING"))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("from 또는 to가 누락되었거나 형식이 잘못된 일정 목록 조회 요청이 주어졌을 때") {
        val meetingId = 1L
        val invalidQueries = mapOf(
            "from 누락" to mapOf("to" to "2026-10-31"),
            "to 누락" to mapOf("from" to "2026-10-01"),
            "from 형식 오류" to mapOf("from" to "2026/10/01", "to" to "2026-10-31"),
            "to 형식 오류" to mapOf("from" to "2026-10-01", "to" to "20261031")
        )

        invalidQueries.forEach { (case, params) ->
            When("$case 요청으로 목록 API를 호출하면") {
                val request = get("/v1/meetings/$meetingId/schedules")
                params.forEach { (name, value) -> request.param(name, value) }
                val resultActions = mockMvc.perform(request)

                Then("400 Bad Request를 반환한다") {
                    resultActions.andExpect(status().isBadRequest)
                }
            }
        }
    }

    Given("from이 to보다 이후인 일정 목록 조회 요청이 주어졌을 때") {
        val meetingId = 1L
        val from = LocalDate.parse("2026-10-31")
        val to = LocalDate.parse("2026-10-01")

        `when`(scheduleService.getSchedules(meetingId, 1L, from, to))
            .thenThrow(ApiException(ErrorCode.VALIDATION_ERROR, "조회 시작일은 종료일보다 이후일 수 없습니다."))

        When("목록 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/schedules")
                    .param("from", "2026-10-31")
                    .param("to", "2026-10-01")
            )

            Then("400 Bad Request와 VALIDATION_ERROR 공통 에러 응답을 반환한다") {
                resultActions
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            }
        }
    }

    Given("유효한 일정 상세 조회 요청(GET /v1/meetings/{meetingId}/schedules/{scheduleId})이 주어졌을 때") {
        val meetingId = 1L
        val scheduleId = 20L
        val mockResponse = ScheduleDetailResponse(
            scheduleId = scheduleId,
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startAt = OffsetDateTime.parse("2026-10-11T07:00:00+09:00"),
            endAt = OffsetDateTime.parse("2026-10-11T09:00:00+09:00"),
            location = "반포 한강공원",
            status = ScheduleStatus.SCHEDULED
        )

        `when`(scheduleService.getSchedule(meetingId, 1L, scheduleId)).thenReturn(mockResponse)

        When("API를 호출하면") {
            val resultActions = mockMvc.perform(get("/v1/meetings/$meetingId/schedules/$scheduleId"))

            Then("200 OK 응답과 공통 Envelope 포맷으로 일정 상세가 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.scheduleId").value(20L))
                    .andExpect(jsonPath("$.data.title").value("10월 정기 러닝"))
                    .andExpect(jsonPath("$.data.description").value("반포 한강공원 집합"))
                    .andExpect(jsonPath("$.data.startAt").exists())
                    .andExpect(jsonPath("$.data.endAt").exists())
                    .andExpect(jsonPath("$.data.location").value("반포 한강공원"))
                    .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("존재하지 않는 일정의 상세 조회 요청이 주어졌을 때") {
        val meetingId = 1L
        val scheduleId = 999L

        `when`(scheduleService.getSchedule(meetingId, 1L, scheduleId))
            .thenThrow(ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다."))

        When("상세 API를 호출하면") {
            val resultActions = mockMvc.perform(get("/v1/meetings/$meetingId/schedules/$scheduleId"))

            Then("404 Not Found와 NOT_FOUND 공통 에러 응답을 반환한다") {
                resultActions
                    .andExpect(status().isNotFound)
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            }
        }
    }

    Given("scheduleId가 숫자가 아닌 상세 조회 요청이 주어졌을 때") {
        When("상세 API를 호출하면") {
            val resultActions = mockMvc.perform(get("/v1/meetings/1/schedules/abc"))

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }
    }

    Given("유효한 일정 수정 요청(PATCH /v1/meetings/{meetingId}/schedules/{scheduleId})이 주어졌을 때") {
        val meetingId = 1L
        val scheduleId = 20L
        val requestJson = """{ "startAt": "2099-10-10T23:00:00Z", "endAt": "2099-10-11T01:00:00Z" }"""
        val request = ScheduleUpdateRequest(
            startAt = Optional.of(OffsetDateTime.parse("2099-10-10T23:00:00Z")),
            endAt = Optional.of(OffsetDateTime.parse("2099-10-11T01:00:00Z"))
        )
        val mockResponse = ScheduleDetailResponse(
            scheduleId = scheduleId,
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startAt = OffsetDateTime.parse("2099-10-11T08:00:00+09:00"),
            endAt = OffsetDateTime.parse("2099-10-11T10:00:00+09:00"),
            location = "반포 한강공원",
            status = ScheduleStatus.SCHEDULED
        )

        `when`(scheduleService.updateSchedule(meetingId, 1L, scheduleId, request)).thenReturn(mockResponse)

        When("API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/$meetingId/schedules/$scheduleId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson)
            )

            Then("200 OK 응답과 공통 Envelope 포맷으로 수정된 전체 일정이 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.scheduleId").value(20L))
                    .andExpect(jsonPath("$.data.title").value("10월 정기 러닝"))
                    .andExpect(jsonPath("$.data.description").value("반포 한강공원 집합"))
                    .andExpect(jsonPath("$.data.startAt").exists())
                    .andExpect(jsonPath("$.data.endAt").exists())
                    .andExpect(jsonPath("$.data.location").value("반포 한강공원"))
                    .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("필수 항목을 null로 보낸 일정 수정 요청이 주어졌을 때") {
        val requests = mapOf(
            "title" to """{ "title": null }""",
            "startAt" to """{ "startAt": null }"""
        )

        requests.forEach { (field, requestJson) ->
            When("$field 를 null로 보내면") {
                val resultActions = mockMvc.perform(
                    patch("/v1/meetings/1/schedules/20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                )

                Then("오류 상세의 필드 이름은 요청 필드 이름과 같다") {
                    resultActions
                        .andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.error.details[0].field").value(field))
                }
            }
        }
    }

    // 시작 일시의 과거 여부는 기존 값과 비교해야 하므로 Service에서 검증 (ScheduleServiceTests)
    Given("제목·장소가 잘못되었거나 필수 항목을 null로 보낸 일정 수정 요청이 주어졌을 때") {
        val invalidRequests = mapOf(
            "공백 title" to """{ "title": "   " }""",
            "빈 문자열 title" to """{ "title": "" }""",
            "50자 초과 title" to """{ "title": "${"가".repeat(51)}" }""",
            "255자 초과 location" to """{ "location": "${"가".repeat(256)}" }""",
            "null title" to """{ "title": null }""",
            "null startAt" to """{ "startAt": null }"""
        )

        invalidRequests.forEach { (case, requestJson) ->
            When("$case 요청으로 수정 API를 호출하면") {
                val resultActions = mockMvc.perform(
                    patch("/v1/meetings/1/schedules/20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                )

                Then("400 Bad Request와 VALIDATION_ERROR를 반환한다") {
                    resultActions
                        .andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                }
            }
        }
    }

    Given("수정 결과 종료 일시가 시작 일시보다 이전이 되는 일정 수정 요청이 주어졌을 때") {
        val request = ScheduleUpdateRequest(endAt = Optional.of(OffsetDateTime.parse("2099-10-10T20:00:00Z")))

        `when`(scheduleService.updateSchedule(1L, 1L, 20L, request))
            .thenThrow(ApiException(ErrorCode.VALIDATION_ERROR, "종료 일시는 시작 일시 이후여야 합니다."))

        When("수정 API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/1/schedules/20")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "endAt": "2099-10-10T20:00:00Z" }""")
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }
    }

    Given("종료 일시만 null로 보내는 일정 수정 요청이 주어졌을 때") {
        // null 전송은 Optional.empty, 미전송 필드는 null로 받아야 Service가 비우기/유지를 구분할 수 있음
        val request = ScheduleUpdateRequest(endAt = Optional.empty())
        val mockResponse = ScheduleDetailResponse(
            scheduleId = 20L,
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startAt = OffsetDateTime.parse("2099-10-11T07:00:00+09:00"),
            endAt = null,
            location = "반포 한강공원",
            status = ScheduleStatus.SCHEDULED
        )

        `when`(scheduleService.updateSchedule(1L, 1L, 20L, request)).thenReturn(mockResponse)

        When("수정 API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/1/schedules/20")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "endAt": null }""")
            )

            Then("200 OK 응답과 종료 일시가 비워진 일정이 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.endAt").doesNotExist())
                    .andExpect(jsonPath("$.data.location").value("반포 한강공원"))
            }
        }
    }

    Given("존재하지 않는 일정의 수정 요청이 주어졌을 때") {
        val request = ScheduleUpdateRequest(title = Optional.of("변경된 제목"))

        `when`(scheduleService.updateSchedule(1L, 1L, 999L, request))
            .thenThrow(ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다."))

        When("수정 API를 호출하면") {
            val resultActions = mockMvc.perform(
                patch("/v1/meetings/1/schedules/999")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "title": "변경된 제목" }""")
            )

            Then("404 Not Found를 반환한다") {
                resultActions.andExpect(status().isNotFound)
            }
        }
    }

    Given("유효한 일정 삭제 요청(DELETE /v1/meetings/{meetingId}/schedules/{scheduleId})이 주어졌을 때") {
        val meetingId = 1L
        val scheduleId = 20L

        `when`(scheduleService.deleteSchedule(meetingId, 1L, scheduleId))
            .thenReturn(ScheduleDeleteResponse(scheduleId = scheduleId))

        When("API를 호출하면") {
            val resultActions = mockMvc.perform(delete("/v1/meetings/$meetingId/schedules/$scheduleId"))

            Then("200 OK 응답과 공통 Envelope 포맷으로 삭제된 일정 ID가 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.scheduleId").value(20L))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("존재하지 않는 일정의 삭제 요청이 주어졌을 때") {
        `when`(scheduleService.deleteSchedule(1L, 1L, 999L))
            .thenThrow(ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다."))

        When("삭제 API를 호출하면") {
            val resultActions = mockMvc.perform(delete("/v1/meetings/1/schedules/999"))

            Then("404 Not Found를 반환한다") {
                resultActions.andExpect(status().isNotFound)
            }
        }
    }
})

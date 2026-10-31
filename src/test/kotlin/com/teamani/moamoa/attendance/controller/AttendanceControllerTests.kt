package com.teamani.moamoa.attendance.controller

import com.teamani.moamoa.attendance.dto.AttendanceListResponse
import com.teamani.moamoa.attendance.dto.AttendanceMemberResponse
import com.teamani.moamoa.attendance.dto.AttendanceUpdateItemRequest
import com.teamani.moamoa.attendance.dto.AttendanceUpdateItemResponse
import com.teamani.moamoa.attendance.dto.AttendanceUpdateRequest
import com.teamani.moamoa.attendance.dto.AttendanceUpdateResponse
import com.teamani.moamoa.attendance.dto.AttendanceViewStatus
import com.teamani.moamoa.attendance.entity.AttendanceStatus
import com.teamani.moamoa.attendance.service.AttendanceService
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

@WebMvcTest(controllers = [AttendanceController::class])
class AttendanceControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var attendanceService: AttendanceService

    init {
        Given("출석 처리 요청(PATCH /v1/meetings/{meetingId}/schedules/{scheduleId}/attendances)이 주어졌을 때") {
            When("유효한 요청이면") {
                Then("200과 공통 응답 형식으로 보낸 항목을 그대로 반환한다") {
                    `when`(
                        attendanceService.updateAttendances(
                            1L, USER_ID, 5L, updateRequest(200L to "LATE", 300L to "ABSENT")
                        )
                    ).thenReturn(
                        AttendanceUpdateResponse(
                            scheduleId = 5L,
                            attendances = listOf(
                                AttendanceUpdateItemResponse(200L, AttendanceStatus.LATE),
                                AttendanceUpdateItemResponse(300L, AttendanceStatus.ABSENT),
                            )
                        )
                    )

                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"LATE"},{"memberId":300,"status":"ABSENT"}]}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.scheduleId") { value(5) }
                        jsonPath("$.data.attendances.length()") { value(2) }
                        jsonPath("$.data.attendances[0].memberId") { value(200) }
                        jsonPath("$.data.attendances[0].status") { value("LATE") }
                        jsonPath("$.data.attendances[1].memberId") { value(300) }
                        jsonPath("$.data.attendances[1].status") { value("ABSENT") }
                        jsonPath("$.error") { value(null as Any?) }
                        jsonPath("$.timestamp") { exists() }
                    }
                }
            }
            When("attendances가 없으면") {
                Then("400 VALIDATION_ERROR와 attendances 필드 details를 응답한다") {
                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("attendances") }
                    }
                }
            }
            When("attendances가 빈 배열이면") {
                Then("400 VALIDATION_ERROR와 attendances 필드 details를 응답한다") {
                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("attendances") }
                    }
                }
            }
            When("attendances에 null 원소가 있으면") {
                Then("JSON_PARSE_ERROR가 아닌 400 VALIDATION_ERROR와 원소 위치 details를 응답한다") {
                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"PRESENT"},null]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("attendances[1]") }
                    }
                }
            }
            When("memberId가 없으면") {
                Then("400 VALIDATION_ERROR와 memberId 필드 details를 응답한다") {
                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"status":"PRESENT"}]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("attendances[0].memberId") }
                    }
                }
            }
            When("status가 없으면") {
                Then("400 VALIDATION_ERROR와 status 필드 details를 응답한다") {
                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200}]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("attendances[0].status") }
                    }
                }
            }
            When("status가 조회 응답 전용 값인 UNCHECKED이면") {
                Then("JSON_PARSE_ERROR가 아닌 400 VALIDATION_ERROR와 status 필드 details를 응답한다") {
                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"UNCHECKED"}]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("attendances[0].status") }
                        jsonPath("$.error.details[0].rejectedValue") { value("UNCHECKED") }
                    }
                }
            }
            When("status가 허용 값이 아니면(소문자·오타·빈 문자열)") {
                Then("400 VALIDATION_ERROR와 status 필드 details를 응답한다") {
                    listOf("present", "ATTEND", "").forEach { status ->
                        mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"attendances":[{"memberId":200,"status":"$status"}]}"""
                        }.andExpect {
                            expectError(400, "VALIDATION_ERROR")
                            jsonPath("$.error.details[0].field") { value("attendances[0].status") }
                        }
                    }
                }
            }
            When("같은 memberId가 두 번 있으면") {
                Then("400 VALIDATION_ERROR를 응답한다") {
                    `when`(
                        attendanceService.updateAttendances(
                            1L, USER_ID, 5L, updateRequest(200L to "PRESENT", 200L to "LATE")
                        )
                    ).thenThrow(ApiException(ErrorCode.VALIDATION_ERROR, "같은 구성원을 중복으로 처리할 수 없습니다."))

                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"PRESENT"},{"memberId":200,"status":"LATE"}]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.message") { value("같은 구성원을 중복으로 처리할 수 없습니다.") }
                    }
                }
            }
            When("ADMIN이 아니면") {
                Then("403 FORBIDDEN을 응답한다") {
                    `when`(attendanceService.updateAttendances(2L, USER_ID, 5L, updateRequest(200L to "PRESENT")))
                        .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 관리자만 요청할 수 있습니다."))

                    mockMvc.patch("/v1/meetings/2/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"PRESENT"}]}"""
                    }.andExpect {
                        expectError(403, "FORBIDDEN")
                    }
                }
            }
            When("모임이나 일정이 없으면") {
                Then("404 NOT_FOUND를 응답한다") {
                    `when`(attendanceService.updateAttendances(1L, USER_ID, 6L, updateRequest(200L to "PRESENT")))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다."))

                    mockMvc.patch("/v1/meetings/1/schedules/6/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"PRESENT"}]}"""
                    }.andExpect {
                        expectError(404, "NOT_FOUND")
                    }
                }
            }
            When("모임 구성원이 아닌 memberId가 포함되어 있으면") {
                Then("404 NOT_FOUND를 응답한다") {
                    `when`(attendanceService.updateAttendances(1L, USER_ID, 5L, updateRequest(900L to "PRESENT")))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "모임 구성원을 찾을 수 없습니다."))

                    mockMvc.patch("/v1/meetings/1/schedules/5/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":900,"status":"PRESENT"}]}"""
                    }.andExpect {
                        expectError(404, "NOT_FOUND")
                        jsonPath("$.error.message") { value("모임 구성원을 찾을 수 없습니다.") }
                    }
                }
            }
            When("취소되었거나 투표 중인 일정이면") {
                Then("422 UNPROCESSABLE_ENTITY를 응답한다") {
                    `when`(attendanceService.updateAttendances(1L, USER_ID, 7L, updateRequest(200L to "PRESENT")))
                        .thenThrow(
                            ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "취소되었거나 투표 중인 일정은 출석을 처리할 수 없습니다.")
                        )

                    mockMvc.patch("/v1/meetings/1/schedules/7/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"PRESENT"}]}"""
                    }.andExpect {
                        expectError(422, "UNPROCESSABLE_ENTITY")
                    }
                }
            }
            When("같은 구성원의 첫 출석 저장이 동시에 들어와 충돌하면") {
                Then("409 CONFLICT를 응답한다") {
                    `when`(attendanceService.updateAttendances(1L, USER_ID, 8L, updateRequest(200L to "PRESENT")))
                        .thenThrow(ApiException(ErrorCode.CONFLICT, "출석 처리 중 충돌이 발생했습니다. 다시 시도해 주세요."))

                    mockMvc.patch("/v1/meetings/1/schedules/8/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":200,"status":"PRESENT"}]}"""
                    }.andExpect {
                        expectError(409, "CONFLICT")
                    }
                }
            }
        }

        Given("출석 현황 조회 요청(GET /v1/meetings/{meetingId}/schedules/{scheduleId}/attendances)이 주어졌을 때") {
            When("모임 구성원이 요청하면") {
                Then("200과 구성원 전체의 출석 상태를 반환하고, 기록이 없는 구성원은 UNCHECKED, nickname은 null로 응답한다") {
                    `when`(attendanceService.getAttendances(1L, USER_ID, 5L)).thenReturn(
                        AttendanceListResponse(
                            scheduleId = 5L,
                            attendances = listOf(
                                AttendanceMemberResponse(100L, null, AttendanceViewStatus.PRESENT),
                                AttendanceMemberResponse(200L, null, AttendanceViewStatus.LATE),
                                AttendanceMemberResponse(300L, null, AttendanceViewStatus.UNCHECKED),
                            )
                        )
                    )

                    mockMvc.get("/v1/meetings/1/schedules/5/attendances").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.scheduleId") { value(5) }
                        jsonPath("$.data.attendances[0].memberId") { value(100) }
                        jsonPath("$.data.attendances[0].nickname") { value(null as Any?) }
                        jsonPath("$.data.attendances[0].status") { value("PRESENT") }
                        jsonPath("$.data.attendances[1].status") { value("LATE") }
                        jsonPath("$.data.attendances[2].memberId") { value(300) }
                        jsonPath("$.data.attendances[2].status") { value("UNCHECKED") }
                    }
                }
            }
            When("모임 구성원이 아니면") {
                Then("403 FORBIDDEN을 응답한다") {
                    `when`(attendanceService.getAttendances(2L, USER_ID, 5L))
                        .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 구성원만 요청할 수 있습니다."))

                    mockMvc.get("/v1/meetings/2/schedules/5/attendances").andExpect {
                        expectError(403, "FORBIDDEN")
                    }
                }
            }
            When("모임이나 일정이 없으면") {
                Then("404 NOT_FOUND를 응답한다") {
                    `when`(attendanceService.getAttendances(1L, USER_ID, 6L))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다."))

                    mockMvc.get("/v1/meetings/1/schedules/6/attendances").andExpect {
                        expectError(404, "NOT_FOUND")
                    }
                }
            }
        }
    }

    private fun updateRequest(vararg items: Pair<Long, String>) =
        AttendanceUpdateRequest(items.map { (memberId, status) -> AttendanceUpdateItemRequest(memberId, status) })

    private fun MockMvcResultMatchersDsl.expectError(status: Int, code: String) {
        status { isEqualTo(status) }
        jsonPath("$.success") { value(false) }
        jsonPath("$.data") { value(null as Any?) }
        jsonPath("$.error.code") { value(code) }
    }

    companion object {
        // 인증 구현 전 Controller의 임시 사용자 ID
        private const val USER_ID = 1L
    }
}

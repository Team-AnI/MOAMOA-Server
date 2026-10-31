package com.teamani.moamoa.vote.controller

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.vote.dto.MyVoteSubmitRequest
import com.teamani.moamoa.vote.dto.MyVoteSubmitResponse
import com.teamani.moamoa.vote.dto.VoteCandidateResponse
import com.teamani.moamoa.vote.dto.VoteConfirmRequest
import com.teamani.moamoa.vote.dto.VoteConfirmResponse
import com.teamani.moamoa.vote.dto.VoteCreateRequest
import com.teamani.moamoa.vote.dto.VoteCreateResponse
import com.teamani.moamoa.vote.dto.VoteDetailResponse
import com.teamani.moamoa.vote.dto.VoteMemberResponse
import com.teamani.moamoa.vote.dto.VoteParticipationResponse
import com.teamani.moamoa.vote.service.VoteService
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
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@WebMvcTest(controllers = [VoteController::class])
class VoteControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var voteService: VoteService

    init {
        // 요청 일시는 역직렬화 시 UTC로 맞춰져도 equals가 유지되도록 Z 표기로 작성
        val deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).truncatedTo(ChronoUnit.SECONDS)
        val firstTime = OffsetDateTime.parse("2026-11-01T10:00:00Z")
        val secondTime = OffsetDateTime.parse("2026-11-02T10:00:00Z")
        val firstTimeKst = OffsetDateTime.parse("2026-11-01T19:00:00+09:00")
        val secondTimeKst = OffsetDateTime.parse("2026-11-02T19:00:00+09:00")
        val submittedAt = OffsetDateTime.parse("2026-10-24T10:00:00+09:00")

        Given("투표 생성 요청(POST /v1/meetings/{meetingId}/schedule-votes)이 주어졌을 때") {
            When("유효한 요청이면") {
                Then("201과 공통 응답 형식으로 투표 정보를 +09:00 일시로 반환한다") {
                    `when`(voteService.createVote(1L, USER_ID, createRequest(deadline, listOf(secondTime, firstTime)))).thenReturn(
                        VoteCreateResponse(
                            scheduleId = 5L,
                            title = "11월 정기 모임",
                            voteDeadlineAt = OffsetDateTime.parse("2026-10-30T21:00:00+09:00"),
                            meetingTimes = listOf(firstTimeKst, secondTimeKst)
                        )
                    )

                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"11월 정기 모임\"", "\"$deadline\"", "[\"$secondTime\",\"$firstTime\"]")
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.scheduleId") { value(5) }
                        jsonPath("$.data.title") { value("11월 정기 모임") }
                        jsonPath("$.data.voteDeadlineAt") { value("2026-10-30T21:00:00+09:00") }
                        jsonPath("$.data.meetingTimes[0]") { value("2026-11-01T19:00:00+09:00") }
                        jsonPath("$.data.meetingTimes[1]") { value("2026-11-02T19:00:00+09:00") }
                        jsonPath("$.error") { value(null as Any?) }
                    }
                }
            }
            When("title이 없으면") {
                Then("400 VALIDATION_ERROR와 title 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"voteDeadlineAt":"$deadline","meetingTimes":["$firstTime"]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("title") }
                    }
                }
            }
            When("title이 공백이면") {
                Then("400 VALIDATION_ERROR와 title 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"   \"", "\"$deadline\"", "[\"$firstTime\"]")
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("title") }
                    }
                }
            }
            When("title이 50자를 넘으면") {
                Then("400 VALIDATION_ERROR와 title 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"${"가".repeat(51)}\"", "\"$deadline\"", "[\"$firstTime\"]")
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("title") }
                    }
                }
            }
            When("voteDeadlineAt이 없으면") {
                Then("400 VALIDATION_ERROR와 voteDeadlineAt 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"title":"11월 정기 모임","meetingTimes":["$firstTime"]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("voteDeadlineAt") }
                    }
                }
            }
            When("voteDeadlineAt이 현재 이전이면") {
                Then("400 VALIDATION_ERROR와 voteDeadlineAt 필드 details를 응답한다") {
                    val pastDeadline = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.SECONDS)

                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"11월 정기 모임\"", "\"$pastDeadline\"", "[\"$firstTime\"]")
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("voteDeadlineAt") }
                    }
                }
            }
            When("meetingTimes가 없으면") {
                Then("400 VALIDATION_ERROR와 meetingTimes 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"title":"11월 정기 모임","voteDeadlineAt":"$deadline"}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("meetingTimes") }
                    }
                }
            }
            When("meetingTimes가 빈 배열이면") {
                Then("400 VALIDATION_ERROR와 meetingTimes 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"11월 정기 모임\"", "\"$deadline\"", "[]")
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("meetingTimes") }
                    }
                }
            }
            When("meetingTimes에 null이 있으면") {
                Then("400 VALIDATION_ERROR와 해당 원소 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"11월 정기 모임\"", "\"$deadline\"", "[\"$firstTime\",null]")
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("meetingTimes[1]") }
                    }
                }
            }
            When("중복 후보 등 Service 검증에 실패하면") {
                Then("400 VALIDATION_ERROR와 Service 메시지를 응답한다") {
                    `when`(voteService.createVote(2L, USER_ID, createRequest(deadline, listOf(firstTime, firstTime))))
                        .thenThrow(ApiException(ErrorCode.VALIDATION_ERROR, "후보 시간은 중복될 수 없습니다."))

                    mockMvc.post("/v1/meetings/2/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"11월 정기 모임\"", "\"$deadline\"", "[\"$firstTime\",\"$firstTime\"]")
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.message") { value("후보 시간은 중복될 수 없습니다.") }
                    }
                }
            }
            When("ADMIN이 아니면") {
                Then("403 FORBIDDEN을 응답한다") {
                    `when`(voteService.createVote(3L, USER_ID, createRequest(deadline, listOf(firstTime))))
                        .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 관리자만 요청할 수 있습니다."))

                    mockMvc.post("/v1/meetings/3/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = createBody("\"11월 정기 모임\"", "\"$deadline\"", "[\"$firstTime\"]")
                    }.andExpect {
                        expectError(403, "FORBIDDEN")
                    }
                }
            }
        }

        Given("투표 현황 조회 요청(GET /v1/meetings/{meetingId}/schedules/{scheduleId}/candidates)이 주어졌을 때") {
            When("모임 구성원이 요청하면") {
                Then("200과 투표 현황, 후보별 인원과 내 선택을 반환한다") {
                    `when`(voteService.getVote(1L, USER_ID, 5L)).thenReturn(
                        VoteDetailResponse(
                            scheduleId = 5L,
                            title = "11월 정기 모임",
                            status = ScheduleStatus.VOTING,
                            voteDeadlineAt = OffsetDateTime.parse("2026-10-30T21:00:00+09:00"),
                            votingOpen = true,
                            memberCount = 4L,
                            respondedCount = 2L,
                            myResponded = true,
                            candidates = listOf(
                                VoteCandidateResponse(firstTimeKst, 2L, true),
                                VoteCandidateResponse(secondTimeKst, 0L, false),
                            )
                        )
                    )

                    mockMvc.get("/v1/meetings/1/schedules/5/candidates").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.scheduleId") { value(5) }
                        jsonPath("$.data.status") { value("VOTING") }
                        jsonPath("$.data.voteDeadlineAt") { value("2026-10-30T21:00:00+09:00") }
                        jsonPath("$.data.votingOpen") { value(true) }
                        jsonPath("$.data.memberCount") { value(4) }
                        jsonPath("$.data.respondedCount") { value(2) }
                        jsonPath("$.data.myResponded") { value(true) }
                        jsonPath("$.data.candidates[0].meetingTime") { value("2026-11-01T19:00:00+09:00") }
                        jsonPath("$.data.candidates[0].availableCount") { value(2) }
                        jsonPath("$.data.candidates[0].selectedByMe") { value(true) }
                        jsonPath("$.data.candidates[1].availableCount") { value(0) }
                    }
                }
            }
            When("투표가 없는 일정이면") {
                Then("404 NOT_FOUND를 응답한다") {
                    `when`(voteService.getVote(1L, USER_ID, 6L))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "투표를 찾을 수 없습니다."))

                    mockMvc.get("/v1/meetings/1/schedules/6/candidates").andExpect {
                        expectError(404, "NOT_FOUND")
                    }
                }
            }
        }

        Given("내 투표 제출 요청(PUT /v1/meetings/{meetingId}/schedules/{scheduleId}/votes/me)이 주어졌을 때") {
            When("후보 시간을 선택해 제출하면") {
                Then("200과 선택한 시간, 마지막 제출 시각을 반환한다") {
                    `when`(voteService.submitMyVote(1L, USER_ID, 5L, MyVoteSubmitRequest(listOf(firstTime))))
                        .thenReturn(MyVoteSubmitResponse(5L, listOf(firstTimeKst), submittedAt))

                    mockMvc.put("/v1/meetings/1/schedules/5/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":["$firstTime"]}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.scheduleId") { value(5) }
                        jsonPath("$.data.meetingTimes[0]") { value("2026-11-01T19:00:00+09:00") }
                        jsonPath("$.data.submittedAt") { value("2026-10-24T10:00:00+09:00") }
                    }
                }
            }
            When("빈 배열을 제출하면") {
                Then("가능한 시간 없음으로 200을 반환한다") {
                    `when`(voteService.submitMyVote(1L, USER_ID, 7L, MyVoteSubmitRequest(emptyList())))
                        .thenReturn(MyVoteSubmitResponse(7L, emptyList(), submittedAt))

                    mockMvc.put("/v1/meetings/1/schedules/7/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":[]}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.meetingTimes.length()") { value(0) }
                    }
                }
            }
            When("meetingTimes가 없으면") {
                Then("400 VALIDATION_ERROR와 meetingTimes 필드 details를 응답한다") {
                    mockMvc.put("/v1/meetings/1/schedules/5/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("meetingTimes") }
                    }
                }
            }
            When("meetingTimes에 null이 있으면") {
                Then("400 VALIDATION_ERROR와 해당 원소 필드 details를 응답한다") {
                    mockMvc.put("/v1/meetings/1/schedules/5/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":[null]}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("meetingTimes[0]") }
                    }
                }
            }
            When("마감되었거나 진행 중이 아닌 투표이면") {
                Then("422 UNPROCESSABLE_ENTITY를 응답한다") {
                    `when`(voteService.submitMyVote(1L, USER_ID, 8L, MyVoteSubmitRequest(listOf(firstTime))))
                        .thenThrow(ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "투표가 마감되었습니다."))

                    mockMvc.put("/v1/meetings/1/schedules/8/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":["$firstTime"]}"""
                    }.andExpect {
                        expectError(422, "UNPROCESSABLE_ENTITY")
                        jsonPath("$.error.message") { value("투표가 마감되었습니다.") }
                    }
                }
            }
            When("같은 구성원의 동시 제출과 충돌하면") {
                Then("409 CONFLICT를 응답한다") {
                    `when`(voteService.submitMyVote(1L, USER_ID, 9L, MyVoteSubmitRequest(listOf(firstTime))))
                        .thenThrow(ApiException(ErrorCode.CONFLICT, "투표 제출 중 충돌이 발생했습니다. 다시 시도해 주세요."))

                    mockMvc.put("/v1/meetings/1/schedules/9/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":["$firstTime"]}"""
                    }.andExpect {
                        expectError(409, "CONFLICT")
                        jsonPath("$.error.message") { value("투표 제출 중 충돌이 발생했습니다. 다시 시도해 주세요.") }
                    }
                }
            }
        }

        Given("투표 참여 현황 조회 요청(GET /v1/meetings/{meetingId}/schedules/{scheduleId}/vote-responses)이 주어졌을 때") {
            When("ADMIN이 요청하면") {
                Then("200과 구성원별 응답 여부를 반환하고 nickname과 미응답자의 submittedAt은 null로 응답한다") {
                    `when`(voteService.getVoteParticipation(1L, USER_ID, 5L)).thenReturn(
                        VoteParticipationResponse(
                            memberCount = 2L,
                            respondedCount = 1L,
                            members = listOf(
                                VoteMemberResponse(100L, null, false, null),
                                VoteMemberResponse(200L, null, true, submittedAt),
                            )
                        )
                    )

                    mockMvc.get("/v1/meetings/1/schedules/5/vote-responses").andExpect {
                        status { isOk() }
                        jsonPath("$.data.memberCount") { value(2) }
                        jsonPath("$.data.respondedCount") { value(1) }
                        jsonPath("$.data.members[0].memberId") { value(100) }
                        jsonPath("$.data.members[0].nickname") { value(null as Any?) }
                        jsonPath("$.data.members[0].responded") { value(false) }
                        jsonPath("$.data.members[0].submittedAt") { value(null as Any?) }
                        jsonPath("$.data.members[1].responded") { value(true) }
                        jsonPath("$.data.members[1].submittedAt") { value("2026-10-24T10:00:00+09:00") }
                    }
                }
            }
            When("ADMIN이 아니면") {
                Then("403 FORBIDDEN을 응답한다") {
                    `when`(voteService.getVoteParticipation(1L, USER_ID, 6L))
                        .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 관리자만 요청할 수 있습니다."))

                    mockMvc.get("/v1/meetings/1/schedules/6/vote-responses").andExpect {
                        expectError(403, "FORBIDDEN")
                    }
                }
            }
        }

        Given("일정 확정 요청(POST /v1/meetings/{meetingId}/schedules/{scheduleId}/confirmation)이 주어졌을 때") {
            When("ADMIN이 후보 시간으로 확정하면") {
                Then("200과 확정된 일정 정보를 반환한다") {
                    `when`(voteService.confirmVote(1L, USER_ID, 5L, VoteConfirmRequest(secondTime)))
                        .thenReturn(VoteConfirmResponse(5L, "11월 정기 모임", secondTimeKst, ScheduleStatus.CONFIRMED))

                    mockMvc.post("/v1/meetings/1/schedules/5/confirmation") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTime":"$secondTime"}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.scheduleId") { value(5) }
                        jsonPath("$.data.title") { value("11월 정기 모임") }
                        jsonPath("$.data.startAt") { value("2026-11-02T19:00:00+09:00") }
                        jsonPath("$.data.status") { value("CONFIRMED") }
                    }
                }
            }
            When("meetingTime이 없으면") {
                Then("400 VALIDATION_ERROR와 meetingTime 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings/1/schedules/5/confirmation") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("meetingTime") }
                    }
                }
            }
            When("이미 확정되었거나 취소된 일정이면") {
                Then("422 UNPROCESSABLE_ENTITY를 응답한다") {
                    `when`(voteService.confirmVote(1L, USER_ID, 6L, VoteConfirmRequest(secondTime)))
                        .thenThrow(ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "진행 중인 투표만 확정할 수 있습니다."))

                    mockMvc.post("/v1/meetings/1/schedules/6/confirmation") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTime":"$secondTime"}"""
                    }.andExpect {
                        expectError(422, "UNPROCESSABLE_ENTITY")
                    }
                }
            }
        }
    }

    private fun createRequest(voteDeadlineAt: OffsetDateTime, meetingTimes: List<OffsetDateTime>) =
        VoteCreateRequest(title = "11월 정기 모임", voteDeadlineAt = voteDeadlineAt, meetingTimes = meetingTimes)

    // 투표 생성 요청 본문 (각 값은 JSON 리터럴 그대로 전달)
    private fun createBody(title: String, voteDeadlineAt: String, meetingTimes: String) =
        """{"title":$title,"voteDeadlineAt":$voteDeadlineAt,"meetingTimes":$meetingTimes}"""

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

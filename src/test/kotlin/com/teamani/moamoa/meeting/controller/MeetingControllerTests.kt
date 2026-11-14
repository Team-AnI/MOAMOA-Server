package com.teamani.moamoa.meeting.controller

import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.dto.MeetingCreateRequest
import com.teamani.moamoa.meeting.dto.MeetingCreateResponse
import com.teamani.moamoa.meeting.dto.MeetingDeleteResponse
import com.teamani.moamoa.meeting.dto.MeetingDetailResponse
import com.teamani.moamoa.meeting.dto.MeetingInviteCodeResponse
import com.teamani.moamoa.meeting.dto.MeetingJoinRequest
import com.teamani.moamoa.meeting.dto.MeetingJoinResponse
import com.teamani.moamoa.meeting.dto.MeetingUpdateRequest
import com.teamani.moamoa.meeting.dto.MeetingUpdateResponse
import com.teamani.moamoa.meeting.dto.MyMeetingListResponse
import com.teamani.moamoa.meeting.dto.MyMeetingSummaryResponse
import com.teamani.moamoa.meeting.service.MeetingService
import com.teamani.moamoa.member.entity.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.Optional

@WebMvcTest(controllers = [MeetingController::class])
class MeetingControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var meetingService: MeetingService

    @MockitoBean
    lateinit var jwtTokenProvider: JwtTokenProvider

    init {
        beforeTest {
            `when`(jwtTokenProvider.getUserId(ACCESS_TOKEN)).thenReturn(USER_ID)
        }

        Given("모임 생성 요청(POST /v1/meetings)이 주어졌을 때") {
            When("유효한 요청이면") {
                Then("201과 공통 응답 형식으로 모임 정보와 초대코드를 반환한다") {
                    `when`(meetingService.createMeeting(USER_ID, MeetingCreateRequest(name = "모아모아", description = "모임 소개")))
                        .thenReturn(MeetingCreateResponse(1L, "모아모아", "A1B2C3D4", MemberRole.ADMIN))

                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"모아모아","description":"모임 소개"}"""
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.meetingId") { value(1) }
                        jsonPath("$.data.name") { value("모아모아") }
                        jsonPath("$.data.inviteCode") { value("A1B2C3D4") }
                        jsonPath("$.data.myRole") { value("ADMIN") }
                        jsonPath("$.error") { value(null as Any?) }
                    }
                }
            }
            When("name이 공백이면") {
                Then("400 VALIDATION_ERROR와 name 필드 details를 응답한다") {
                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"  "}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("name") }
                    }
                }
            }
            When("name이 없으면") {
                Then("400 VALIDATION_ERROR를 응답한다") {
                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"description":"모임 소개"}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                    }
                }
            }
            When("name이 20자를 넘으면") {
                Then("400 VALIDATION_ERROR를 응답한다") {
                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"${"가".repeat(21)}"}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("name") }
                    }
                }
            }
            When("초대코드 생성이 동시 요청과 충돌하면") {
                Then("409 CONFLICT를 응답한다") {
                    `when`(meetingService.createMeeting(USER_ID, MeetingCreateRequest(name = "모아모아")))
                        .thenThrow(ApiException(ErrorCode.CONFLICT, "초대코드 생성 중 충돌이 발생했습니다. 다시 시도해 주세요."))

                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"모아모아"}"""
                    }.andExpect {
                        expectError(409, "CONFLICT")
                        jsonPath("$.error.message") { value("초대코드 생성 중 충돌이 발생했습니다. 다시 시도해 주세요.") }
                    }
                }
            }
        }

        Given("내 모임 목록 조회 요청(GET /v1/me/meetings)이 주어졌을 때") {
            When("요청하면") {
                Then("200과 모임 목록을 반환한다") {
                    `when`(meetingService.getMyMeetings(USER_ID)).thenReturn(
                        MyMeetingListResponse(listOf(MyMeetingSummaryResponse(1L, "모아모아", 3L, MemberRole.MEMBER)))
                    )

                    mockMvc.get("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.meetings[0].meetingId") { value(1) }
                        jsonPath("$.data.meetings[0].memberCount") { value(3) }
                        jsonPath("$.data.meetings[0].myRole") { value("MEMBER") }
                    }
                }
            }
        }

        Given("초대코드 참여 요청(POST /v1/me/meetings)이 주어졌을 때") {
            When("유효한 초대코드이면") {
                Then("201과 참여한 모임 정보를 반환한다") {
                    `when`(meetingService.joinMeeting(USER_ID, MeetingJoinRequest(inviteCode = "A1B2C3D4")))
                        .thenReturn(MeetingJoinResponse(1L, "모아모아", MemberRole.MEMBER))

                    mockMvc.post("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"inviteCode":"A1B2C3D4"}"""
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.data.meetingId") { value(1) }
                        jsonPath("$.data.myRole") { value("MEMBER") }
                    }
                }
            }
            When("inviteCode가 공백이면") {
                Then("400 VALIDATION_ERROR를 응답한다") {
                    mockMvc.post("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"inviteCode":""}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("inviteCode") }
                    }
                }
            }
            When("inviteCode 앞뒤나 중간에 공백이 있으면") {
                Then("400 VALIDATION_ERROR를 응답한다") {
                    listOf(" A1B2C3D4", "A1B2C3D4 ", "A1B2 C3D4").forEach { inviteCode ->
                        mockMvc.post("/v1/me/meetings") {
                            header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"inviteCode":"$inviteCode"}"""
                        }.andExpect {
                            expectError(400, "VALIDATION_ERROR")
                            jsonPath("$.error.details[0].field") { value("inviteCode") }
                            jsonPath("$.error.details[0].reason") { value("초대코드에 공백을 포함할 수 없습니다.") }
                        }
                    }
                }
            }
            When("존재하지 않는 초대코드이면") {
                Then("404 NOT_FOUND를 응답한다") {
                    `when`(meetingService.joinMeeting(USER_ID, MeetingJoinRequest(inviteCode = "ZZZZZZZZ")))
                        .thenThrow(ApiException(ErrorCode.NOT_FOUND, "초대코드에 해당하는 모임을 찾을 수 없습니다."))

                    mockMvc.post("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"inviteCode":"ZZZZZZZZ"}"""
                    }.andExpect {
                        expectError(404, "NOT_FOUND")
                    }
                }
            }
            When("이미 참여한 모임이면") {
                Then("409 CONFLICT를 응답한다") {
                    `when`(meetingService.joinMeeting(USER_ID, MeetingJoinRequest(inviteCode = "JOINED01")))
                        .thenThrow(ApiException(ErrorCode.CONFLICT, "이미 참여한 모임입니다."))

                    mockMvc.post("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"inviteCode":"JOINED01"}"""
                    }.andExpect {
                        expectError(409, "CONFLICT")
                        jsonPath("$.error.message") { value("이미 참여한 모임입니다.") }
                    }
                }
            }
        }

        Given("모임 상세 조회 요청(GET /v1/meetings/{meetingId})이 주어졌을 때") {
            When("모임 구성원이면") {
                Then("200과 모임 상세 정보를 반환한다") {
                    `when`(meetingService.getMeeting(1L, USER_ID))
                        .thenReturn(MeetingDetailResponse(1L, "모아모아", null, 2L, MemberRole.ADMIN))

                    mockMvc.get("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.meetingId") { value(1) }
                        jsonPath("$.data.description") { value(null as Any?) }
                        jsonPath("$.data.memberCount") { value(2) }
                        jsonPath("$.data.myRole") { value("ADMIN") }
                    }
                }
            }
            When("모임 구성원이 아니면") {
                Then("403 FORBIDDEN을 응답한다") {
                    `when`(meetingService.getMeeting(2L, USER_ID))
                        .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 구성원만 요청할 수 있습니다."))

                    mockMvc.get("/v1/meetings/2") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        expectError(403, "FORBIDDEN")
                    }
                }
            }
            When("meetingId가 숫자가 아니면") {
                Then("400 INPUT_ERROR를 응답한다") {
                    mockMvc.get("/v1/meetings/abc") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        expectError(400, "INPUT_ERROR")
                    }
                }
            }
        }

        Given("모임 수정 요청(PATCH /v1/meetings/{meetingId})이 주어졌을 때") {
            When("name만 보내면") {
                Then("200과 수정된 모임 정보를 반환한다") {
                    `when`(meetingService.updateMeeting(1L, USER_ID, MeetingUpdateRequest(name = Optional.of("새 이름"))))
                        .thenReturn(MeetingUpdateResponse(1L, "새 이름", "모임 소개"))

                    mockMvc.patch("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"새 이름"}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.name") { value("새 이름") }
                        jsonPath("$.data.description") { value("모임 소개") }
                    }
                }
            }
            When("description에 null을 보내면") {
                Then("Optional.empty로 받아 비운 결과를 반환한다") {
                    `when`(meetingService.updateMeeting(1L, USER_ID, MeetingUpdateRequest(description = Optional.empty())))
                        .thenReturn(MeetingUpdateResponse(1L, "모아모아", null))

                    mockMvc.patch("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"description":null}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.name") { value("모아모아") }
                        jsonPath("$.data.description") { value(null as Any?) }
                    }
                }
            }
            When("description 값을 보내면") {
                Then("Optional.of로 받아 변경한 결과를 반환한다") {
                    `when`(meetingService.updateMeeting(1L, USER_ID, MeetingUpdateRequest(description = Optional.of("새 소개"))))
                        .thenReturn(MeetingUpdateResponse(1L, "모아모아", "새 소개"))

                    mockMvc.patch("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"description":"새 소개"}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.description") { value("새 소개") }
                    }
                }
            }
            When("name에 null을 보내면") {
                Then("필수 항목이므로 400 VALIDATION_ERROR를 응답한다") {
                    mockMvc.patch("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":null}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("name") }
                    }
                }
            }
            When("name을 20자 초과로 보내면") {
                Then("400 VALIDATION_ERROR를 응답한다") {
                    mockMvc.patch("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"${"가".repeat(21)}"}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("name") }
                    }
                }
            }
            When("name을 공백으로 보내면") {
                Then("400 VALIDATION_ERROR를 응답한다") {
                    mockMvc.patch("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"   "}"""
                    }.andExpect {
                        expectError(400, "VALIDATION_ERROR")
                        jsonPath("$.error.details[0].field") { value("name") }
                    }
                }
            }
        }

        Given("모임 삭제 요청(DELETE /v1/meetings/{meetingId})이 주어졌을 때") {
            When("ADMIN이 요청하면") {
                Then("200과 meetingId를 반환한다") {
                    `when`(meetingService.deleteMeeting(1L, USER_ID)).thenReturn(MeetingDeleteResponse(1L))

                    mockMvc.delete("/v1/meetings/1") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.meetingId") { value(1) }
                    }
                }
            }
        }

        Given("초대코드 조회 요청(GET /v1/meetings/{meetingId}/invite-code)이 주어졌을 때") {
            When("ADMIN이 요청하면") {
                Then("200과 초대코드를 반환한다") {
                    `when`(meetingService.getInviteCode(1L, USER_ID)).thenReturn(MeetingInviteCodeResponse("A1B2C3D4"))

                    mockMvc.get("/v1/meetings/1/invite-code") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.inviteCode") { value("A1B2C3D4") }
                    }
                }
            }
        }

        Given("인증되지 않은 모임 생성 요청이 주어졌을 때") {
            When("Authorization 헤더가 없으면") {
                Then("401 UNAUTHORIZED이고 Service를 호출하지 않는다") {
                    mockMvc.post("/v1/meetings") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"모아모아"}"""
                    }.andExpect {
                        expectError(401, "UNAUTHORIZED")
                    }

                    verifyNoInteractions(meetingService)
                }
            }
            When("Bearer 형식이 아니면") {
                Then("401 UNAUTHORIZED를 응답한다") {
                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, ACCESS_TOKEN)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"모아모아"}"""
                    }.andExpect {
                        expectError(401, "UNAUTHORIZED")
                    }
                }
            }
            When("토큰이 유효하지 않으면") {
                Then("401 UNAUTHORIZED를 응답한다") {
                    `when`(jwtTokenProvider.getUserId("invalid-token")).thenThrow(ApiException(ErrorCode.UNAUTHORIZED))

                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"모아모아"}"""
                    }.andExpect {
                        expectError(401, "UNAUTHORIZED")
                    }

                    verifyNoInteractions(meetingService)
                }
            }
            When("토큰도 없고 요청값도 잘못되었으면") {
                Then("요청값 검증보다 인증을 먼저 확인해 401 UNAUTHORIZED를 응답한다") {
                    mockMvc.post("/v1/meetings") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"  "}"""
                    }.andExpect {
                        expectError(401, "UNAUTHORIZED")
                    }
                }
            }
        }
    }

    private fun MockMvcResultMatchersDsl.expectError(status: Int, code: String) {
        status { isEqualTo(status) }
        jsonPath("$.success") { value(false) }
        jsonPath("$.data") { value(null as Any?) }
        jsonPath("$.error.code") { value(code) }
    }

    companion object {
        // 토큰 검증은 mock으로 대체: ACCESS_TOKEN을 보내면 USER_ID로 인증
        private const val USER_ID = 1L
        private const val ACCESS_TOKEN = "test-access-token"
        private const val BEARER_TOKEN = "Bearer $ACCESS_TOKEN"
    }
}

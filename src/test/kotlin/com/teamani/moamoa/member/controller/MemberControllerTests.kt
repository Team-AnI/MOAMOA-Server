package com.teamani.moamoa.member.controller

import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.dto.MemberLeaveResponse
import com.teamani.moamoa.member.dto.MemberListResponse
import com.teamani.moamoa.member.dto.MemberSummaryResponse
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.service.MemberService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [MemberController::class])
class MemberControllerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var memberService: MemberService

    @MockitoBean
    lateinit var jwtTokenProvider: JwtTokenProvider

    init {
        beforeTest {
            `when`(jwtTokenProvider.getUserId(ACCESS_TOKEN)).thenReturn(USER_ID)
        }

        Given("구성원 목록 조회 요청(GET /v1/meetings/{meetingId}/members)이 주어졌을 때") {
            When("모임 구성원이 요청하면") {
                Then("200과 구성원 목록을 반환하고 userName은 null로 응답한다") {
                    `when`(memberService.getMembers(1L, USER_ID)).thenReturn(
                        MemberListResponse(
                            listOf(
                                MemberSummaryResponse(100L, null, MemberRole.ADMIN),
                                MemberSummaryResponse(200L, null, MemberRole.MEMBER),
                            )
                        )
                    )

                    mockMvc.get("/v1/meetings/1/members") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.data.members[0].memberId") { value(100) }
                        jsonPath("$.data.members[0].userName") { value(null as Any?) }
                        jsonPath("$.data.members[0].role") { value("ADMIN") }
                        jsonPath("$.data.members[1].role") { value("MEMBER") }
                    }
                }
            }
        }

        Given("모임 탈퇴 요청(DELETE /v1/meetings/{meetingId}/members/me)이 주어졌을 때") {
            When("MEMBER가 요청하면") {
                Then("200과 meetingId를 반환한다") {
                    `when`(memberService.leaveMeeting(1L, USER_ID)).thenReturn(MemberLeaveResponse(1L))

                    mockMvc.delete("/v1/meetings/1/members/me") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.meetingId") { value(1) }
                    }
                }
            }
            When("ADMIN이 요청하면") {
                Then("422 UNPROCESSABLE_ENTITY를 응답한다") {
                    `when`(memberService.leaveMeeting(2L, USER_ID))
                        .thenThrow(ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "모임 관리자는 탈퇴할 수 없습니다."))

                    mockMvc.delete("/v1/meetings/2/members/me") {
                        header(HttpHeaders.AUTHORIZATION, BEARER_TOKEN)
                    }.andExpect {
                        status { isEqualTo(422) }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.error.code") { value("UNPROCESSABLE_ENTITY") }
                    }
                }
            }
        }
    }

    companion object {
        // 토큰 검증은 mock으로 대체: ACCESS_TOKEN을 보내면 USER_ID로 인증
        private const val USER_ID = 1L
        private const val ACCESS_TOKEN = "test-access-token"
        private const val BEARER_TOKEN = "Bearer $ACCESS_TOKEN"
    }
}

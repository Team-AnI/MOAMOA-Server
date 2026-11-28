package com.teamani.moamoa.meeting

import com.jayway.jsonpath.JsonPath
import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime

// 실제 트랜잭션·DB 제약을 거친 최종 HTTP 응답 검증 (Controller 테스트는 Service를 mock으로 대체하므로 별도 작성)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeetingIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @MockitoSpyBean
    lateinit var meetingRepository: MeetingRepository

    @MockitoSpyBean
    lateinit var memberRepository: MemberRepository

    init {
        afterTest {
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
        }

        Given("중복 확인을 통과한 직후 다른 요청이 같은 초대코드를 먼저 저장했을 때") {
            When("모임을 생성하면") {
                Then("unique 제약 위반이 409 CONFLICT로 응답되고 모임과 ADMIN 구성원은 저장되지 않는다") {
                    // 동시 요청 상황 재현: 초대코드 중복 확인 직후 다른 요청이 같은 코드로 모임을 먼저 저장
                    doAnswer { invocation ->
                        jdbcTemplate.update(
                            "insert into meeting (name, invite_code, created_at) values (?, ?, ?)",
                            "다른 모임", invocation.getArgument<String>(0), OffsetDateTime.now()
                        )
                        false
                    }.`when`(meetingRepository).existsByInviteCode(anyString())

                    mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtTokenProvider.createAccessToken(USER_ID)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"모아모아"}"""
                    }.andExpect {
                        status { isConflict() }
                        jsonPath("$.error.code") { value("CONFLICT") }
                    }

                    meetingRepository.findAll().map { it.name } shouldNotContain "모아모아"
                    memberRepository.count() shouldBe 0L
                }
            }
        }

        Given("중복 확인을 통과한 직후 다른 요청이 먼저 같은 구성원을 저장했을 때") {
            When("초대코드로 참여하면") {
                Then("unique 제약 위반이 409 CONFLICT로 응답되고 구성원은 1명만 남는다") {
                    val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = "RACE0001")).id)
                    memberRepository.save(Member(meetingId = meetingId, userId = USER_ID, role = MemberRole.MEMBER))
                    // 동시 요청 상황 재현: 중복 확인은 통과시키고 실제 저장에서 unique 제약에 걸리게 함
                    doReturn(false).`when`(memberRepository).existsByMeetingIdAndUserId(anyLong(), anyLong())

                    mockMvc.post("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtTokenProvider.createAccessToken(USER_ID)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"inviteCode":"RACE0001"}"""
                    }.andExpect {
                        status { isConflict() }
                        jsonPath("$.error.code") { value("CONFLICT") }
                    }

                    memberRepository.countByMeetingId(meetingId) shouldBe 1L
                }
            }
        }

        Given("ADMIN이 모임을 수정할 때") {
            When("필수 항목 name에 null을 보내면") {
                Then("400 VALIDATION_ERROR를 응답하고 모임명은 그대로다") {
                    val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = "EDIT0001")).id)
                    memberRepository.save(Member(meetingId = meetingId, userId = USER_ID, role = MemberRole.ADMIN))

                    mockMvc.patch("/v1/meetings/$meetingId") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtTokenProvider.createAccessToken(USER_ID)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":null}"""
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                        jsonPath("$.error.details[0].field") { value("name") }
                    }

                    meetingRepository.findById(meetingId).map { it.name }.orElse(null) shouldBe "모아모아"
                }
            }
        }

        Given("서로 다른 두 사용자가 액세스 토큰으로 요청할 때") {
            When("한 사용자가 모임을 만들고 다른 사용자가 초대코드로 참여하면") {
                Then("각자의 토큰으로 요청자가 구분되어 역할과 구성원이 따로 저장된다") {
                    val adminToken = "Bearer ${jwtTokenProvider.createAccessToken(ADMIN_USER_ID)}"
                    val memberToken = "Bearer ${jwtTokenProvider.createAccessToken(MEMBER_USER_ID)}"

                    val createResult = mockMvc.post("/v1/meetings") {
                        header(HttpHeaders.AUTHORIZATION, adminToken)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"모아모아"}"""
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.data.myRole") { value("ADMIN") }
                    }.andReturn()
                    val meetingId = JsonPath.read<Number>(createResult.response.contentAsString, "$.data.meetingId").toLong()
                    val inviteCode = JsonPath.read<String>(createResult.response.contentAsString, "$.data.inviteCode")

                    mockMvc.post("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, memberToken)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"inviteCode":"$inviteCode"}"""
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.data.myRole") { value("MEMBER") }
                    }

                    mockMvc.get("/v1/me/meetings") {
                        header(HttpHeaders.AUTHORIZATION, memberToken)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.meetings[0].meetingId") { value(meetingId.toInt()) }
                        jsonPath("$.data.meetings[0].memberCount") { value(2) }
                        jsonPath("$.data.meetings[0].myRole") { value("MEMBER") }
                    }

                    mockMvc.get("/v1/meetings/$meetingId/members") {
                        header(HttpHeaders.AUTHORIZATION, adminToken)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.members.length()") { value(2) }
                    }

                    memberRepository.findByMeetingIdAndUserId(meetingId, ADMIN_USER_ID)?.role shouldBe MemberRole.ADMIN
                    memberRepository.findByMeetingIdAndUserId(meetingId, MEMBER_USER_ID)?.role shouldBe MemberRole.MEMBER
                }
            }
        }

        Given("액세스 토큰 없이 모임 생성 요청이 주어졌을 때") {
            When("요청하면") {
                Then("401 UNAUTHORIZED이고 모임과 구성원이 저장되지 않는다") {
                    mockMvc.post("/v1/meetings") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"인증없는모임"}"""
                    }.andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.error.code") { value("UNAUTHORIZED") }
                    }

                    meetingRepository.findAll().map { it.name } shouldNotContain "인증없는모임"
                    memberRepository.count() shouldBe 0L
                }
            }
        }
    }

    companion object {
        // 요청자 (MeetingController·MemberController는 액세스 토큰으로 식별)
        private const val USER_ID = 1L
        private const val ADMIN_USER_ID = 10L
        private const val MEMBER_USER_ID = 20L
    }
}

package com.teamani.moamoa.schedule

import com.jayway.jsonpath.JsonPath
import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.dto.ScheduleMyResponseRequest
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.schedule.repository.ScheduleResponseRepository
import com.teamani.moamoa.schedule.service.ScheduleResponseService
import com.teamani.moamoa.schedule.service.ScheduleService
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.entity.ProfileColor
import com.teamani.moamoa.user.repository.AppUserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실제 토큰·DB로 일정 참석 응답(2-6·2-7)과 일정 준비물·응답 마감(2-1·2-4), 모임 나가기·일정 삭제 시 응답 정리를 검증
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleResponseIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    @Autowired
    lateinit var scheduleRepository: ScheduleRepository

    @Autowired
    lateinit var scheduleResponseRepository: ScheduleResponseRepository

    @Autowired
    lateinit var scheduleResponseService: ScheduleResponseService

    @Autowired
    lateinit var scheduleService: ScheduleService

    @Autowired
    lateinit var memberService: MemberService

    init {
        Given("관리자 A, 구성원 B·C가 있는 모임의 예정 일정이 있을 때") {
            val group = savedGroup()
            val startsAt = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS)
            val scheduleId = savedSchedule(group.meetingId, startsAt = startsAt)
            val url = "/v1/meetings/${group.meetingId}/schedules/$scheduleId/responses"

            When("B가 참석했다가 불참으로 바꾸고 A가 미정으로 응답하면") {
                respond(url, group.b, "ATTENDING").andExpect { status { isOk() }; jsonPath("$.data.status") { value("ATTENDING") } }
                respond(url, group.b, "NOT_ATTENDING").andExpect {
                    status { isOk() }
                    jsonPath("$.data.scheduleId") { value(scheduleId) }
                    jsonPath("$.data.status") { value("NOT_ATTENDING") }
                    jsonPath("$.data.respondedAt") { exists() }
                }
                respond(url, group.a, "UNDECIDED").andExpect { status { isOk() } }

                Then("B의 응답은 하나만 남고 바뀐 상태로 저장된다") {
                    scheduleResponseRepository.findAllByScheduleId(scheduleId).size shouldBe 2
                }

                Then("C가 참석 현황을 조회하면 상태별 인원·명단과 실제 마감(시작 일시)이 나오고 C의 응답은 없다") {
                    mockMvc.get(url) { header(HttpHeaders.AUTHORIZATION, group.c.token) }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.responseDeadlineAt") { value(startsAt.withOffsetSameInstant(KST).toString()) }
                        jsonPath("$.data.myStatus") { value(null as Any?) }
                        jsonPath("$.data.summary.attendingCount") { value(0) }
                        jsonPath("$.data.summary.notAttendingCount") { value(1) }
                        jsonPath("$.data.summary.undecidedCount") { value(1) }
                        jsonPath("$.data.summary.noResponseCount") { value(1) }
                        jsonPath("$.data.summary.totalCount") { value(3) }
                        jsonPath("$.data.notAttendingMembers[0].memberId") { value(group.b.memberId) }
                        jsonPath("$.data.notAttendingMembers[0].userName") { value("구성원B") }
                        jsonPath("$.data.notAttendingMembers[0].profileColor") { value("BLUE") }
                        jsonPath("$.data.notAttendingMembers[0].respondedAt") { exists() }
                        jsonPath("$.data.undecidedMembers[0].memberId") { value(group.a.memberId) }
                        jsonPath("$.data.noResponseMembers[0].memberId") { value(group.c.memberId) }
                        jsonPath("$.data.noResponseMembers[0].respondedAt") { doesNotExist() }
                    }
                    mockMvc.get(url) { header(HttpHeaders.AUTHORIZATION, group.b.token) }.andExpect {
                        jsonPath("$.data.myStatus") { value("NOT_ATTENDING") }
                    }
                }
            }

            When("응답 후 새 구성원이 가입하고 B가 모임을 나가면") {
                val d = savedMember(group.meetingId, "새구성원D", MemberRole.MEMBER)
                memberService.leaveMeeting(group.meetingId, group.b.userId)

                Then("B의 응답은 삭제되고, 새 구성원은 미응답으로 나온다") {
                    scheduleResponseRepository.findByScheduleIdAndMemberId(scheduleId, group.b.memberId) shouldBe null
                    mockMvc.get(url) { header(HttpHeaders.AUTHORIZATION, group.a.token) }.andExpect {
                        jsonPath("$.data.summary.notAttendingCount") { value(0) }
                        jsonPath("$.data.summary.noResponseCount") { value(2) }
                        jsonPath("$.data.summary.totalCount") { value(3) }
                        jsonPath("$.data.noResponseMembers[1].memberId") { value(d.memberId) }
                    }
                }
            }

            When("관리자가 일정을 삭제하면") {
                scheduleService.deleteSchedule(group.meetingId, group.a.userId, scheduleId)

                Then("그 일정의 응답도 삭제된다") {
                    scheduleResponseRepository.findAllByScheduleId(scheduleId) shouldBe emptyList()
                }
            }
        }

        Given("응답할 수 없는 일정이 있을 때") {
            val group = savedGroup()
            val now = OffsetDateTime.now()
            listOf(
                "응답 마감이 지난 일정" to savedSchedule(group.meetingId, startsAt = now.plusDays(1), responseDeadlineAt = now.minusMinutes(1)),
                "마감 없이 시작 일시가 지난 일정" to savedSchedule(group.meetingId, startsAt = now.minusHours(1)),
                "투표 중인 일정" to savedSchedule(group.meetingId, startsAt = now.plusDays(1), status = ScheduleStatus.VOTING),
                "취소된 일정" to savedSchedule(group.meetingId, startsAt = now.plusDays(1), status = ScheduleStatus.CANCELED)
            ).forEach { (case, scheduleId) ->
                When("$case 에 응답하면") {
                    Then("422 UNPROCESSABLE_ENTITY이고 응답은 저장되지 않는다") {
                        respond("/v1/meetings/${group.meetingId}/schedules/$scheduleId/responses", group.b, "ATTENDING").andExpect {
                            status { isUnprocessableContent() }
                            jsonPath("$.error.code") { value("UNPROCESSABLE_ENTITY") }
                        }
                        scheduleResponseRepository.findAllByScheduleId(scheduleId) shouldBe emptyList()
                    }
                }
            }

            When("확정된 일정에 응답하면") {
                val scheduleId = savedSchedule(group.meetingId, startsAt = now.plusDays(1), status = ScheduleStatus.CONFIRMED)

                Then("200이다") {
                    respond("/v1/meetings/${group.meetingId}/schedules/$scheduleId/responses", group.b, "ATTENDING").andExpect { status { isOk() } }
                }
            }

            When("투표 중인 일정의 참석 현황을 조회하면") {
                val scheduleId = savedSchedule(group.meetingId, startsAt = now.plusDays(1), status = ScheduleStatus.VOTING)

                Then("조회는 되고 응답 마감은 null이다") {
                    mockMvc.get("/v1/meetings/${group.meetingId}/schedules/$scheduleId/responses") {
                        header(HttpHeaders.AUTHORIZATION, group.b.token)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.responseDeadlineAt") { value(null as Any?) }
                        jsonPath("$.data.summary.noResponseCount") { value(3) }
                    }
                }
            }
        }

        Given("권한·요청 오류가 있을 때") {
            val group = savedGroup()
            val other = savedGroup()
            val outsider = savedUserToken("외부인")
            val scheduleId = savedSchedule(group.meetingId, startsAt = OffsetDateTime.now().plusDays(1))
            val otherScheduleId = savedSchedule(other.meetingId, startsAt = OffsetDateTime.now().plusDays(1))
            val url = "/v1/meetings/${group.meetingId}/schedules/$scheduleId/responses"

            Then("모임 구성원이 아니면 응답·조회 모두 403") {
                respond(url, outsider, "ATTENDING").andExpect { status { isForbidden() } }
                mockMvc.get(url) { header(HttpHeaders.AUTHORIZATION, outsider.token) }.andExpect { status { isForbidden() } }
            }

            Then("없는 일정이나 다른 모임의 일정이면 404") {
                respond("/v1/meetings/${group.meetingId}/schedules/999999/responses", group.b, "ATTENDING").andExpect { status { isNotFound() } }
                respond("/v1/meetings/${group.meetingId}/schedules/$otherScheduleId/responses", group.b, "ATTENDING").andExpect { status { isNotFound() } }
            }

            Then("허용되지 않는 응답 값이나 누락은 400 VALIDATION_ERROR, 토큰이 없으면 401") {
                respond(url, group.b, "MAYBE").andExpect {
                    status { isBadRequest() }
                    jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                }
                mockMvc.put("$url/me") {
                    header(HttpHeaders.AUTHORIZATION, group.b.token)
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect { jsonPath("$.error.code") { value("VALIDATION_ERROR") } }
                mockMvc.put("$url/me") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{ "status": "ATTENDING" }"""
                }.andExpect { status { isUnauthorized() } }
                mockMvc.get(url).andExpect { status { isUnauthorized() } }
            }
        }

        Given("같은 구성원이 첫 응답을 동시에 여러 번 보낼 때") {
            val group = savedGroup()
            val scheduleId = savedSchedule(group.meetingId, startsAt = OffsetDateTime.now().plusDays(1))

            When("동시에 요청하면") {
                val errors = runConcurrently(
                    List(4) {
                        { scheduleResponseService.respond(group.meetingId, group.b.userId, scheduleId, ScheduleMyResponseRequest("ATTENDING")) }
                    }
                )

                Then("모두 성공하고 응답은 하나만 남는다") {
                    errors.filterNotNull() shouldBe emptyList()
                    scheduleResponseRepository.findAllByScheduleId(scheduleId).size shouldBe 1
                }
            }
        }

        Given("관리자가 준비물·응답 마감이 있는 일정을 만들고 고칠 때") {
            val group = savedGroup()
            val base = "/v1/meetings/${group.meetingId}/schedules"
            val startAt = OffsetDateTime.now().plusDays(3).truncatedTo(ChronoUnit.SECONDS)

            Then("마감이 시작 일시보다 뒤이거나 과거면 400이고, 올바르면 201과 상세에 준비물·마감이 나온다") {
                createSchedule(base, group.a, startAt, startAt.plusMinutes(1)).andExpect { status { isBadRequest() } }
                createSchedule(base, group.a, startAt, OffsetDateTime.now().minusMinutes(1)).andExpect { status { isBadRequest() } }

                val created = createSchedule(base, group.a, startAt, startAt.minusDays(1)).andExpect { status { isCreated() } }
                    .andReturn().response.contentAsString
                val scheduleId = JsonPath.read<Int>(created, "$.data.scheduleId")
                mockMvc.get("$base/$scheduleId") { header(HttpHeaders.AUTHORIZATION, group.a.token) }.andExpect {
                    jsonPath("$.data.preparation") { value("러닝화, 물") }
                    jsonPath("$.data.responseDeadlineAt") { value(startAt.minusDays(1).withOffsetSameInstant(KST).toString()) }
                }

                // 시작 일시를 마감보다 앞당기면 400
                updateSchedule("$base/$scheduleId", group.a, """{ "startAt": "${startAt.minusDays(2)}" }""").andExpect { status { isBadRequest() } }
                // 마감을 null로 보내면 비우고(시작 일시까지 응답), 준비물도 null로 비움
                updateSchedule("$base/$scheduleId", group.a, """{ "responseDeadlineAt": null, "preparation": null }""").andExpect {
                    status { isOk() }
                    jsonPath("$.data.responseDeadlineAt") { value(null as Any?) }
                    jsonPath("$.data.preparation") { value(null as Any?) }
                }
                // 마감을 과거로 바꾸면 400
                updateSchedule("$base/$scheduleId", group.a, """{ "responseDeadlineAt": "${OffsetDateTime.now().minusMinutes(1)}" }""")
                    .andExpect { status { isBadRequest() } }
            }
        }
    }

    private fun respond(url: String, who: Who, status: String) = mockMvc.put("$url/me") {
        header(HttpHeaders.AUTHORIZATION, who.token)
        contentType = MediaType.APPLICATION_JSON
        content = """{ "status": "$status" }"""
    }

    private fun createSchedule(base: String, who: Who, startAt: OffsetDateTime, deadline: OffsetDateTime) = mockMvc.post(base) {
        header(HttpHeaders.AUTHORIZATION, who.token)
        contentType = MediaType.APPLICATION_JSON
        content = """{ "title": "정기 모임", "startAt": "$startAt", "preparation": "러닝화, 물", "responseDeadlineAt": "$deadline" }"""
    }

    private fun updateSchedule(url: String, who: Who, body: String) = mockMvc.patch(url) {
        header(HttpHeaders.AUTHORIZATION, who.token)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private data class Who(val userId: Long, val memberId: Long, val token: String)

    private data class Group(val meetingId: Long, val a: Who, val b: Who, val c: Who)

    // 관리자 A(LIGHT_BLUE), 구성원 B(BLUE)·C(GRAY)가 있는 모임
    private fun savedGroup(): Group {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = UUID.randomUUID().toString().take(8))).id)
        return Group(
            meetingId = meetingId,
            a = savedMember(meetingId, "관리자A", MemberRole.ADMIN, ProfileColor.LIGHT_BLUE),
            b = savedMember(meetingId, "구성원B", MemberRole.MEMBER, ProfileColor.BLUE),
            c = savedMember(meetingId, "구성원C", MemberRole.MEMBER, ProfileColor.GRAY)
        )
    }

    private fun savedMember(meetingId: Long, name: String, role: MemberRole, color: ProfileColor = ProfileColor.LIGHT_BLUE): Who {
        val user = savedUserToken(name, color)
        val memberId = checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = user.userId, role = role)).id)
        return user.copy(memberId = memberId)
    }

    private fun savedUserToken(name: String, color: ProfileColor = ProfileColor.LIGHT_BLUE): Who {
        val userId = checkNotNull(
            appUserRepository.save(
                AppUser(
                    name = name,
                    email = "rsvp-${UUID.randomUUID()}@moamoa.test",
                    password = "password",
                    profileColor = color,
                    termsAgreedAt = OffsetDateTime.now()
                )
            ).id
        )
        return Who(userId = userId, memberId = 0L, token = "Bearer " + jwtTokenProvider.createAccessToken(userId))
    }

    private fun savedSchedule(
        meetingId: Long,
        startsAt: OffsetDateTime,
        responseDeadlineAt: OffsetDateTime? = null,
        status: ScheduleStatus = ScheduleStatus.SCHEDULED
    ): Long =
        checkNotNull(
            scheduleRepository.save(
                Schedule(meetingId = meetingId, title = "정기 모임", startsAt = startsAt, responseDeadlineAt = responseDeadlineAt, status = status)
            ).id
        )

    // 모든 작업을 같은 시점에 시작하고, 작업별 예외(성공이면 null)를 순서대로 반환
    private fun runConcurrently(tasks: List<() -> Any?>): List<Throwable?> {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tasks.size)
        try {
            val futures = tasks.map { task ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        start.await()
                        runCatching { task() }.exceptionOrNull()
                    }
                )
            }
            ready.await()
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
    }
}

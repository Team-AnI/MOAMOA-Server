package com.teamani.moamoa.vote

import com.jayway.jsonpath.JsonPath
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.vote.dto.MyVoteSubmitRequest
import com.teamani.moamoa.vote.entity.Vote
import com.teamani.moamoa.vote.entity.VoteStatus
import com.teamani.moamoa.vote.repository.VoteRepository
import com.teamani.moamoa.vote.service.VoteService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// 실제 트랜잭션·DB를 거친 투표 흐름과 모임 탈퇴 연동 검증 (Controller·Service 테스트는 mock 사용)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoteIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var scheduleRepository: ScheduleRepository

    @Autowired
    lateinit var voteRepository: VoteRepository

    @Autowired
    lateinit var voteService: VoteService

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    private val firstTime = OffsetDateTime.parse("2026-11-01T19:00:00+09:00")
    private val secondTime = OffsetDateTime.parse("2026-11-02T19:00:00+09:00")
    private val secondTimeUtc = OffsetDateTime.parse("2026-11-02T10:00:00Z")

    // 같은 일정의 구성원 응답을 후보 시간 순으로 조회
    private fun votesOf(scheduleId: Long, memberId: Long): List<Vote> =
        voteRepository.findAllByScheduleId(scheduleId)
            .filter { it.memberId == memberId }
            .sortedBy { it.meetingTime.toInstant() }

    init {
        afterTest {
            voteRepository.deleteAll()
            scheduleRepository.deleteAll()
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
        }

        Given("ADMIN이 투표를 만들고 제출·확정할 때") {
            When("생성 → 제출 → 재제출 → 확정 순서로 요청하면") {
                Then("응답이 중복 저장되지 않고, 확정 후 일정 시간과 상태가 바뀌며 이후 제출은 422로 거절된다") {
                    val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = "VOTE0001")).id)
                    val adminMemberId = checkNotNull(
                        memberRepository.save(Member(meetingId = meetingId, userId = USER_ID, role = MemberRole.ADMIN)).id
                    )
                    memberRepository.save(Member(meetingId = meetingId, userId = 2L, role = MemberRole.MEMBER))
                    val deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.SECONDS)
                    val firstTimeUtc = firstTime.withOffsetSameInstant(ZoneOffset.UTC)

                    // 5-1 투표 생성 (후보를 다른 표기와 역순으로 전달)
                    val createResult = mockMvc.post("/v1/meetings/$meetingId/schedule-votes") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"title":"11월 정기 모임","voteDeadlineAt":"$deadline",""" +
                            """"meetingTimes":["$secondTime","$firstTimeUtc"]}"""
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.data.meetingTimes[0]") { value("2026-11-01T19:00:00+09:00") }
                    }.andReturn()
                    val scheduleId = JsonPath.read<Int>(createResult.response.contentAsString, "$.data.scheduleId").toLong()

                    val votingSchedule = scheduleRepository.findById(scheduleId).orElseThrow()
                    votingSchedule.status shouldBe ScheduleStatus.VOTING
                    votingSchedule.startsAt.toInstant() shouldBe firstTime.toInstant()
                    votesOf(scheduleId, adminMemberId).map { it.status } shouldContainExactly
                        listOf(VoteStatus.PENDING, VoteStatus.PENDING)

                    // 5-3 첫 제출 후 다른 표기로 재제출
                    mockMvc.put("/v1/meetings/$meetingId/schedules/$scheduleId/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":["$firstTime"]}"""
                    }.andExpect { status { isOk() } }
                    mockMvc.put("/v1/meetings/$meetingId/schedules/$scheduleId/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":["$secondTimeUtc"]}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.meetingTimes[0]") { value("2026-11-02T19:00:00+09:00") }
                    }

                    voteRepository.findAllByScheduleId(scheduleId) shouldHaveSize 2
                    votesOf(scheduleId, adminMemberId).map { it.status } shouldContainExactly
                        listOf(VoteStatus.UNAVAILABLE, VoteStatus.AVAILABLE)

                    // 5-2 투표 현황
                    mockMvc.get("/v1/meetings/$meetingId/schedules/$scheduleId/candidates").andExpect {
                        status { isOk() }
                        jsonPath("$.data.votingOpen") { value(true) }
                        jsonPath("$.data.memberCount") { value(2) }
                        jsonPath("$.data.respondedCount") { value(1) }
                        jsonPath("$.data.candidates[1].meetingTime") { value("2026-11-02T19:00:00+09:00") }
                        jsonPath("$.data.candidates[1].availableCount") { value(1) }
                        jsonPath("$.data.candidates[1].selectedByMe") { value(true) }
                    }

                    // 5-5 마감 전 확정
                    mockMvc.post("/v1/meetings/$meetingId/schedules/$scheduleId/confirmation") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTime":"$secondTimeUtc"}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.startAt") { value("2026-11-02T19:00:00+09:00") }
                        jsonPath("$.data.status") { value("CONFIRMED") }
                    }

                    val confirmedSchedule = scheduleRepository.findById(scheduleId).orElseThrow()
                    confirmedSchedule.status shouldBe ScheduleStatus.CONFIRMED
                    confirmedSchedule.startsAt.toInstant() shouldBe secondTime.toInstant()

                    // 확정 후에도 현황 조회 가능, 제출은 거절되고 기존 선택 유지
                    mockMvc.get("/v1/meetings/$meetingId/schedules/$scheduleId/candidates").andExpect {
                        status { isOk() }
                        jsonPath("$.data.status") { value("CONFIRMED") }
                        jsonPath("$.data.votingOpen") { value(false) }
                    }
                    mockMvc.put("/v1/meetings/$meetingId/schedules/$scheduleId/votes/me") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"meetingTimes":["$firstTime"]}"""
                    }.andExpect {
                        status { isEqualTo(422) }
                        jsonPath("$.error.code") { value("UNPROCESSABLE_ENTITY") }
                    }
                    votesOf(scheduleId, adminMemberId).map { it.status } shouldContainExactly
                        listOf(VoteStatus.UNAVAILABLE, VoteStatus.AVAILABLE)
                }
            }
        }

        Given("직접 등록한 일정(투표 행 없음)이 있을 때") {
            When("투표 현황을 조회하면") {
                Then("404 NOT_FOUND를 응답한다") {
                    val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = "VOTE0002")).id)
                    memberRepository.save(Member(meetingId = meetingId, userId = USER_ID, role = MemberRole.MEMBER))
                    val scheduleId = checkNotNull(
                        scheduleRepository.save(Schedule(meetingId = meetingId, title = "직접 등록 일정", startsAt = firstTime)).id
                    )

                    mockMvc.get("/v1/meetings/$meetingId/schedules/$scheduleId/candidates").andExpect {
                        status { isNotFound() }
                        jsonPath("$.error.code") { value("NOT_FOUND") }
                    }
                }
            }
        }

        Given("한 트랜잭션 안에서 같은 구성원이 투표를 다시 제출할 때") {
            When("이전 제출로 저장한 행을 id로 다시 조회하면") {
                Then("벌크 삭제 후 영속성 컨텍스트가 비워져 삭제된 행이 조회되지 않고 후보 수만큼만 남는다") {
                    val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = "VOTE0004")).id)
                    val adminMemberId = checkNotNull(
                        memberRepository.save(Member(meetingId = meetingId, userId = 99L, role = MemberRole.ADMIN)).id
                    )
                    val memberId = checkNotNull(
                        memberRepository.save(Member(meetingId = meetingId, userId = USER_ID, role = MemberRole.MEMBER)).id
                    )
                    val scheduleId = checkNotNull(
                        scheduleRepository.save(
                            Schedule(
                                meetingId = meetingId,
                                title = "11월 정기 모임",
                                startsAt = firstTime,
                                status = ScheduleStatus.VOTING
                            )
                        ).id
                    )
                    val deadline = OffsetDateTime.now().plusDays(1)
                    voteRepository.saveAll(
                        listOf(firstTime, secondTime).map {
                            Vote(adminMemberId, scheduleId, it, VoteStatus.PENDING, endedAt = deadline)
                        }
                    )

                    transactionTemplate.executeWithoutResult {
                        voteService.submitMyVote(meetingId, USER_ID, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                        val previousIds = votesOf(scheduleId, memberId).map { checkNotNull(it.id) }

                        voteService.submitMyVote(meetingId, USER_ID, scheduleId, MyVoteSubmitRequest(listOf(secondTime)))

                        previousIds.map { voteRepository.findById(it).isPresent }.distinct() shouldContainExactly listOf(false)
                        votesOf(scheduleId, memberId).map { it.status } shouldContainExactly
                            listOf(VoteStatus.UNAVAILABLE, VoteStatus.AVAILABLE)
                    }
                }
            }
        }

        Given("투표에 응답한 MEMBER가 있을 때") {
            When("그 구성원이 모임을 탈퇴하면") {
                Then("그 구성원의 투표 응답이 함께 삭제되고 다른 구성원의 행은 유지된다") {
                    val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = "VOTE0003")).id)
                    val adminMemberId = checkNotNull(
                        memberRepository.save(Member(meetingId = meetingId, userId = 99L, role = MemberRole.ADMIN)).id
                    )
                    val leavingMemberId = checkNotNull(
                        memberRepository.save(Member(meetingId = meetingId, userId = USER_ID, role = MemberRole.MEMBER)).id
                    )
                    val scheduleId = checkNotNull(
                        scheduleRepository.save(
                            Schedule(
                                meetingId = meetingId,
                                title = "11월 정기 모임",
                                startsAt = firstTime,
                                status = ScheduleStatus.VOTING
                            )
                        ).id
                    )
                    val deadline = OffsetDateTime.now().plusDays(1)
                    voteRepository.saveAll(
                        listOf(
                            Vote(adminMemberId, scheduleId, firstTime, VoteStatus.PENDING, endedAt = deadline),
                            Vote(adminMemberId, scheduleId, secondTime, VoteStatus.PENDING, endedAt = deadline),
                            Vote(leavingMemberId, scheduleId, firstTime, VoteStatus.AVAILABLE, endedAt = deadline),
                            Vote(leavingMemberId, scheduleId, secondTime, VoteStatus.UNAVAILABLE, endedAt = deadline),
                        )
                    )

                    mockMvc.delete("/v1/meetings/$meetingId/members/me").andExpect {
                        status { isOk() }
                    }

                    voteRepository.findAllByScheduleId(scheduleId).map { it.memberId }.distinct() shouldContainExactly
                        listOf(adminMemberId)
                    memberRepository.countByMeetingId(meetingId) shouldBe 1L
                }
            }
        }
    }

    companion object {
        // Controller 임시 로그인 사용자 (currentUserId = 1L)
        private const val USER_ID = 1L
    }
}

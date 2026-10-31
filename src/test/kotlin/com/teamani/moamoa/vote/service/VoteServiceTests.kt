package com.teamani.moamoa.vote.service

import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.vote.dto.MyVoteSubmitRequest
import com.teamani.moamoa.vote.dto.VoteConfirmRequest
import com.teamani.moamoa.vote.dto.VoteCreateRequest
import com.teamani.moamoa.vote.entity.Vote
import com.teamani.moamoa.vote.entity.VoteStatus
import com.teamani.moamoa.vote.repository.VoteRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.hibernate.exception.ConstraintViolationException
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset

class VoteServiceTests : BehaviorSpec({

    val meetingRepository: MeetingRepository = mock(MeetingRepository::class.java)
    val memberRepository: MemberRepository = mock(MemberRepository::class.java)
    val scheduleRepository: ScheduleRepository = mock(ScheduleRepository::class.java)
    val voteRepository: VoteRepository = mock(VoteRepository::class.java)
    val memberService = MemberService(meetingRepository, memberRepository, voteRepository, mock(AttendanceRepository::class.java))
    val voteService = VoteService(memberService, memberRepository, scheduleRepository, voteRepository)

    val meetingId = 1L
    val scheduleId = 5L
    val adminUserId = 10L
    val memberUserId = 20L
    val quietUserId = 30L
    val lateUserId = 40L
    val outsiderUserId = 50L
    val admin = Member(meetingId = meetingId, userId = adminUserId, role = MemberRole.ADMIN, id = 100L)
    val member = Member(meetingId = meetingId, userId = memberUserId, role = MemberRole.MEMBER, id = 200L)
    // 투표에 응답하지 않은 구성원
    val quietMember = Member(meetingId = meetingId, userId = quietUserId, role = MemberRole.MEMBER, id = 300L)
    // 투표 생성 후 가입한 구성원
    val lateMember = Member(meetingId = meetingId, userId = lateUserId, role = MemberRole.MEMBER, id = 400L)

    val kst = ZoneOffset.ofHours(9)
    val firstTime = OffsetDateTime.parse("2026-11-01T19:00:00+09:00")
    val secondTime = OffsetDateTime.parse("2026-11-02T19:00:00+09:00")
    val thirdTime = OffsetDateTime.parse("2026-11-03T19:00:00+09:00")

    fun schedule(status: ScheduleStatus = ScheduleStatus.VOTING) = Schedule(
        meetingId = meetingId,
        title = "11월 정기 모임",
        startsAt = firstTime,
        status = status,
        id = scheduleId
    )

    // DB에서 UTC로 읽힌 일시도 같은 후보로 처리되는지 확인하기 위해 UTC 표기로 생성
    fun vote(
        memberId: Long,
        meetingTime: OffsetDateTime,
        status: VoteStatus,
        endedAt: OffsetDateTime,
        createdAt: OffsetDateTime = OffsetDateTime.now()
    ) = Vote(
        memberId = memberId,
        scheduleId = scheduleId,
        meetingTime = meetingTime.withOffsetSameInstant(ZoneOffset.UTC),
        status = status,
        createdAt = createdAt,
        endedAt = endedAt
    )

    // 투표 생성 시 저장되는 관리자 PENDING 행 (후보 목록 기준)
    fun adminRows(endedAt: OffsetDateTime) =
        listOf(firstTime, secondTime, thirdTime).map { vote(100L, it, VoteStatus.PENDING, endedAt) }

    // 조회 API는 일반 조회, 제출·확정은 잠금 조회로 일정을 읽으므로 둘 다 같은 일정 반환
    fun stubVote(schedule: Schedule, votes: List<Vote>) {
        `when`(scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)).thenReturn(schedule)
        `when`(scheduleRepository.findByIdAndMeetingIdForUpdate(scheduleId, meetingId)).thenReturn(schedule)
        `when`(voteRepository.findAllByScheduleId(scheduleId)).thenReturn(votes)
    }

    @Suppress("UNCHECKED_CAST")
    fun voteCaptor() = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Vote>>

    // 투표 생성 시 저장한 관리자 행
    fun savedVotes(): List<Vote> {
        val captor = voteCaptor()
        verify(voteRepository).saveAll(captor.capture())
        return captor.value
    }

    // 제출 시 unique 제약 위반을 바로 확인하도록 flush까지 실행해 저장한 행
    fun submittedVotes(): List<Vote> {
        val captor = voteCaptor()
        verify(voteRepository).saveAllAndFlush(captor.capture())
        return captor.value
    }

    beforeTest {
        reset(meetingRepository, memberRepository, scheduleRepository, voteRepository)
        `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(true)
        listOf(admin, member, quietMember, lateMember).forEach {
            `when`(memberRepository.findByMeetingIdAndUserId(meetingId, it.userId)).thenReturn(it)
        }
    }

    Given("투표 생성 요청이 주어졌을 때") {
        val deadline = OffsetDateTime.now().plusDays(3)
        // 후보를 오름차순이 아닌 순서와 Z 표기로 전달
        val request = VoteCreateRequest(
            title = "11월 정기 모임",
            voteDeadlineAt = deadline,
            meetingTimes = listOf(secondTime.withOffsetSameInstant(ZoneOffset.UTC), firstTime)
        )

        When("ADMIN이 요청하면") {
            Then("VOTING 일정에 가장 이른 후보 시간을 임시 저장하고, 관리자 PENDING 행을 후보 수만큼 저장한다") {
                `when`(scheduleRepository.save(any(Schedule::class.java))).thenAnswer {
                    val saved = it.getArgument<Schedule>(0)
                    Schedule(
                        meetingId = saved.meetingId,
                        title = saved.title,
                        startsAt = saved.startsAt,
                        status = saved.status,
                        id = scheduleId
                    )
                }

                val response = voteService.createVote(meetingId, adminUserId, request)

                val scheduleCaptor = ArgumentCaptor.forClass(Schedule::class.java)
                verify(scheduleRepository).save(scheduleCaptor.capture())
                scheduleCaptor.value.status shouldBe ScheduleStatus.VOTING
                scheduleCaptor.value.startsAt.toInstant() shouldBe firstTime.toInstant()

                val votes = savedVotes()
                votes shouldHaveSize 2
                votes.map { it.memberId }.distinct() shouldContainExactly listOf(100L)
                votes.map { it.status }.distinct() shouldContainExactly listOf(VoteStatus.PENDING)
                votes.map { it.endedAt }.distinct() shouldContainExactly listOf(deadline)

                response.scheduleId shouldBe scheduleId
                response.title shouldBe "11월 정기 모임"
                response.voteDeadlineAt shouldBe deadline.withOffsetSameInstant(kst)
                response.meetingTimes shouldContainExactly listOf(firstTime, secondTime)
            }
        }
        When("MEMBER가 요청하면") {
            Then("403 FORBIDDEN 예외가 발생하고 저장하지 않는다") {
                shouldThrow<ApiException> {
                    voteService.createVote(meetingId, memberUserId, request)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
                verify(scheduleRepository, never()).save(any(Schedule::class.java))
            }
        }
        When("모임이 없으면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(false)

                shouldThrow<ApiException> {
                    voteService.createVote(meetingId, adminUserId, request)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("표기만 다르고 같은 순간인 후보 시간이 있으면") {
            Then("400 VALIDATION_ERROR 예외가 발생하고 저장하지 않는다") {
                val duplicated = request.copy(meetingTimes = listOf(firstTime, firstTime.withOffsetSameInstant(ZoneOffset.UTC)))

                shouldThrow<ApiException> {
                    voteService.createVote(meetingId, adminUserId, duplicated)
                }.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verify(scheduleRepository, never()).save(any(Schedule::class.java))
                verify(voteRepository, never()).saveAll(anyList<Vote>())
            }
        }
    }

    Given("투표 현황을 조회할 때") {
        val openDeadline = OffsetDateTime.now().plusDays(1)
        val responses = listOf(
            vote(200L, firstTime, VoteStatus.AVAILABLE, openDeadline),
            vote(200L, secondTime, VoteStatus.UNAVAILABLE, openDeadline),
            vote(200L, thirdTime, VoteStatus.UNAVAILABLE, openDeadline),
            vote(400L, firstTime, VoteStatus.AVAILABLE, openDeadline),
            vote(400L, secondTime, VoteStatus.AVAILABLE, openDeadline),
            vote(400L, thirdTime, VoteStatus.UNAVAILABLE, openDeadline),
        )

        When("구성원이 요청하면") {
            Then("후보를 오름차순으로, 아무도 고르지 않은 후보도 0명으로 반환하고 내 선택을 표시한다") {
                stubVote(schedule(), adminRows(openDeadline) + responses)
                `when`(memberRepository.countByMeetingId(meetingId)).thenReturn(4L)

                val response = voteService.getVote(meetingId, memberUserId, scheduleId)

                response.scheduleId shouldBe scheduleId
                response.title shouldBe "11월 정기 모임"
                response.status shouldBe ScheduleStatus.VOTING
                response.voteDeadlineAt shouldBe openDeadline.withOffsetSameInstant(kst)
                response.votingOpen shouldBe true
                response.memberCount shouldBe 4L
                response.respondedCount shouldBe 2L
                response.myResponded shouldBe true
                response.candidates.map { it.meetingTime } shouldContainExactly listOf(firstTime, secondTime, thirdTime)
                response.candidates.map { it.availableCount } shouldContainExactly listOf(2L, 1L, 0L)
                response.candidates.map { it.selectedByMe } shouldContainExactly listOf(true, false, false)
            }
        }
        When("투표를 만든 관리자가 아직 제출하지 않았으면") {
            Then("myResponded가 false이고 선택한 후보가 없다") {
                stubVote(schedule(), adminRows(openDeadline) + responses)

                val response = voteService.getVote(meetingId, adminUserId, scheduleId)

                response.myResponded shouldBe false
                response.candidates.map { it.selectedByMe } shouldContainExactly listOf(false, false, false)
            }
        }
        When("마감 일시가 지났으면") {
            Then("votingOpen이 false인 현황을 반환한다") {
                val closedDeadline = OffsetDateTime.now().minusHours(1)
                stubVote(schedule(), adminRows(closedDeadline))

                val response = voteService.getVote(meetingId, memberUserId, scheduleId)

                response.votingOpen shouldBe false
                response.candidates shouldHaveSize 3
            }
        }
        When("확정된 일정이면") {
            Then("상태 CONFIRMED와 votingOpen false로 현황을 반환한다") {
                stubVote(schedule(ScheduleStatus.CONFIRMED), adminRows(openDeadline) + responses)

                val response = voteService.getVote(meetingId, memberUserId, scheduleId)

                response.status shouldBe ScheduleStatus.CONFIRMED
                response.votingOpen shouldBe false
                response.candidates.map { it.availableCount } shouldContainExactly listOf(2L, 1L, 0L)
            }
        }
        When("투표가 없는 일정(직접 등록한 일정)이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                stubVote(schedule(ScheduleStatus.SCHEDULED), emptyList())

                shouldThrow<ApiException> {
                    voteService.getVote(meetingId, memberUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("다른 모임의 일정이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                `when`(scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)).thenReturn(null)

                shouldThrow<ApiException> {
                    voteService.getVote(meetingId, memberUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("모임 구성원이 아니면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    voteService.getVote(meetingId, outsiderUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
    }

    Given("내 투표를 제출할 때") {
        val openDeadline = OffsetDateTime.now().plusDays(1)
        val closedDeadline = OffsetDateTime.now().minusHours(1)

        When("후보 시간을 Z 표기와 섞어 선택하면") {
            Then("기존 응답을 먼저 삭제하고 모든 후보를 AVAILABLE/UNAVAILABLE로 다시 저장한다") {
                stubVote(schedule(), adminRows(openDeadline))
                val request = MyVoteSubmitRequest(listOf(secondTime.withOffsetSameInstant(ZoneOffset.UTC), firstTime))

                val response = voteService.submitMyVote(meetingId, memberUserId, scheduleId, request)

                verify(scheduleRepository).findByIdAndMeetingIdForUpdate(scheduleId, meetingId)
                val order = inOrder(voteRepository)
                order.verify(voteRepository).deleteAllByScheduleIdAndMemberId(scheduleId, 200L)
                order.verify(voteRepository).saveAllAndFlush(anyList<Vote>())

                val votes = submittedVotes()
                votes.map { it.meetingTime.toInstant() } shouldContainExactly
                    listOf(firstTime, secondTime, thirdTime).map { it.toInstant() }
                votes.map { it.status } shouldContainExactly
                    listOf(VoteStatus.AVAILABLE, VoteStatus.AVAILABLE, VoteStatus.UNAVAILABLE)
                votes.map { it.memberId }.distinct() shouldContainExactly listOf(200L)
                votes.map { it.endedAt }.distinct() shouldContainExactly listOf(openDeadline)
                votes.map { it.createdAt }.distinct() shouldHaveSize 1

                response.scheduleId shouldBe scheduleId
                response.meetingTimes shouldContainExactly listOf(firstTime, secondTime)
                response.submittedAt shouldBe votes.first().createdAt.withOffsetSameInstant(kst)
            }
        }
        When("빈 배열을 제출하면") {
            Then("가능한 시간 없음으로 모든 후보를 UNAVAILABLE로 저장한다") {
                stubVote(schedule(), adminRows(openDeadline))

                val response = voteService.submitMyVote(meetingId, memberUserId, scheduleId, MyVoteSubmitRequest(emptyList()))

                submittedVotes().map { it.status }.distinct() shouldContainExactly listOf(VoteStatus.UNAVAILABLE)
                response.meetingTimes.shouldBeEmpty()
            }
        }
        When("투표 생성 후 가입한 구성원이 제출하면") {
            Then("해당 구성원의 응답으로 저장한다") {
                stubVote(schedule(), adminRows(openDeadline))

                voteService.submitMyVote(meetingId, lateUserId, scheduleId, MyVoteSubmitRequest(listOf(thirdTime)))

                submittedVotes().map { it.memberId }.distinct() shouldContainExactly listOf(400L)
            }
        }
        When("마감 일시가 지났으면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생하고 기존 응답을 유지한다") {
                stubVote(schedule(), adminRows(closedDeadline))

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                verify(voteRepository, never()).deleteAllByScheduleIdAndMemberId(anyLong(), anyLong())
                verify(voteRepository, never()).saveAllAndFlush(anyList<Vote>())
            }
        }
        When("확정된 일정이면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생한다") {
                stubVote(schedule(ScheduleStatus.CONFIRMED), adminRows(openDeadline))

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                verify(voteRepository, never()).deleteAllByScheduleIdAndMemberId(anyLong(), anyLong())
            }
        }
        When("취소된 일정이면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생한다") {
                stubVote(schedule(ScheduleStatus.CANCELED), adminRows(openDeadline))

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
            }
        }
        When("마감된 투표에 후보가 아닌 시간을 보내면") {
            Then("상태 검사가 먼저라 422 UNPROCESSABLE_ENTITY 예외가 발생한다") {
                stubVote(schedule(), adminRows(closedDeadline))
                val request = MyVoteSubmitRequest(listOf(firstTime.plusHours(1)))

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, request)
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
            }
        }
        When("후보가 아닌 시간을 포함하면") {
            Then("400 VALIDATION_ERROR 예외가 발생하고 기존 응답을 유지한다") {
                stubVote(schedule(), adminRows(openDeadline))
                val request = MyVoteSubmitRequest(listOf(firstTime, firstTime.plusHours(1)))

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, request)
                }.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verify(voteRepository, never()).deleteAllByScheduleIdAndMemberId(anyLong(), anyLong())
                verify(voteRepository, never()).saveAllAndFlush(anyList<Vote>())
            }
        }
        When("표기만 다르고 같은 순간인 시간을 중복으로 보내면") {
            Then("400 VALIDATION_ERROR 예외가 발생한다") {
                stubVote(schedule(), adminRows(openDeadline))
                val request = MyVoteSubmitRequest(listOf(firstTime, firstTime.withOffsetSameInstant(ZoneOffset.UTC)))

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, request)
                }.errorCode shouldBe ErrorCode.VALIDATION_ERROR
            }
        }
        When("같은 구성원의 동시 제출로 응답 unique 제약을 위반하면") {
            Then("409 CONFLICT 예외로 변환한다") {
                stubVote(schedule(), adminRows(openDeadline))
                `when`(voteRepository.saveAllAndFlush(anyList<Vote>()))
                    .thenThrow(integrityViolation(Vote.UK_SCHEDULE_ID_MEMBER_ID_MEETING_TIME))

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                }.errorCode shouldBe ErrorCode.CONFLICT
            }
        }
        When("응답 unique 제약이 아닌 무결성 제약에 걸리면") {
            Then("409로 변환하지 않고 예외를 그대로 전달한다") {
                stubVote(schedule(), adminRows(openDeadline))
                `when`(voteRepository.saveAllAndFlush(anyList<Vote>()))
                    .thenThrow(integrityViolation("fk_vote_member_id"))

                shouldThrow<DataIntegrityViolationException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                }
            }
        }
        When("투표가 없는 일정이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                stubVote(schedule(ScheduleStatus.SCHEDULED), emptyList())

                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, memberUserId, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("모임 구성원이 아니면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    voteService.submitMyVote(meetingId, outsiderUserId, scheduleId, MyVoteSubmitRequest(listOf(firstTime)))
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
    }

    Given("투표 참여 현황을 조회할 때") {
        val openDeadline = OffsetDateTime.now().plusDays(1)
        val memberSubmittedAt = OffsetDateTime.parse("2026-10-24T01:00:00Z")
        val lateSubmittedAt = OffsetDateTime.parse("2026-10-25T01:00:00Z")

        When("ADMIN이 요청하면") {
            Then("조회 시점의 구성원 전체 응답 여부와 마지막 제출 시각을 반환하고, 관리자는 제출 전까지 미응답이다") {
                stubVote(
                    schedule(),
                    adminRows(openDeadline) + listOf(firstTime, secondTime, thirdTime).map {
                        vote(200L, it, VoteStatus.UNAVAILABLE, openDeadline, memberSubmittedAt)
                    } + listOf(firstTime, secondTime, thirdTime).map {
                        vote(400L, it, VoteStatus.AVAILABLE, openDeadline, lateSubmittedAt)
                    }
                )
                `when`(memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId))
                    .thenReturn(listOf(admin, member, quietMember, lateMember))

                val response = voteService.getVoteParticipation(meetingId, adminUserId, scheduleId)

                response.memberCount shouldBe 4L
                response.respondedCount shouldBe 2L
                response.members.map { it.memberId } shouldContainExactly listOf(100L, 200L, 300L, 400L)
                response.members.map { it.responded } shouldContainExactly listOf(false, true, false, true)
                response.members[0].submittedAt.shouldBeNull()
                response.members[1].submittedAt shouldBe memberSubmittedAt.withOffsetSameInstant(kst)
                response.members[2].submittedAt.shouldBeNull()
                response.members[3].submittedAt shouldBe lateSubmittedAt.withOffsetSameInstant(kst)
                response.members.map { it.nickname }.distinct() shouldContainExactly listOf(null)
            }
        }
        When("MEMBER가 요청하면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    voteService.getVoteParticipation(meetingId, memberUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
        When("투표가 없는 일정이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                stubVote(schedule(ScheduleStatus.SCHEDULED), emptyList())

                shouldThrow<ApiException> {
                    voteService.getVoteParticipation(meetingId, adminUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    Given("일정을 확정할 때") {
        val openDeadline = OffsetDateTime.now().plusDays(1)

        When("마감 전에 ADMIN이 후보 시간을 Z 표기로 확정하면") {
            Then("일정 시작 일시와 상태를 CONFIRMED로 바꾸고 투표 응답은 유지한다") {
                val votingSchedule = schedule()
                stubVote(votingSchedule, adminRows(openDeadline))
                val request = VoteConfirmRequest(meetingTime = secondTime.withOffsetSameInstant(ZoneOffset.UTC))

                val response = voteService.confirmVote(meetingId, adminUserId, scheduleId, request)

                verify(scheduleRepository).findByIdAndMeetingIdForUpdate(scheduleId, meetingId)
                votingSchedule.status shouldBe ScheduleStatus.CONFIRMED
                votingSchedule.startsAt.toInstant() shouldBe secondTime.toInstant()
                response.scheduleId shouldBe scheduleId
                response.title shouldBe "11월 정기 모임"
                response.startAt shouldBe secondTime
                response.status shouldBe ScheduleStatus.CONFIRMED
                verify(voteRepository, never()).deleteAllByScheduleIdAndMemberId(anyLong(), anyLong())
            }
        }
        When("이미 확정된 일정이면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생하고 확정 시간을 바꾸지 않는다") {
                val confirmedSchedule = schedule(ScheduleStatus.CONFIRMED)
                stubVote(confirmedSchedule, adminRows(openDeadline))

                shouldThrow<ApiException> {
                    voteService.confirmVote(meetingId, adminUserId, scheduleId, VoteConfirmRequest(thirdTime))
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                confirmedSchedule.startsAt shouldBe firstTime
            }
        }
        When("취소된 일정이면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생한다") {
                stubVote(schedule(ScheduleStatus.CANCELED), adminRows(openDeadline))

                shouldThrow<ApiException> {
                    voteService.confirmVote(meetingId, adminUserId, scheduleId, VoteConfirmRequest(secondTime))
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
            }
        }
        When("후보가 아닌 시간이면") {
            Then("400 VALIDATION_ERROR 예외가 발생하고 일정은 VOTING으로 유지된다") {
                val votingSchedule = schedule()
                stubVote(votingSchedule, adminRows(openDeadline))

                shouldThrow<ApiException> {
                    voteService.confirmVote(meetingId, adminUserId, scheduleId, VoteConfirmRequest(secondTime.plusMinutes(30)))
                }.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                votingSchedule.status shouldBe ScheduleStatus.VOTING
            }
        }
        When("MEMBER가 요청하면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    voteService.confirmVote(meetingId, memberUserId, scheduleId, VoteConfirmRequest(secondTime))
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
        When("투표가 없는 일정이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                stubVote(schedule(ScheduleStatus.SCHEDULED), emptyList())

                shouldThrow<ApiException> {
                    voteService.confirmVote(meetingId, adminUserId, scheduleId, VoteConfirmRequest(secondTime))
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }
})

// Spring이 Hibernate 제약 위반 예외를 감싸는 형태 그대로 생성
private fun integrityViolation(constraintName: String) = DataIntegrityViolationException(
    "could not execute statement",
    ConstraintViolationException("could not execute statement", SQLException(), constraintName)
)

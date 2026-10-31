package com.teamani.moamoa.meeting.service

import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.dto.MeetingCreateRequest
import com.teamani.moamoa.meeting.dto.MeetingJoinRequest
import com.teamani.moamoa.meeting.dto.MeetingUpdateRequest
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MeetingMemberCount
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.vote.repository.VoteRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.util.Optional

class MeetingServiceTests : BehaviorSpec({

    val meetingRepository: MeetingRepository = mock(MeetingRepository::class.java)
    val memberRepository: MemberRepository = mock(MemberRepository::class.java)
    val memberService = MemberService(
        meetingRepository,
        memberRepository,
        mock(VoteRepository::class.java),
        mock(AttendanceRepository::class.java)
    )
    val meetingService = MeetingService(meetingRepository, memberRepository, memberService)

    val meetingId = 1L
    val adminUserId = 10L
    val memberUserId = 20L
    val outsiderUserId = 30L
    val admin = Member(meetingId = meetingId, userId = adminUserId, role = MemberRole.ADMIN, id = 100L)
    val member = Member(meetingId = meetingId, userId = memberUserId, role = MemberRole.MEMBER, id = 200L)
    lateinit var meeting: Meeting

    // 저장 시 PK가 채워진 엔티티 반환
    fun stubMeetingSave() {
        `when`(meetingRepository.saveAndFlush(any(Meeting::class.java))).thenAnswer {
            val saved = it.getArgument<Meeting>(0)
            Meeting(name = saved.name, description = saved.description, inviteCode = saved.inviteCode, id = meetingId)
        }
    }

    fun memberCount(id: Long, count: Long) = object : MeetingMemberCount {
        override val meetingId = id
        override val memberCount = count
    }

    beforeTest {
        reset(meetingRepository, memberRepository)
        meeting = Meeting(name = "모아모아", description = "모임 소개", inviteCode = "A1B2C3D4", id = meetingId)
        `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(true)
        `when`(meetingRepository.findByIdAndEndedAtIsNull(meetingId)).thenReturn(meeting)
        `when`(memberRepository.findByMeetingIdAndUserId(meetingId, adminUserId)).thenReturn(admin)
        `when`(memberRepository.findByMeetingIdAndUserId(meetingId, memberUserId)).thenReturn(member)
    }

    Given("모임 생성 요청이 주어졌을 때") {
        val request = MeetingCreateRequest(name = "모아모아", description = "모임 소개")

        When("모임을 생성하면") {
            Then("모임과 생성자의 ADMIN 구성원 정보를 저장하고 대문자·숫자 8자리 초대코드를 반환한다") {
                stubMeetingSave()

                val response = meetingService.createMeeting(adminUserId, request)

                response.meetingId shouldBe meetingId
                response.name shouldBe "모아모아"
                response.inviteCode shouldMatch Regex("[A-Z0-9]{8}")
                response.myRole shouldBe MemberRole.ADMIN

                val captor = ArgumentCaptor.forClass(Member::class.java)
                verify(memberRepository).save(captor.capture())
                captor.value.meetingId shouldBe meetingId
                captor.value.userId shouldBe adminUserId
                captor.value.role shouldBe MemberRole.ADMIN
            }
        }
        When("생성한 초대코드가 이미 사용 중이면") {
            Then("초대코드를 다시 생성한다") {
                stubMeetingSave()
                `when`(meetingRepository.existsByInviteCode(anyString())).thenReturn(true, false)

                meetingService.createMeeting(adminUserId, request)

                verify(meetingRepository, times(2)).existsByInviteCode(anyString())
            }
        }
        When("중복 확인 직후 동시 요청이 같은 초대코드를 먼저 저장하면") {
            Then("409 CONFLICT 예외로 변환하고 ADMIN 구성원을 저장하지 않는다") {
                `when`(meetingRepository.saveAndFlush(any(Meeting::class.java)))
                    .thenThrow(integrityViolation(Meeting.UK_INVITE_CODE))

                shouldThrow<ApiException> {
                    meetingService.createMeeting(adminUserId, request)
                }.errorCode shouldBe ErrorCode.CONFLICT
                verify(memberRepository, never()).save(any(Member::class.java))
            }
        }
    }

    Given("내 모임 목록을 조회할 때") {
        When("참여한 모임 중 삭제된 모임이 있으면") {
            Then("삭제된 모임을 제외하고 참여 순서대로 구성원 수와 내 역할을 반환한다") {
                val otherMeetingId = 2L
                val endedMeetingId = 3L
                `when`(memberRepository.findAllByUserIdOrderByIdAsc(memberUserId)).thenReturn(
                    listOf(
                        Member(meetingId = otherMeetingId, userId = memberUserId, role = MemberRole.ADMIN, id = 300L),
                        Member(meetingId = endedMeetingId, userId = memberUserId, role = MemberRole.MEMBER, id = 301L),
                        member,
                    )
                )
                `when`(meetingRepository.findAllByIdInAndEndedAtIsNull(listOf(otherMeetingId, endedMeetingId, meetingId)))
                    .thenReturn(listOf(meeting, Meeting(name = "두 번째 모임", inviteCode = "B1B2C3D4", id = otherMeetingId)))
                `when`(memberRepository.countGroupByMeetingIdIn(setOf(meetingId, otherMeetingId)))
                    .thenReturn(listOf(memberCount(meetingId, 2L), memberCount(otherMeetingId, 1L)))

                val response = meetingService.getMyMeetings(memberUserId)

                response.meetings shouldHaveSize 2
                response.meetings[0].meetingId shouldBe otherMeetingId
                response.meetings[0].memberCount shouldBe 1L
                response.meetings[0].myRole shouldBe MemberRole.ADMIN
                response.meetings[1].meetingId shouldBe meetingId
                response.meetings[1].name shouldBe "모아모아"
                response.meetings[1].memberCount shouldBe 2L
                response.meetings[1].myRole shouldBe MemberRole.MEMBER
            }
        }
        When("참여한 모임이 없으면") {
            Then("빈 목록을 반환하고 구성원 수를 조회하지 않는다") {
                meetingService.getMyMeetings(outsiderUserId).meetings.shouldBeEmpty()

                verify(memberRepository, never()).countGroupByMeetingIdIn(anyCollection())
            }
        }
    }

    Given("초대코드로 모임에 참여할 때") {
        val request = MeetingJoinRequest(inviteCode = "A1B2C3D4")

        When("유효한 초대코드이고 아직 참여하지 않았으면") {
            Then("MEMBER로 등록하고 모임 정보를 반환한다") {
                `when`(meetingRepository.findByInviteCodeAndEndedAtIsNull("A1B2C3D4")).thenReturn(meeting)

                val response = meetingService.joinMeeting(outsiderUserId, request)

                response.meetingId shouldBe meetingId
                response.name shouldBe "모아모아"
                response.myRole shouldBe MemberRole.MEMBER

                val captor = ArgumentCaptor.forClass(Member::class.java)
                verify(memberRepository).saveAndFlush(captor.capture())
                captor.value.userId shouldBe outsiderUserId
                captor.value.role shouldBe MemberRole.MEMBER
            }
        }
        When("존재하지 않는 초대코드이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                shouldThrow<ApiException> {
                    meetingService.joinMeeting(outsiderUserId, MeetingJoinRequest(inviteCode = "ZZZZZZZZ"))
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("이미 참여한 모임이면") {
            Then("409 CONFLICT 예외가 발생하고 구성원을 저장하지 않는다") {
                `when`(meetingRepository.findByInviteCodeAndEndedAtIsNull("A1B2C3D4")).thenReturn(meeting)
                `when`(memberRepository.existsByMeetingIdAndUserId(meetingId, memberUserId)).thenReturn(true)

                shouldThrow<ApiException> {
                    meetingService.joinMeeting(memberUserId, request)
                }.errorCode shouldBe ErrorCode.CONFLICT
                verify(memberRepository, never()).saveAndFlush(any(Member::class.java))
            }
        }
        When("중복 확인 직후 동시 요청으로 unique 제약에 걸리면") {
            Then("409 CONFLICT 예외로 변환한다") {
                `when`(meetingRepository.findByInviteCodeAndEndedAtIsNull("A1B2C3D4")).thenReturn(meeting)
                `when`(memberRepository.saveAndFlush(any(Member::class.java)))
                    .thenThrow(integrityViolation(Member.UK_MEETING_ID_USER_ID))

                shouldThrow<ApiException> {
                    meetingService.joinMeeting(outsiderUserId, request)
                }.errorCode shouldBe ErrorCode.CONFLICT
            }
        }
        When("구성원 unique 제약이 아닌 무결성 제약에 걸리면") {
            Then("409로 변환하지 않고 예외를 그대로 전달한다") {
                `when`(meetingRepository.findByInviteCodeAndEndedAtIsNull("A1B2C3D4")).thenReturn(meeting)
                `when`(memberRepository.saveAndFlush(any(Member::class.java)))
                    .thenThrow(integrityViolation("fk_member_user_id"))

                shouldThrow<DataIntegrityViolationException> {
                    meetingService.joinMeeting(outsiderUserId, request)
                }
            }
        }
    }

    Given("모임 상세를 조회할 때") {
        When("모임 구성원이 요청하면") {
            Then("모임 정보와 구성원 수, 내 역할을 반환한다") {
                `when`(memberRepository.countByMeetingId(meetingId)).thenReturn(2L)

                val response = meetingService.getMeeting(meetingId, memberUserId)

                response.meetingId shouldBe meetingId
                response.name shouldBe "모아모아"
                response.description shouldBe "모임 소개"
                response.memberCount shouldBe 2L
                response.myRole shouldBe MemberRole.MEMBER
            }
        }
        When("모임 구성원이 아니면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    meetingService.getMeeting(meetingId, outsiderUserId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
        When("모임이 없거나 삭제되었으면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(false)

                shouldThrow<ApiException> {
                    meetingService.getMeeting(meetingId, memberUserId)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    Given("모임을 수정할 때") {
        When("ADMIN이 name만 보내면") {
            Then("name만 변경하고 description은 유지한다") {
                val response = meetingService.updateMeeting(meetingId, adminUserId, MeetingUpdateRequest(name = Optional.of("새 이름")))

                response.name shouldBe "새 이름"
                response.description shouldBe "모임 소개"
            }
        }
        When("ADMIN이 description에 null을 보내면") {
            Then("description을 비우고 name은 유지한다") {
                val response = meetingService.updateMeeting(
                    meetingId, adminUserId, MeetingUpdateRequest(description = Optional.empty())
                )

                response.name shouldBe "모아모아"
                response.description.shouldBeNull()
                meeting.description.shouldBeNull()
            }
        }
        When("ADMIN이 description 값을 보내면") {
            Then("description을 변경한다") {
                val response = meetingService.updateMeeting(
                    meetingId, adminUserId, MeetingUpdateRequest(description = Optional.of("새 소개"))
                )

                response.description shouldBe "새 소개"
            }
        }
        When("MEMBER가 요청하면") {
            Then("403 FORBIDDEN 예외가 발생하고 모임 정보는 변경되지 않는다") {
                shouldThrow<ApiException> {
                    meetingService.updateMeeting(meetingId, memberUserId, MeetingUpdateRequest(name = Optional.of("새 이름")))
                }.errorCode shouldBe ErrorCode.FORBIDDEN
                meeting.name shouldBe "모아모아"
            }
        }
    }

    Given("모임을 삭제할 때") {
        When("ADMIN이 요청하면") {
            Then("종료 일시를 기록하고 meetingId를 반환한다") {
                val response = meetingService.deleteMeeting(meetingId, adminUserId)

                response.meetingId shouldBe meetingId
                meeting.endedAt.shouldNotBeNull()
            }
        }
        When("MEMBER가 요청하면") {
            Then("403 FORBIDDEN 예외가 발생하고 모임은 삭제되지 않는다") {
                shouldThrow<ApiException> {
                    meetingService.deleteMeeting(meetingId, memberUserId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
                meeting.endedAt.shouldBeNull()
            }
        }
    }

    Given("현재 초대코드를 조회할 때") {
        When("ADMIN이 요청하면") {
            Then("초대코드를 반환한다") {
                meetingService.getInviteCode(meetingId, adminUserId).inviteCode shouldBe "A1B2C3D4"
            }
        }
        When("MEMBER가 요청하면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    meetingService.getInviteCode(meetingId, memberUserId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
    }
})

// Spring이 Hibernate 제약 위반 예외를 감싸는 형태 그대로 생성
private fun integrityViolation(constraintName: String) = DataIntegrityViolationException(
    "could not execute statement",
    ConstraintViolationException("could not execute statement", SQLException(), constraintName)
)

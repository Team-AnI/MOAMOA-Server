package com.teamani.moamoa.member.service

import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.vote.repository.VoteRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

class MemberServiceTests : BehaviorSpec({

    val meetingRepository: MeetingRepository = mock(MeetingRepository::class.java)
    val memberRepository: MemberRepository = mock(MemberRepository::class.java)
    val voteRepository: VoteRepository = mock(VoteRepository::class.java)
    val attendanceRepository: AttendanceRepository = mock(AttendanceRepository::class.java)
    val memberService = MemberService(meetingRepository, memberRepository, voteRepository, attendanceRepository)

    val meetingId = 1L
    val adminUserId = 10L
    val memberUserId = 20L
    val outsiderUserId = 30L
    val admin = Member(meetingId = meetingId, userId = adminUserId, role = MemberRole.ADMIN, id = 100L)
    val member = Member(meetingId = meetingId, userId = memberUserId, role = MemberRole.MEMBER, id = 200L)

    beforeTest {
        reset(meetingRepository, memberRepository, voteRepository, attendanceRepository)
        `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(true)
        `when`(memberRepository.findByMeetingIdAndUserId(meetingId, adminUserId)).thenReturn(admin)
        `when`(memberRepository.findByMeetingIdAndUserId(meetingId, memberUserId)).thenReturn(member)
    }

    Given("모임 구성원 권한을 검증할 때") {
        When("모임이 없거나 삭제되었으면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(false)

                shouldThrow<ApiException> {
                    memberService.validateMember(meetingId, memberUserId)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("모임 구성원이 아니면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    memberService.validateMember(meetingId, outsiderUserId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
        When("모임 구성원이면") {
            Then("구성원 정보를 반환한다") {
                memberService.validateMember(meetingId, memberUserId) shouldBe member
            }
        }
    }

    Given("모임 관리자 권한을 검증할 때") {
        When("구성원이 MEMBER이면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                shouldThrow<ApiException> {
                    memberService.validateAdmin(meetingId, memberUserId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
        When("구성원이 ADMIN이면") {
            Then("구성원 정보를 반환한다") {
                memberService.validateAdmin(meetingId, adminUserId) shouldBe admin
            }
        }
    }

    Given("구성원 목록을 조회할 때") {
        When("모임 구성원이 요청하면") {
            Then("구성원 id·nickname(null)·role 목록을 반환한다") {
                `when`(memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId)).thenReturn(listOf(admin, member))

                val response = memberService.getMembers(meetingId, memberUserId)

                response.members shouldHaveSize 2
                response.members[0].memberId shouldBe 100L
                response.members[0].role shouldBe MemberRole.ADMIN
                response.members[0].nickname.shouldBeNull()
                response.members[1].memberId shouldBe 200L
                response.members[1].role shouldBe MemberRole.MEMBER
            }
        }
    }

    Given("모임을 탈퇴할 때") {
        When("MEMBER가 요청하면") {
            Then("투표 응답과 출석 기록을 먼저 삭제한 뒤 구성원 정보를 삭제하고 meetingId를 반환한다") {
                val response = memberService.leaveMeeting(meetingId, memberUserId)

                response.meetingId shouldBe meetingId
                val order = inOrder(voteRepository, attendanceRepository, memberRepository)
                order.verify(voteRepository).deleteAllByMemberId(200L)
                order.verify(attendanceRepository).deleteAllByMemberId(200L)
                order.verify(memberRepository).delete(member)
            }
        }
        When("ADMIN이 요청하면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생하고 구성원 정보와 투표 응답·출석 기록은 유지된다") {
                shouldThrow<ApiException> {
                    memberService.leaveMeeting(meetingId, adminUserId)
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                verify(voteRepository, never()).deleteAllByMemberId(anyLong())
                verify(attendanceRepository, never()).deleteAllByMemberId(anyLong())
                verify(memberRepository, never()).delete(any(Member::class.java))
            }
        }
    }
})

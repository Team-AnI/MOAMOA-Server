package com.teamani.moamoa.member.service

import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.dto.MemberLeaveResponse
import com.teamani.moamoa.member.dto.MemberListResponse
import com.teamani.moamoa.member.dto.MemberSummaryResponse
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.notice.repository.NoticeReadRepository
import com.teamani.moamoa.vote.repository.VoteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 탈퇴 시 투표 응답·출석·공지 읽음 기록 삭제는 VoteService·AttendanceService·NoticeService가 MemberService를 사용하므로 순환 방지를 위해 각 Repository 직접 사용
@Service
class MemberService(
    private val meetingRepository: MeetingRepository,
    private val memberRepository: MemberRepository,
    private val voteRepository: VoteRepository,
    private val attendanceRepository: AttendanceRepository,
    private val noticeReadRepository: NoticeReadRepository
) {

    // 모임 구성원 권한 검증 (모임 없음 404 → 구성원 아님 403), 다른 도메인에서도 재사용
    @Transactional(readOnly = true)
    fun validateMember(meetingId: Long, userId: Long): Member {
        if (!meetingRepository.existsByIdAndEndedAtIsNull(meetingId)) {
            throw ApiException(ErrorCode.NOT_FOUND, MEETING_NOT_FOUND_MESSAGE)
        }
        return memberRepository.findByMeetingIdAndUserId(meetingId, userId)
            ?: throw ApiException(ErrorCode.FORBIDDEN, "모임 구성원만 요청할 수 있습니다.")
    }

    // validateMember와 같은 검증을 구성원 잠금 조회로 수행 (호출한 트랜잭션이 끝날 때까지 모임 나가기와 순서대로 처리)
    @Transactional
    fun validateMemberForUpdate(meetingId: Long, userId: Long): Member {
        if (!meetingRepository.existsByIdAndEndedAtIsNull(meetingId)) {
            throw ApiException(ErrorCode.NOT_FOUND, MEETING_NOT_FOUND_MESSAGE)
        }
        return memberRepository.findByMeetingIdAndUserIdForUpdate(meetingId, userId)
            ?: throw ApiException(ErrorCode.FORBIDDEN, "모임 구성원만 요청할 수 있습니다.")
    }

    // 모임 관리자 권한 검증 (모임 없음 404 → 구성원 아님·ADMIN 아님 403), 다른 도메인에서도 재사용
    @Transactional(readOnly = true)
    fun validateAdmin(meetingId: Long, userId: Long): Member {
        val member = validateMember(meetingId, userId)
        if (member.role != MemberRole.ADMIN) {
            throw ApiException(ErrorCode.FORBIDDEN, "모임 관리자만 요청할 수 있습니다.")
        }
        return member
    }

    @Transactional(readOnly = true)
    fun getMembers(meetingId: Long, userId: Long): MemberListResponse {
        validateMember(meetingId, userId)

        val members = memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId).map {
            MemberSummaryResponse(
                memberId = checkNotNull(it.id) { "구성원 식별자가 없습니다." },
                userName = null,
                role = it.role
            )
        }
        return MemberListResponse(members = members)
    }

    @Transactional
    fun leaveMeeting(meetingId: Long, userId: Long): MemberLeaveResponse {
        // 기록을 지우기 전에 구성원을 잠가, 처리 중인 공지 읽음 기록까지 지운 뒤 나가도록 함
        val member = validateMemberForUpdate(meetingId, userId)

        // ADMIN 위임 API 전까지 ADMIN은 모임 삭제로만 모임 종료 가능
        if (member.role == MemberRole.ADMIN) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "모임 관리자는 탈퇴할 수 없습니다.")
        }

        // 구성원 삭제 전 같은 트랜잭션에서 투표 응답, 출석 기록, 공지 읽음 기록 먼저 삭제
        val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }
        voteRepository.deleteAllByMemberId(memberId)
        attendanceRepository.deleteAllByMemberId(memberId)
        noticeReadRepository.deleteAllByMemberId(memberId)
        memberRepository.delete(member)

        return MemberLeaveResponse(meetingId = meetingId)
    }

    companion object {
        const val MEETING_NOT_FOUND_MESSAGE = "모임을 찾을 수 없습니다."
    }
}

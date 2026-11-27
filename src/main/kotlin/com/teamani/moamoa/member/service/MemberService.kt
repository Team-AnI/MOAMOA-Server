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
import com.teamani.moamoa.schedule.repository.ScheduleResponseRepository
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.user.entity.ProfileColor
import com.teamani.moamoa.user.repository.AppUserRepository
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
    private val noticeReadRepository: NoticeReadRepository,
    private val appUserRepository: AppUserRepository,
    private val scheduleResponseRepository: ScheduleResponseRepository,
    private val settlementMemberRepository: SettlementMemberRepository
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

        val members = memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId)
        // 구성원마다 사용자를 조회하지 않도록 한 번에 조회
        val usersById = appUserRepository.findAllById(members.map { it.userId }).associateBy { it.id }
        val responses = members.map {
            val user = usersById[it.userId]
            MemberSummaryResponse(
                memberId = checkNotNull(it.id) { "구성원 식별자가 없습니다." },
                userName = user?.name ?: WITHDRAWN_USER_NAME,
                profileColor = user?.profileColor ?: ProfileColor.LIGHT_BLUE,
                role = it.role
            )
        }
        return MemberListResponse(members = responses)
    }

    @Transactional
    fun leaveMeeting(meetingId: Long, userId: Long): MemberLeaveResponse {
        // 기록을 지우기 전에 구성원을 잠가, 처리 중인 공지 읽음 기록까지 지운 뒤 나가도록 함
        val member = validateMemberForUpdate(meetingId, userId)

        // ADMIN 위임 API 전까지 ADMIN은 모임 삭제로만 모임 종료 가능
        if (member.role == MemberRole.ADMIN) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "모임 관리자는 탈퇴할 수 없습니다.")
        }
        // 정산 생성·정기 회비 청구도 같은 구성원 잠금으로 대상을 확인하므로, 잠근 뒤 확인하면 먼저 생긴 미납까지 보고 판단
        if (settlementMemberRepository.existsUnpaidByMemberId(checkNotNull(member.id) { "구성원 식별자가 없습니다." })) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, UNPAID_SETTLEMENT_MESSAGE)
        }

        removeMember(member)

        return MemberLeaveResponse(meetingId = meetingId)
    }

    // 회원 탈퇴(7-9): 참여한 모든 모임에서 나가기 (사용자 잠금을 얻은 뒤 호출, 잠금 순서: 사용자 → 구성원)
    // 종료된 모임은 납부·관리를 할 수 없으므로 유일한 관리자·미납 판단에서 빼고 구성원 행만 정리
    @Transactional
    fun leaveAllMeetings(userId: Long) {
        val members = memberRepository.findAllByUserIdForUpdate(userId)
        if (members.isEmpty()) {
            return
        }
        val activeMeetingIds = meetingRepository.findAllByIdInAndEndedAtIsNull(members.map { it.meetingId })
            .mapNotNull { it.id }
            .toSet()

        members.filter { it.meetingId in activeMeetingIds }.forEach { member ->
            val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }
            if (member.role == MemberRole.ADMIN &&
                !memberRepository.existsByMeetingIdAndRoleAndIdNot(member.meetingId, MemberRole.ADMIN, memberId)
            ) {
                throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "유일한 관리자로 있는 모임이 있어 탈퇴할 수 없습니다.")
            }
            if (settlementMemberRepository.existsUnpaidByMemberId(memberId)) {
                throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, UNPAID_SETTLEMENT_MESSAGE)
            }
        }

        members.forEach { removeMember(it) }
    }

    // 구성원 삭제 전 같은 트랜잭션에서 투표 응답, 출석 기록, 공지 읽음 기록, 일정 참석 응답 먼저 삭제 (정산·납부 기록은 유지)
    private fun removeMember(member: Member) {
        val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }
        voteRepository.deleteAllByMemberId(memberId)
        attendanceRepository.deleteAllByMemberId(memberId)
        noticeReadRepository.deleteAllByMemberId(memberId)
        scheduleResponseRepository.deleteAllByMemberId(memberId)
        memberRepository.delete(member)
    }

    companion object {
        const val MEETING_NOT_FOUND_MESSAGE = "모임을 찾을 수 없습니다."
        private const val UNPAID_SETTLEMENT_MESSAGE = "납부 완료를 제출하지 않은 정산이 있습니다."

        // 사용자 정보가 없는 구성원 표시 이름 (명세 공통 "탈퇴한 사용자")
        private const val WITHDRAWN_USER_NAME = "탈퇴한 사용자"
    }
}

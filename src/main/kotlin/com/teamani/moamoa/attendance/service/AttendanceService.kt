package com.teamani.moamoa.attendance.service

import com.teamani.moamoa.attendance.dto.AttendanceListResponse
import com.teamani.moamoa.attendance.dto.AttendanceMemberResponse
import com.teamani.moamoa.attendance.dto.AttendanceUpdateItemResponse
import com.teamani.moamoa.attendance.dto.AttendanceUpdateRequest
import com.teamani.moamoa.attendance.dto.AttendanceUpdateResponse
import com.teamani.moamoa.attendance.dto.AttendanceViewStatus
import com.teamani.moamoa.attendance.entity.Attendance
import com.teamani.moamoa.attendance.entity.AttendanceStatus
import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class AttendanceService(
    private val memberService: MemberService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val attendanceRepository: AttendanceRepository
) {

    @Transactional
    fun updateAttendances(
        meetingId: Long,
        userId: Long,
        scheduleId: Long,
        request: AttendanceUpdateRequest
    ): AttendanceUpdateResponse {
        memberService.validateAdmin(meetingId, userId)
        val schedule = findSchedule(meetingId, scheduleId)

        val items = requireNotNull(request.attendances) { "출석 목록은 1개 이상이어야 합니다." }
            .map { requireNotNull(it) { "출석 항목은 null일 수 없습니다." } }
        val memberIds = items.map { requireNotNull(it.memberId) { "구성원 ID는 필수입니다." } }

        // 하위 리소스 404 → 상태 422 → 그 외 400 순서로 확인 (README 예외 처리 규칙)
        val meetingMemberIds = memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId)
            .map { checkNotNull(it.id) { "구성원 식별자가 없습니다." } }
            .toSet()
        if (!meetingMemberIds.containsAll(memberIds)) {
            throw ApiException(ErrorCode.NOT_FOUND, "모임 구성원을 찾을 수 없습니다.")
        }

        // 일정 시작 전후와 관계없이 상태로만 판단 (투표 중 일정의 시작 일시는 임시 값)
        if (schedule.status !in CHECKABLE_SCHEDULE_STATUSES) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "취소되었거나 투표 중인 일정은 출석을 처리할 수 없습니다.")
        }

        if (memberIds.toSet().size != memberIds.size) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "같은 구성원을 중복으로 처리할 수 없습니다.")
        }

        // 요청 값은 DTO 검증(@Pattern)을 통과한 저장 상태 이름만 들어옴
        val requested = memberIds.zip(
            items.map { AttendanceStatus.valueOf(requireNotNull(it.status) { "출석 상태는 필수입니다." }) }
        )
        val checkedAt = OffsetDateTime.now()
        val existingByMemberId = attendanceRepository.findAllByScheduleId(scheduleId).associateBy { it.memberId }
        val (existingItems, newItems) = requested.partition { (memberId, _) -> memberId in existingByMemberId }

        // 이미 체크한 구성원은 변경 감지로 상태 변경
        existingItems.forEach { (memberId, status) ->
            existingByMemberId.getValue(memberId).changeStatus(status, checkedAt)
        }

        try {
            attendanceRepository.saveAllAndFlush(
                newItems.map { (memberId, status) ->
                    Attendance(memberId = memberId, scheduleId = scheduleId, status = status, createdAt = checkedAt)
                }
            )
        } catch (ex: DataIntegrityViolationException) {
            // 조회 직후 동시 요청이 같은 구성원의 첫 출석을 먼저 저장한 경우에만 409, 그 외 무결성 오류는 그대로 전달
            if (!ex.violates(Attendance.UK_MEMBER_ID_SCHEDULE_ID)) {
                throw ex
            }
            throw ApiException(ErrorCode.CONFLICT, "출석 처리 중 충돌이 발생했습니다. 다시 시도해 주세요.")
        }

        return AttendanceUpdateResponse(
            scheduleId = scheduleId,
            attendances = requested.map { (memberId, status) ->
                AttendanceUpdateItemResponse(memberId = memberId, status = status)
            }
        )
    }

    @Transactional(readOnly = true)
    fun getAttendances(meetingId: Long, userId: Long, scheduleId: Long): AttendanceListResponse {
        memberService.validateMember(meetingId, userId)
        findSchedule(meetingId, scheduleId)

        val statusByMemberId = attendanceRepository.findAllByScheduleId(scheduleId)
            .associate { it.memberId to it.status }

        // 참석 응답과 관계없이 조회 시점의 구성원 전체 대상 (1-7과 같은 memberId 오름차순)
        val attendances = memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId).map { member ->
            val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }
            AttendanceMemberResponse(
                memberId = memberId,
                nickname = null,
                status = AttendanceViewStatus.from(statusByMemberId[memberId])
            )
        }

        return AttendanceListResponse(scheduleId = scheduleId, attendances = attendances)
    }

    // 다른 모임의 일정도 존재하지 않는 일정으로 처리
    private fun findSchedule(meetingId: Long, scheduleId: Long): Schedule =
        scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다.")

    // 위반한 제약 이름 확인 (PostgreSQL은 제약 이름 그대로, H2는 "public.<이름> INDEX ..." 형태라 포함 여부로 비교)
    private fun DataIntegrityViolationException.violates(constraintName: String): Boolean {
        val violation = cause as? ConstraintViolationException ?: return false
        return violation.constraintName?.contains(constraintName, ignoreCase = true) == true
    }

    companion object {
        // 출석 처리가 가능한 일정 상태 (CANCELED·VOTING은 422)
        private val CHECKABLE_SCHEDULE_STATUSES = setOf(ScheduleStatus.SCHEDULED, ScheduleStatus.CONFIRMED)
    }
}

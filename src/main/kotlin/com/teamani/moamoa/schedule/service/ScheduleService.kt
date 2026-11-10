package com.teamani.moamoa.schedule.service

import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.schedule.dto.ScheduleCreateRequest
import com.teamani.moamoa.schedule.dto.ScheduleCreateResponse
import com.teamani.moamoa.schedule.dto.ScheduleDeleteResponse
import com.teamani.moamoa.schedule.dto.ScheduleDetailResponse
import com.teamani.moamoa.schedule.dto.ScheduleListResponse
import com.teamani.moamoa.schedule.dto.ScheduleSummaryResponse
import com.teamani.moamoa.schedule.dto.ScheduleUpdateRequest
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.vote.repository.VoteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class ScheduleService(
    private val scheduleRepository: ScheduleRepository,
    private val attendanceRepository: AttendanceRepository,
    private val voteRepository: VoteRepository,
    private val settlementRepository: SettlementRepository
) {

    @Transactional
    fun createSchedule(meetingId: Long, userId: Long, request: ScheduleCreateRequest): ScheduleCreateResponse {
        // 모임 존재 여부 및 ADMIN 권한 검증 (Meeting·Member 도메인 연동 후속 이슈 처리 예정)
        validateMeeting(meetingId)
        validateAdmin(meetingId, userId)

        val startsAt = requireNotNull(request.startAt) { "시작 일시는 필수입니다." }
        validatePeriod(startsAt, request.endAt)

        val schedule = Schedule(
            meetingId = meetingId,
            title = requireNotNull(request.title) { "제목은 필수입니다." },
            description = request.description,
            startsAt = startsAt,
            endedAt = request.endAt,
            location = request.location
        )

        val savedSchedule = scheduleRepository.save(schedule)

        return ScheduleCreateResponse(
            scheduleId = checkNotNull(savedSchedule.id) { "일정 식별자가 생성되지 않았습니다." },
            title = savedSchedule.title,
            startAt = savedSchedule.startsAt.withOffsetSameInstant(KST)
        )
    }

    @Transactional(readOnly = true)
    fun getSchedules(meetingId: Long, userId: Long, from: LocalDate, to: LocalDate): ScheduleListResponse {
        validateMeeting(meetingId)
        validateMember(meetingId, userId)

        if (from.isAfter(to)) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "조회 시작일은 종료일보다 이후일 수 없습니다.")
        }

        // from, to 모두 포함하도록 KST 기준 [from 00:00, to 다음 날 00:00) 범위와 겹치는 일정을 조회
        val schedules = scheduleRepository.findAllOverlapping(
            meetingId,
            from.atStartOfDay().atOffset(KST),
            to.plusDays(1).atStartOfDay().atOffset(KST)
        )

        return ScheduleListResponse(
            schedules = schedules.map { schedule ->
                ScheduleSummaryResponse(
                    scheduleId = checkNotNull(schedule.id) { "일정 식별자가 없습니다." },
                    title = schedule.title,
                    startAt = schedule.startsAt.withOffsetSameInstant(KST),
                    endAt = schedule.endedAt?.withOffsetSameInstant(KST),
                    location = schedule.location,
                    status = schedule.status
                )
            }
        )
    }

    @Transactional(readOnly = true)
    fun getSchedule(meetingId: Long, userId: Long, scheduleId: Long): ScheduleDetailResponse {
        validateMeeting(meetingId)
        validateMember(meetingId, userId)

        val schedule = scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다.")

        return toDetailResponse(schedule)
    }

    @Transactional
    fun updateSchedule(
        meetingId: Long,
        userId: Long,
        scheduleId: Long,
        request: ScheduleUpdateRequest
    ): ScheduleDetailResponse {
        validateMeeting(meetingId)
        validateAdmin(meetingId, userId)

        val schedule = scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다.")

        // 시작 일시를 실제로 바꿀 때만 과거 여부 검증 (수정 화면 값을 모두 보내도 지난 일정의 다른 항목은 수정 가능)
        // 오프셋만 다르고 같은 시각일 수 있으므로 isEqual로 비교
        val newStartsAt = request.startAt?.orElse(null)
        if (newStartsAt != null && !newStartsAt.isEqual(schedule.startsAt) && newStartsAt.isBefore(OffsetDateTime.now())) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "시작 일시는 현재 시각 이후여야 합니다.")
        }

        // 한쪽만 보내도 수정 결과 기준으로 종료 일시가 시작 일시보다 이전이면 안 됨
        validatePeriod(
            newStartsAt ?: schedule.startsAt,
            if (request.endAt == null) schedule.endedAt else request.endAt.orElse(null)
        )

        // 변경 감지(Dirty Checking)로 트랜잭션 종료 시 반영
        // 필수 항목의 null 전송은 DTO 검증에서 거부되므로 여기서는 값이 있거나 미전송인 경우만 남음
        schedule.update(
            request.title?.orElse(null),
            request.description,
            newStartsAt,
            request.endAt,
            request.location
        )

        return toDetailResponse(schedule)
    }

    @Transactional
    fun deleteSchedule(meetingId: Long, userId: Long, scheduleId: Long): ScheduleDeleteResponse {
        validateMeeting(meetingId)
        validateAdmin(meetingId, userId)

        // 투표 제출·확정과 같은 일정 잠금으로 조회해, 처리 중인 투표 응답이 삭제 뒤에 남지 않게 함
        val schedule = scheduleRepository.findByIdAndMeetingIdForUpdate(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다.")

        // 같은 트랜잭션에서 연결 기록을 먼저 정리 (출석·투표는 삭제, 정산은 납부 기록이라 연결만 해제)
        attendanceRepository.deleteAllByScheduleId(scheduleId)
        voteRepository.deleteAllByScheduleId(scheduleId)
        settlementRepository.clearScheduleId(scheduleId)
        scheduleRepository.delete(schedule)

        return ScheduleDeleteResponse(scheduleId = scheduleId)
    }

    // 생성·수정 공통: 종료 일시가 있으면 시작 일시보다 이전일 수 없음
    private fun validatePeriod(startsAt: OffsetDateTime, endedAt: OffsetDateTime?) {
        if (endedAt != null && endedAt.isBefore(startsAt)) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "종료 일시는 시작 일시 이후여야 합니다.")
        }
    }

    private fun toDetailResponse(schedule: Schedule): ScheduleDetailResponse =
        ScheduleDetailResponse(
            scheduleId = checkNotNull(schedule.id) { "일정 식별자가 없습니다." },
            title = schedule.title,
            description = schedule.description,
            startAt = schedule.startsAt.withOffsetSameInstant(KST),
            endAt = schedule.endedAt?.withOffsetSameInstant(KST),
            location = schedule.location,
            status = schedule.status
        )

    // 모임 존재 여부 검증 함수 (추후 Meeting 도메인 연동 자리)
    private fun validateMeeting(meetingId: Long) {
        // TODO: meetingRepository로 모임을 조회하고, 없으면 404 NOT_FOUND 예외를 throw하도록 연결
    }

    // 관리자 권한 검증 함수 (추후 Member 도메인 연동 자리)
    private fun validateAdmin(meetingId: Long, userId: Long) {
        // TODO: 해당 사용자가 모임의 ADMIN인지 확인하고, 아니면 403 FORBIDDEN 예외를 throw하도록 연결
    }

    // 모임 구성원 권한 검증 함수 (추후 Member 도메인 연동 자리)
    private fun validateMember(meetingId: Long, userId: Long) {
        // TODO: 해당 사용자가 모임의 구성원인지 확인하고, 아니면 403 FORBIDDEN 예외를 throw하도록 연결
    }

    companion object {
        // API 명세상 응답 일시와 날짜 조회 기준은 KST(+09:00)
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
    }
}

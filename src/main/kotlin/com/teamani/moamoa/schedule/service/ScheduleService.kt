package com.teamani.moamoa.schedule.service

import com.teamani.moamoa.schedule.dto.ScheduleCreateRequest
import com.teamani.moamoa.schedule.dto.ScheduleCreateResponse
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScheduleService(
    private val scheduleRepository: ScheduleRepository
) {

    @Transactional
    fun createSchedule(meetingId: Long, userId: Long, request: ScheduleCreateRequest): ScheduleCreateResponse {
        // 모임 존재 여부 및 ADMIN 권한 검증 (Meeting·Member 도메인 연동 후속 이슈 처리 예정)
        validateMeeting(meetingId)
        validateAdmin(meetingId, userId)

        val schedule = Schedule(
            meetingId = meetingId,
            title = requireNotNull(request.title) { "제목은 필수입니다." },
            description = request.description,
            startsAt = requireNotNull(request.startAt) { "시작 일시는 필수입니다." },
            endedAt = request.endAt,
            location = request.location
        )

        val savedSchedule = scheduleRepository.save(schedule)

        return ScheduleCreateResponse(
            scheduleId = checkNotNull(savedSchedule.id) { "일정 식별자가 생성되지 않았습니다." },
            title = savedSchedule.title,
            startAt = savedSchedule.startsAt
        )
    }

    // 모임 존재 여부 검증 함수 (추후 Meeting 도메인 연동 자리)
    private fun validateMeeting(meetingId: Long) {
        // TODO: meetingRepository로 모임을 조회하고, 없으면 404 NOT_FOUND 예외를 throw하도록 연결
    }

    // 관리자 권한 검증 함수 (추후 Member 도메인 연동 자리)
    private fun validateAdmin(meetingId: Long, userId: Long) {
        // TODO: 해당 사용자가 모임의 ADMIN인지 확인하고, 아니면 403 FORBIDDEN 예외를 throw하도록 연결
    }
}

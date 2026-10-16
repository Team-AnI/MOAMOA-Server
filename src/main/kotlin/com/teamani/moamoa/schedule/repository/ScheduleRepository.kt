package com.teamani.moamoa.schedule.repository

import com.teamani.moamoa.schedule.entity.Schedule
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

interface ScheduleRepository : JpaRepository<Schedule, Long> {

    // 시작 일시가 [startInclusive, endExclusive) 범위인 모임 일정을 시작 일시 순으로 조회
    fun findAllByMeetingIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAsc(
        meetingId: Long,
        startInclusive: OffsetDateTime,
        endExclusive: OffsetDateTime
    ): List<Schedule>

    // 다른 모임의 일정은 조회되지 않도록 모임 ID까지 함께 조건으로 사용
    fun findByIdAndMeetingId(id: Long, meetingId: Long): Schedule?
}

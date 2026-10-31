package com.teamani.moamoa.schedule.repository

import com.teamani.moamoa.schedule.entity.Schedule
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

interface ScheduleRepository : JpaRepository<Schedule, Long> {

    // 조회 범위 [rangeStart, rangeEnd)와 기간이 겹치는 모임 일정을 시작 일시 순으로 조회
    // 여러 날에 걸친 일정도 포함하며, 종료 일시가 없으면 시작 일시만으로 판단
    @Query(
        """
        select s from Schedule s
        where s.meetingId = :meetingId
          and s.startsAt < :rangeEnd
          and coalesce(s.endedAt, s.startsAt) >= :rangeStart
        order by s.startsAt asc
        """
    )
    fun findAllOverlapping(
        meetingId: Long,
        rangeStart: OffsetDateTime,
        rangeEnd: OffsetDateTime
    ): List<Schedule>

    // 다른 모임의 일정은 조회되지 않도록 모임 ID까지 함께 조건으로 사용
    fun findByIdAndMeetingId(id: Long, meetingId: Long): Schedule?

    // 투표 제출·확정을 일정 단위로 직렬화 (트랜잭션이 끝날 때까지 같은 일정의 다른 요청은 대기)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id and s.meetingId = :meetingId")
    fun findByIdAndMeetingIdForUpdate(id: Long, meetingId: Long): Schedule?
}

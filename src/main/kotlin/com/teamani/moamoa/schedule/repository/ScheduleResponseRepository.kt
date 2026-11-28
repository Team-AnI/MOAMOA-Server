package com.teamani.moamoa.schedule.repository

import com.teamani.moamoa.schedule.entity.ScheduleResponse
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ScheduleResponseRepository : JpaRepository<ScheduleResponse, Long> {

    fun findByScheduleIdAndMemberId(scheduleId: Long, memberId: Long): ScheduleResponse?

    fun findAllByScheduleId(scheduleId: Long): List<ScheduleResponse>

    // 일정 삭제 시 해당 일정의 응답 삭제
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ScheduleResponse r where r.scheduleId = :scheduleId")
    fun deleteAllByScheduleId(scheduleId: Long): Int

    // 모임 나가기 시 해당 구성원의 응답 삭제
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ScheduleResponse r where r.memberId = :memberId")
    fun deleteAllByMemberId(memberId: Long): Int
}

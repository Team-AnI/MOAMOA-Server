package com.teamani.moamoa.attendance.repository

import com.teamani.moamoa.attendance.entity.Attendance
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AttendanceRepository : JpaRepository<Attendance, Long> {

    fun findAllByScheduleId(scheduleId: Long): List<Attendance>

    // 모임 탈퇴 시 구성원의 모든 출석 기록 일괄 삭제
    // 실행 전 변경을 반영하고 실행 후 비워 삭제된 엔티티가 남지 않게 함
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Attendance a where a.memberId = :memberId")
    fun deleteAllByMemberId(@Param("memberId") memberId: Long): Int
}

package com.teamani.moamoa.settlement.repository

import com.teamani.moamoa.settlement.entity.RegularDues
import org.springframework.data.jpa.repository.JpaRepository

interface RegularDuesRepository : JpaRepository<RegularDues, Long> {

    // 모임의 정기 회비를 등록순으로 조회
    fun findAllByMeetingIdOrderByIdAsc(meetingId: Long): List<RegularDues>

    // 다른 모임의 정기 회비는 조회되지 않도록 모임 ID까지 함께 조건으로 사용
    fun findByIdAndMeetingId(id: Long, meetingId: Long): RegularDues?
}

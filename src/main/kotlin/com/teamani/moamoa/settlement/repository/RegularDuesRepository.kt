package com.teamani.moamoa.settlement.repository

import com.teamani.moamoa.settlement.entity.RegularDues
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface RegularDuesRepository : JpaRepository<RegularDues, Long> {

    // 모임의 정기 회비를 등록순으로 조회
    fun findAllByMeetingIdOrderByIdAsc(meetingId: Long): List<RegularDues>

    // 다른 모임의 정기 회비는 조회되지 않도록 모임 ID까지 함께 조건으로 사용
    fun findByIdAndMeetingId(id: Long, meetingId: Long): RegularDues?

    // 자동 청구 대상 (비활성화한 정기 회비는 제외)
    fun findAllByIsActiveTrue(): List<RegularDues>

    // 자동 청구·수정·삭제를 정기 회비 단위로 직렬화 (트랜잭션이 끝날 때까지 같은 정기 회비의 다른 요청은 대기)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RegularDues r where r.id = :id")
    fun findByIdForUpdate(id: Long): RegularDues?

    // 수정·삭제용 잠금 조회 (다른 모임의 정기 회비는 조회되지 않음)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RegularDues r where r.id = :id and r.meetingId = :meetingId")
    fun findByIdAndMeetingIdForUpdate(id: Long, meetingId: Long): RegularDues?
}

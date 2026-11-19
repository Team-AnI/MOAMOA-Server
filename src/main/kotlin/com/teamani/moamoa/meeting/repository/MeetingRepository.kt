package com.teamani.moamoa.meeting.repository

import com.teamani.moamoa.meeting.entity.Meeting
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface MeetingRepository : JpaRepository<Meeting, Long> {

    // 삭제(종료)되지 않은 모임 조회
    fun findByIdAndEndedAtIsNull(id: Long): Meeting?

    fun existsByIdAndEndedAtIsNull(id: Long): Boolean

    // 정기 회비 자동 청구와 모임 종료를 모임 단위로 직렬화 (트랜잭션이 끝날 때까지 같은 모임의 종료는 대기)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Meeting m where m.id = :id")
    fun findByIdForUpdate(id: Long): Meeting?

    fun findByInviteCodeAndEndedAtIsNull(inviteCode: String): Meeting?

    fun findAllByIdInAndEndedAtIsNull(ids: Collection<Long>): List<Meeting>

    // 초대코드 중복 확인 (삭제된 모임 포함, DB unique 제약 기준)
    fun existsByInviteCode(inviteCode: String): Boolean
}

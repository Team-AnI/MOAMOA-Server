package com.teamani.moamoa.settlement.repository

import com.teamani.moamoa.settlement.entity.Settlement
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface SettlementRepository : JpaRepository<Settlement, Long> {

    // 모임의 정산을 최신순으로 조회 (생성 일시가 같으면 나중에 만든 정산 먼저)
    fun findAllByMeetingIdOrderByCreatedAtDescIdDesc(meetingId: Long): List<Settlement>

    // 다른 모임의 정산은 조회되지 않도록 모임 ID까지 함께 조건으로 사용
    fun findByIdAndMeetingId(id: Long, meetingId: Long): Settlement?

    // 공지 삭제 전 연결된 정산과의 연결만 해제 (정산·납부 내역은 유지)
    // 벌크 UPDATE는 영속성 컨텍스트를 거치지 않으므로 실행 전 변경을 반영하고 실행 후 비워 조회 값이 어긋나지 않게 함
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Settlement s set s.noticeId = null where s.noticeId = :noticeId")
    fun clearNoticeId(noticeId: Long): Int

    // 납부 제출·승인·반려를 정산 단위로 직렬화 (트랜잭션이 끝날 때까지 같은 정산의 다른 요청은 대기)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id = :id and s.meetingId = :meetingId")
    fun findByIdAndMeetingIdForUpdate(id: Long, meetingId: Long): Settlement?

    // 정기 회비 삭제 전 기존 회차 정산과의 연결만 해제 (정산·납부 내역은 유지)
    // 벌크 UPDATE는 영속성 컨텍스트를 거치지 않으므로 실행 전 변경을 반영하고 실행 후 비워 조회 값이 어긋나지 않게 함
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Settlement s set s.regularDuesId = null where s.regularDuesId = :regularDuesId")
    fun clearRegularDuesId(regularDuesId: Long): Int
}

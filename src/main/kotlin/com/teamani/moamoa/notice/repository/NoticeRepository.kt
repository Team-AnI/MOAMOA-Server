package com.teamani.moamoa.notice.repository

import com.teamani.moamoa.notice.entity.Notice
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface NoticeRepository : JpaRepository<Notice, Long> {
    // 특정 모임의 공지 목록을 페이징하여 조회
    fun findAllByMeetingId(meetingId: Long, pageable: Pageable): Page<Notice>

    // 같은 공지의 읽음 처리를 직렬화 (트랜잭션이 끝날 때까지 같은 공지의 다른 읽음 요청은 대기)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notice n where n.id = :id and n.meetingId = :meetingId")
    fun findByIdAndMeetingIdForUpdate(id: Long, meetingId: Long): Notice?
}

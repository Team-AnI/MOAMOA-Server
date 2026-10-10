package com.teamani.moamoa.notice.repository

import com.teamani.moamoa.notice.entity.Notice
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface NoticeRepository : JpaRepository<Notice, Long> {
    // 특정 모임의 공지 목록을 페이징하여 조회
    fun findAllByMeetingId(meetingId: Long, pageable: Pageable): Page<Notice>
}

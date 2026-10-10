package com.teamani.moamoa.notice.repository

import com.teamani.moamoa.notice.entity.Notice
import org.springframework.data.jpa.repository.JpaRepository

interface NoticeRepository : JpaRepository<Notice, Long>

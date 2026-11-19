package com.teamani.moamoa.notice.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

// 구성원별 공지 읽음 기록 (같은 구성원은 같은 공지에 기록 하나만 가짐)
@Entity
@Table(
    name = "notice_read",
    uniqueConstraints = [UniqueConstraint(columnNames = ["notice_id", "member_id"])]
)
class NoticeRead(
    // 읽은 공지 ID
    @Column(nullable = false)
    val noticeId: Long,

    // 읽은 구성원 ID
    @Column(nullable = false)
    val memberId: Long,

    // 처음 읽은 시각 (다시 읽어도 바뀌지 않음)
    @Column(nullable = false, updatable = false)
    val readAt: OffsetDateTime = OffsetDateTime.now(),

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
)

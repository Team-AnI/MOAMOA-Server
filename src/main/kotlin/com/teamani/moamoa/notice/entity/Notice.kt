package com.teamani.moamoa.notice.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "notices")
class Notice(
    // 공지가 속한 모임 ID
    @Column(nullable = false)
    val meetingId: Long,

    // 공지 작성자(회원) ID
    @Column(nullable = false)
    val authorId: Long,

    // 공지 제목 (최대 50자)
    @Column(nullable = false, length = 50)
    var title: String,

    // 공지 본문 내용
    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    // 등록 일시 (ISO-8601 기준)
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

}
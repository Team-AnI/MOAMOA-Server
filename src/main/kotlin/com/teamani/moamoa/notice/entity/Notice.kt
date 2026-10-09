package com.teamani.moamoa.notice.entity

import jakarta.persistence.*
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
    @Lob
    @Column(nullable = false)
    var content: String,

    // 등록 일시 (ISO-8601 기준)
    @Column(nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {
    // 공지 수정 메서드 (PATCH 요청 대응)
    fun update(title: String?, content: String?) {
        title?.let { this.title = it }
        content?.let { this.content = it }
    }
}
package com.teamani.moamoa.schedule.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "schedule")
class Schedule(
    // 일정이 속한 모임 ID
    @Column(nullable = false)
    val meetingId: Long,

    // 일정명 (최대 50자)
    @Column(nullable = false, length = 50)
    var title: String,

    // 일정 설명 (API 명세상 선택 값이라 NULL 허용)
    @Column(nullable = true, columnDefinition = "text")
    var description: String? = null,

    // 시작 일시 (ERD: starts_at, API: startAt)
    @Column(nullable = false)
    var startsAt: OffsetDateTime,

    // 종료 일시 (ERD: ended_at, API: endAt, NULL 허용)
    @Column(nullable = true)
    var endedAt: OffsetDateTime? = null,

    // 장소 (API 명세상 선택 값이라 NULL 허용)
    @Column(nullable = true)
    var location: String? = null,

    // 일정 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ScheduleStatus = ScheduleStatus.SCHEDULED,

    // 생성 일시
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    // 전달된 값만 변경 (null이면 기존 값 유지)
    fun update(
        title: String?,
        description: String?,
        startsAt: OffsetDateTime?,
        endedAt: OffsetDateTime?,
        location: String?
    ) {
        title?.let { this.title = it }
        description?.let { this.description = it }
        startsAt?.let { this.startsAt = it }
        endedAt?.let { this.endedAt = it }
        location?.let { this.location = it }
    }
}

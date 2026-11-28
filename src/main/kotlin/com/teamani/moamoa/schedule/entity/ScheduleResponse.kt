package com.teamani.moamoa.schedule.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

// 일정 참석 응답 (일정마다 구성원 한 명당 하나)
@Entity
@Table(
    name = "schedule_response",
    uniqueConstraints = [UniqueConstraint(columnNames = ["schedule_id", "member_id"])]
)
class ScheduleResponse(
    // 응답한 일정 ID
    @Column(nullable = false)
    val scheduleId: Long,

    // 응답한 구성원 ID
    @Column(nullable = false)
    val memberId: Long,

    // 응답 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ScheduleResponseStatus,

    // 마지막으로 응답한 일시
    @Column(nullable = false)
    var respondedAt: OffsetDateTime = OffsetDateTime.now(),

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    // 응답 변경
    fun change(status: ScheduleResponseStatus, respondedAt: OffsetDateTime) {
        this.status = status
        this.respondedAt = respondedAt
    }
}

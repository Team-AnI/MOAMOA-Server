package com.teamani.moamoa.attendance.entity

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

// 관리자가 기록한 일정별 구성원의 실제 출석 (참석 응답과 별개)
@Entity
@Table(
    name = "attendance",
    uniqueConstraints = [
        // 같은 일정의 같은 구성원 출석 기록 중복 방지
        UniqueConstraint(name = Attendance.UK_MEMBER_ID_SCHEDULE_ID, columnNames = ["member_id", "schedule_id"]),
    ],
)
class Attendance(
    // 출석 대상 구성원 ID (member PK)
    @Column(nullable = false)
    val memberId: Long,

    // 출석 대상 일정 ID
    @Column(nullable = false)
    val scheduleId: Long,

    // 출석 상태 (PRESENT / LATE / ABSENT)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AttendanceStatus,

    // 처음 체크한 일시
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 마지막으로 상태를 바꾼 일시 (처음 체크 시 created_at과 같음)
    @Column(nullable = false)
    var updatedAt: OffsetDateTime = createdAt,

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    // 상태가 다를 때만 변경하고 변경 일시 기록 (같은 상태면 변경 없음)
    fun changeStatus(status: AttendanceStatus, changedAt: OffsetDateTime) {
        if (this.status == status) {
            return
        }
        this.status = status
        this.updatedAt = changedAt
    }

    companion object {
        // 동시 첫 저장 충돌 판별에 사용하는 unique 제약 이름
        const val UK_MEMBER_ID_SCHEDULE_ID = "uk_attendance_member_id_schedule_id"
    }
}

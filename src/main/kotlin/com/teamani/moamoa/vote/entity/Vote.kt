package com.teamani.moamoa.vote.entity

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

// 구성원 1명이 후보 시간 1개에 낸 응답 (현재 ERD vote 테이블 기준, 테이블 분리는 후속 작업)
@Entity
@Table(
    name = "vote",
    uniqueConstraints = [
        // 같은 구성원이 같은 후보 시간에 응답을 중복 저장하지 않도록 방지 (동시 제출 대비)
        UniqueConstraint(
            name = Vote.UK_SCHEDULE_ID_MEMBER_ID_MEETING_TIME,
            columnNames = ["schedule_id", "member_id", "meeting_time"]
        ),
    ],
)
class Vote(
    // 응답한 구성원 ID (member PK)
    @Column(nullable = false)
    val memberId: Long,

    // 투표 대상 일정 ID
    @Column(nullable = false)
    val scheduleId: Long,

    // 후보 시간
    @Column(nullable = false)
    val meetingTime: OffsetDateTime,

    // 후보 시간에 대한 응답 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: VoteStatus,

    // 응답 저장 일시 (제출마다 행을 다시 저장하므로 마지막 제출 시각)
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 투표 마감 일시 (ERD: ended_at, API: voteDeadlineAt)
    @Column(nullable = false)
    val endedAt: OffsetDateTime,

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    companion object {
        // 동시 제출 충돌 판별에 사용하는 unique 제약 이름
        const val UK_SCHEDULE_ID_MEMBER_ID_MEETING_TIME = "uk_vote_schedule_id_member_id_meeting_time"
    }
}

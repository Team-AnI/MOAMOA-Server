package com.teamani.moamoa.settlement.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(
    name = "settlement",
    // 같은 정기 회비의 같은 주기는 한 번만 청구 (일반 정산은 두 값이 모두 NULL)
    uniqueConstraints = [UniqueConstraint(columnNames = ["regular_dues_id", "billing_period"])]
)
class Settlement(
    // 정산이 속한 모임 ID
    @Column(nullable = false)
    val meetingId: Long,

    // 연결 일정 ID (일정 없이 만든 정산은 NULL)
    @Column(nullable = true)
    val scheduleId: Long? = null,

    // 정기 회비 설정 ID (정기 회비로 자동 생성된 정산만 값이 있음)
    @Column(nullable = true)
    val regularDuesId: Long? = null,

    // 생성한 관리자의 구성원 ID (정기 회비 자동 생성은 NULL)
    @Column(nullable = true)
    val createdBy: Long? = null,

    // 함께 등록된 안내 공지 ID (공지 자동 연동 시에만 값이 있음)
    @Column(nullable = true)
    var noticeId: Long? = null,

    // 정산 제목 (최대 50자)
    @Column(nullable = false, length = 50)
    val title: String,

    // 정산 총금액 (원)
    @Column(nullable = false)
    val totalAmount: Int,

    // 입금받을 계좌 정보
    @Column(nullable = false, length = 20)
    val bankName: String,

    @Column(nullable = false, length = 30)
    val accountNumber: String,

    @Column(nullable = false, length = 20)
    val accountHolder: String,

    // 납부 마감 일시
    @Column(nullable = false)
    val dueAt: OffsetDateTime,

    // 정산 진행 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SettlementStatus = SettlementStatus.IN_PROGRESS,

    // 생성 일시
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 정기 회비 청구 주기 (매월: 해당 월 1일, 매주: 해당 주 시작일)
    @Column(nullable = true)
    val billingPeriod: LocalDate? = null,

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    // 대상자 전원이 완납하면 정산 완료
    fun complete() {
        status = SettlementStatus.COMPLETED
    }
}

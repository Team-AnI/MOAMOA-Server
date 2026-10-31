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
import java.time.OffsetDateTime

// 정산 대상 구성원별 납부 항목 (API의 payment)
@Entity
@Table(
    name = "settlement_member",
    uniqueConstraints = [UniqueConstraint(columnNames = ["settlement_id", "member_id"])]
)
class SettlementMember(
    // 납부 항목이 속한 정산 ID
    @Column(nullable = false)
    val settlementId: Long,

    // 납부 대상 구성원 ID
    @Column(nullable = false)
    val memberId: Long,

    // 이 구성원이 납부할 금액 (1/N 계산 결과를 저장)
    @Column(nullable = false)
    val amount: Int,

    // 납부 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentStatus = PaymentStatus.UNPAID,

    // 구성원이 납부 완료를 제출한 일시
    @Column(nullable = true)
    var submittedAt: OffsetDateTime? = null,

    // 관리자가 납부를 승인한 일시
    @Column(nullable = true)
    var confirmedAt: OffsetDateTime? = null,

    // 관리자의 반려 사유
    @Column(nullable = true)
    var rejectionReason: String? = null,

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    // 납부 완료 제출: 확인 대기로 바꾸고, 재제출이면 이전 반려 사유를 지움
    fun submit(submittedAt: OffsetDateTime) {
        status = PaymentStatus.PENDING
        this.submittedAt = submittedAt
        rejectionReason = null
    }

    // 관리자 승인: 완납 처리
    fun approve(confirmedAt: OffsetDateTime) {
        status = PaymentStatus.PAID
        this.confirmedAt = confirmedAt
    }

    // 관리자 반려: 미납으로 되돌리고 사유만 남김
    fun reject(reason: String?) {
        status = PaymentStatus.UNPAID
        rejectionReason = reason
    }
}

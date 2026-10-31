package com.teamani.moamoa.settlement.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalTime

// 정기 회비 설정 (스케줄러가 이 설정으로 회차마다 정산을 생성)
@Entity
@Table(name = "regular_dues")
class RegularDues(
    // 정기 회비가 속한 모임 ID
    @Column(nullable = false)
    val meetingId: Long,

    // 등록한 관리자의 구성원 ID (자동 발행 공지의 작성자)
    @Column(nullable = false)
    val createdBy: Long,

    // 회비명 (최대 50자)
    @Column(nullable = false, length = 50)
    var title: String,

    // 구성원 1인당 회비 금액 (원)
    @Column(nullable = false)
    var amount: Int,

    // 반복 주기
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var cycleType: CycleType,

    // 요청 요일(MON~SUN) 또는 날짜(1~28, LAST)
    @Column(nullable = false, length = 10)
    var cycleDay: String,

    // 요청 발송 시간 (기본값 09:00)
    @Column(nullable = false)
    var sendTime: LocalTime = DEFAULT_SEND_TIME,

    // 요청일부터 납부 마감일까지의 일수
    @Column(nullable = false)
    var dueDays: Int,

    // 입금받을 계좌 정보
    @Column(nullable = false, length = 20)
    var bankName: String,

    @Column(nullable = false, length = 30)
    var accountNumber: String,

    @Column(nullable = false, length = 20)
    var accountHolder: String,

    // 활성화 여부 (false면 이후 자동 청구 중단)
    @Column(nullable = false)
    var isActive: Boolean = true,

    // 회차마다 납부 안내 공지 자동 발행 여부
    @Column(nullable = false)
    var postNotice: Boolean = false,

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    // 전달된 값만 변경 (null이면 기존 값 유지, 반복 설정 검증은 Service에서 처리)
    fun update(
        title: String?,
        amount: Int?,
        cycleType: CycleType?,
        cycleDay: String?,
        sendTime: LocalTime?,
        dueDays: Int?,
        bankName: String?,
        accountNumber: String?,
        accountHolder: String?,
        postNotice: Boolean?,
        isActive: Boolean?
    ) {
        title?.let { this.title = it }
        amount?.let { this.amount = it }
        cycleType?.let { this.cycleType = it }
        cycleDay?.let { this.cycleDay = it }
        sendTime?.let { this.sendTime = it }
        dueDays?.let { this.dueDays = it }
        bankName?.let { this.bankName = it }
        accountNumber?.let { this.accountNumber = it }
        accountHolder?.let { this.accountHolder = it }
        postNotice?.let { this.postNotice = it }
        isActive?.let { this.isActive = it }
    }

    companion object {
        val DEFAULT_SEND_TIME: LocalTime = LocalTime.of(9, 0)
    }
}

package com.teamani.moamoa.settlement.dto

import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.SettlementStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

// 대상 구성원 중복은 Service에서 검증
data class SettlementCreateRequest(
    val scheduleId: Long? = null,

    @field:NotBlank(message = "정산명은 필수입니다.")
    @field:Size(max = 50, message = "정산명은 50자 이하여야 합니다.")
    val title: String?,

    @field:NotNull(message = "총 정산 금액은 필수입니다.")
    @field:Positive(message = "총 정산 금액은 0보다 커야 합니다.")
    val totalAmount: Int?,

    @field:NotEmpty(message = "정산 대상 구성원은 1명 이상이어야 합니다.")
    val memberIds: List<Long>?,

    @field:NotBlank(message = "은행명은 필수입니다.")
    @field:Size(max = 20, message = "은행명은 20자 이하여야 합니다.")
    val bankName: String?,

    @field:NotBlank(message = "계좌번호는 필수입니다.")
    @field:Size(max = 30, message = "계좌번호는 30자 이하여야 합니다.")
    val accountNumber: String?,

    @field:NotBlank(message = "예금주는 필수입니다.")
    @field:Size(max = 20, message = "예금주는 20자 이하여야 합니다.")
    val accountHolder: String?,

    @field:NotNull(message = "납부 기한은 필수입니다.")
    val dueAt: OffsetDateTime?,

    // 정산 안내 공지 자동 등록 여부 (보내지 않으면 false)
    val postNotice: Boolean? = null
)

data class SettlementCreateResponse(
    val settlementId: Long,
    val title: String,
    val totalAmount: Int,
    val memberCount: Int,
    val status: SettlementStatus,
    // 공지 자동 연동(#47) 전까지는 항상 null
    val noticeId: Long?
)

data class SettlementListResponse(
    val settlements: List<SettlementSummaryResponse>
)

// 대상자가 아닌 구성원은 my* 값이 null
data class SettlementSummaryResponse(
    val settlementId: Long,
    val title: String,
    val scheduleId: Long?,
    val totalAmount: Int,
    val memberCount: Int,
    val dueAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val status: SettlementStatus,
    val myAmount: Int?,
    val myPaymentStatus: PaymentStatus?,
    val myConfirmedAt: OffsetDateTime?
)

data class SettlementDetailResponse(
    val settlementId: Long,
    val title: String,
    val scheduleId: Long?,
    val totalAmount: Int,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
    val dueAt: OffsetDateTime,
    val status: SettlementStatus,
    val noticeId: Long?,
    // 대상자가 아니면 null
    val myPayment: MyPaymentResponse?,
    val summary: PaymentSummaryResponse,
    // ADMIN에게만 반환, MEMBER는 null
    val payments: List<PaymentResponse>?
)

data class MyPaymentResponse(
    val paymentId: Long,
    val amount: Int,
    val status: PaymentStatus
)

data class PaymentSummaryResponse(
    val totalCount: Int,
    val unpaidCount: Int,
    val pendingCount: Int,
    val paidCount: Int,
    val paidAmount: Int
)

data class PaymentSubmitResponse(
    val paymentId: Long,
    val status: PaymentStatus,
    val submittedAt: OffsetDateTime
)

data class PaymentApproveResponse(
    val paymentId: Long,
    val status: PaymentStatus,
    val confirmedAt: OffsetDateTime
)

// 반려 사유는 선택 입력, 요청 본문 없이 반려해도 됨
data class PaymentRejectRequest(
    @field:Size(max = 255, message = "반려 사유는 255자 이하여야 합니다.")
    val reason: String? = null
)

data class PaymentRejectResponse(
    val paymentId: Long,
    val status: PaymentStatus,
    val rejectionReason: String?
)

data class PaymentResponse(
    val paymentId: Long,
    val memberId: Long,
    // User 도메인 연동 전까지 null
    val userName: String?,
    val amount: Int,
    val status: PaymentStatus,
    val submittedAt: OffsetDateTime?
)

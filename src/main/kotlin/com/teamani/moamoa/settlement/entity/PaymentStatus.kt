package com.teamani.moamoa.settlement.entity

// 구성원별 납부 상태: 미납 → 확인 대기(납부 완료 제출) → 완납(관리자 승인), 반려 시 미납으로 복귀
// 취소된 정산의 완납은 돈을 돌려준 뒤 환불됨(최종 상태)
enum class PaymentStatus {
    UNPAID,
    PENDING,
    PAID,
    REFUNDED
}

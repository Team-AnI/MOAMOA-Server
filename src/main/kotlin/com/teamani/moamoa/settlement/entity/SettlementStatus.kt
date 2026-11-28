package com.teamani.moamoa.settlement.entity

// 정산 진행 상태 (대상자 전원이 PAID가 되면 COMPLETED, 관리자가 취소하면 CANCELED)
enum class SettlementStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELED
}

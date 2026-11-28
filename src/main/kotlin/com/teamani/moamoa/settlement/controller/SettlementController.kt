package com.teamani.moamoa.settlement.controller

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.settlement.dto.PaymentApproveResponse
import com.teamani.moamoa.settlement.dto.PaymentRefundResponse
import com.teamani.moamoa.settlement.dto.PaymentRejectRequest
import com.teamani.moamoa.settlement.dto.PaymentRejectResponse
import com.teamani.moamoa.settlement.dto.PaymentSubmitResponse
import com.teamani.moamoa.settlement.dto.SettlementCancelRequest
import com.teamani.moamoa.settlement.dto.SettlementCancelResponse
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.dto.SettlementCreateResponse
import com.teamani.moamoa.settlement.dto.SettlementDetailResponse
import com.teamani.moamoa.settlement.dto.SettlementListResponse
import com.teamani.moamoa.settlement.service.SettlementService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/meetings/{meetingId}/settlements")
class SettlementController(
    private val settlementService: SettlementService
) {

    @PostMapping
    fun createSettlement(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: SettlementCreateRequest
    ): ResponseEntity<CommonResponse<SettlementCreateResponse>> {
        val response = settlementService.createSettlement(meetingId, currentUserId, request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }

    @GetMapping
    fun getSettlements(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long
    ): ResponseEntity<CommonResponse<SettlementListResponse>> {
        return ResponseEntity.ok(CommonResponse(data = settlementService.getSettlements(meetingId, currentUserId)))
    }

    @GetMapping("/{settlementId}")
    fun getSettlement(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable settlementId: Long
    ): ResponseEntity<CommonResponse<SettlementDetailResponse>> {
        return ResponseEntity.ok(
            CommonResponse(data = settlementService.getSettlement(meetingId, currentUserId, settlementId))
        )
    }

    @PostMapping("/{settlementId}/payments/me/submissions")
    fun submitPayment(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable settlementId: Long
    ): ResponseEntity<CommonResponse<PaymentSubmitResponse>> {
        return ResponseEntity.ok(
            CommonResponse(data = settlementService.submitPayment(meetingId, currentUserId, settlementId))
        )
    }

    @PostMapping("/{settlementId}/payments/{paymentId}/approval")
    fun approvePayment(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable settlementId: Long,
        @PathVariable paymentId: Long
    ): ResponseEntity<CommonResponse<PaymentApproveResponse>> {
        return ResponseEntity.ok(
            CommonResponse(data = settlementService.approvePayment(meetingId, currentUserId, settlementId, paymentId))
        )
    }

    // 반려 사유는 선택 입력이라 요청 본문 없이도 반려 가능
    @PostMapping("/{settlementId}/payments/{paymentId}/rejection")
    fun rejectPayment(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable settlementId: Long,
        @PathVariable paymentId: Long,
        @Valid @RequestBody(required = false) request: PaymentRejectRequest?
    ): ResponseEntity<CommonResponse<PaymentRejectResponse>> {
        val response = settlementService.rejectPayment(meetingId, currentUserId, settlementId, paymentId, request?.reason)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    // 취소 사유는 선택 입력이라 요청 본문 없이도 취소 가능
    @PostMapping("/{settlementId}/cancellation")
    fun cancelSettlement(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable settlementId: Long,
        @Valid @RequestBody(required = false) request: SettlementCancelRequest?
    ): ResponseEntity<CommonResponse<SettlementCancelResponse>> {
        val response = settlementService.cancelSettlement(meetingId, currentUserId, settlementId, request?.reason)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @PostMapping("/{settlementId}/payments/{paymentId}/refund")
    fun refundPayment(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable settlementId: Long,
        @PathVariable paymentId: Long
    ): ResponseEntity<CommonResponse<PaymentRefundResponse>> {
        return ResponseEntity.ok(
            CommonResponse(data = settlementService.refundPayment(meetingId, currentUserId, settlementId, paymentId))
        )
    }
}

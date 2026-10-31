package com.teamani.moamoa.settlement.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.settlement.dto.MyPaymentResponse
import com.teamani.moamoa.settlement.dto.PaymentApproveResponse
import com.teamani.moamoa.settlement.dto.PaymentRejectResponse
import com.teamani.moamoa.settlement.dto.PaymentResponse
import com.teamani.moamoa.settlement.dto.PaymentSubmitResponse
import com.teamani.moamoa.settlement.dto.PaymentSummaryResponse
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.dto.SettlementCreateResponse
import com.teamani.moamoa.settlement.dto.SettlementDetailResponse
import com.teamani.moamoa.settlement.dto.SettlementListResponse
import com.teamani.moamoa.settlement.dto.SettlementSummaryResponse
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.entity.SettlementMember
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Service
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val settlementMemberRepository: SettlementMemberRepository,
    private val noticeService: NoticeService
) {

    @Transactional
    fun createSettlement(meetingId: Long, memberId: Long, request: SettlementCreateRequest): SettlementCreateResponse {
        // 모임 존재 여부 및 ADMIN 권한 검증 (Member 도메인 연동 후속 이슈 #50 처리 예정)
        validateMeeting(meetingId)
        validateAdmin(meetingId, memberId)

        val memberIds = requireNotNull(request.memberIds) { "정산 대상 구성원은 필수입니다." }
        // DTO 검증을 거치지 않는 내부 호출(정기 회비 자동 생성 등)에서도 0으로 나누지 않도록 확인
        if (memberIds.isEmpty()) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "정산 대상 구성원은 1명 이상이어야 합니다.")
        }
        if (memberIds.toSet().size != memberIds.size) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "정산 대상 구성원이 중복되었습니다.")
        }
        val totalAmount = requireNotNull(request.totalAmount) { "총 정산 금액은 필수입니다." }
        // 1인당 금액이 0원이 되지 않도록 총액은 대상 인원수 이상이어야 함
        if (totalAmount < memberIds.size) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "총 정산 금액은 대상 인원수 이상이어야 합니다.")
        }
        validateTargetMembers(meetingId, memberIds)
        validateSchedule(meetingId, request.scheduleId)

        val amounts = splitAmount(totalAmount, memberIds, memberId)

        val settlement = settlementRepository.save(
            Settlement(
                meetingId = meetingId,
                scheduleId = request.scheduleId,
                createdBy = memberId,
                title = requireNotNull(request.title) { "정산명은 필수입니다." },
                totalAmount = totalAmount,
                bankName = requireNotNull(request.bankName) { "은행명은 필수입니다." },
                accountNumber = requireNotNull(request.accountNumber) { "계좌번호는 필수입니다." },
                accountHolder = requireNotNull(request.accountHolder) { "예금주는 필수입니다." },
                dueAt = requireNotNull(request.dueAt) { "납부 기한은 필수입니다." }
            )
        )
        val settlementId = checkNotNull(settlement.id) { "정산 식별자가 생성되지 않았습니다." }

        settlementMemberRepository.saveAll(
            memberIds.map { SettlementMember(settlementId = settlementId, memberId = it, amount = amounts.getValue(it)) }
        )

        // 공지 생성에 실패하면 같은 트랜잭션이라 정산도 함께 롤백됨
        if (request.postNotice == true) {
            val notice = noticeService.createNotice(
                meetingId,
                memberId,
                settlementNotice(settlement, totalAmount / memberIds.size)
            )
            settlement.noticeId = notice.noticeId
        }

        // TODO: 푸시 알림 - 정산 대상 구성원에게 정산 요청 알림 발송

        return SettlementCreateResponse(
            settlementId = settlementId,
            title = settlement.title,
            totalAmount = settlement.totalAmount,
            memberCount = memberIds.size,
            status = settlement.status,
            noticeId = settlement.noticeId
        )
    }

    @Transactional(readOnly = true)
    fun getSettlements(meetingId: Long, memberId: Long): SettlementListResponse {
        validateMeeting(meetingId)
        validateMember(meetingId, memberId)

        val settlements = settlementRepository.findAllByMeetingIdOrderByCreatedAtDescIdDesc(meetingId)
        // 정산이 없으면 납부 항목을 조회하지 않음
        if (settlements.isEmpty()) {
            return SettlementListResponse(settlements = emptyList())
        }
        val paymentsBySettlement = settlementMemberRepository
            .findAllBySettlementIdIn(settlements.mapNotNull { it.id })
            .groupBy { it.settlementId }

        return SettlementListResponse(
            settlements = settlements.map { settlement ->
                val settlementId = checkNotNull(settlement.id) { "정산 식별자가 없습니다." }
                val payments = paymentsBySettlement[settlementId].orEmpty()
                val myPayment = payments.find { it.memberId == memberId }
                SettlementSummaryResponse(
                    settlementId = settlementId,
                    title = settlement.title,
                    scheduleId = settlement.scheduleId,
                    totalAmount = settlement.totalAmount,
                    memberCount = payments.size,
                    dueAt = settlement.dueAt.withOffsetSameInstant(KST),
                    createdAt = settlement.createdAt.withOffsetSameInstant(KST),
                    status = settlement.status,
                    myAmount = myPayment?.amount,
                    myPaymentStatus = myPayment?.status,
                    myConfirmedAt = myPayment?.confirmedAt?.withOffsetSameInstant(KST)
                )
            }
        )
    }

    @Transactional(readOnly = true)
    fun getSettlement(meetingId: Long, memberId: Long, settlementId: Long): SettlementDetailResponse {
        validateMeeting(meetingId)
        validateMember(meetingId, memberId)

        val settlement = settlementRepository.findByIdAndMeetingId(settlementId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "정산을 찾을 수 없습니다.")
        val payments = settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(settlementId)
        val myPayment = payments.find { it.memberId == memberId }

        return SettlementDetailResponse(
            settlementId = settlementId,
            title = settlement.title,
            scheduleId = settlement.scheduleId,
            totalAmount = settlement.totalAmount,
            bankName = settlement.bankName,
            accountNumber = settlement.accountNumber,
            accountHolder = settlement.accountHolder,
            dueAt = settlement.dueAt.withOffsetSameInstant(KST),
            status = settlement.status,
            noticeId = settlement.noticeId,
            myPayment = myPayment?.let {
                MyPaymentResponse(paymentId = checkNotNull(it.id) { "납부 항목 식별자가 없습니다." }, amount = it.amount, status = it.status)
            },
            summary = PaymentSummaryResponse(
                totalCount = payments.size,
                unpaidCount = payments.count { it.status == PaymentStatus.UNPAID },
                pendingCount = payments.count { it.status == PaymentStatus.PENDING },
                paidCount = payments.count { it.status == PaymentStatus.PAID },
                paidAmount = payments.filter { it.status == PaymentStatus.PAID }.sumOf { it.amount }
            ),
            // 구성원 전원의 납부 현황은 ADMIN에게만 제공
            payments = if (isAdmin(meetingId, memberId)) payments.map { toPaymentResponse(it) } else null
        )
    }

    @Transactional
    fun submitPayment(meetingId: Long, memberId: Long, settlementId: Long): PaymentSubmitResponse {
        validateMeeting(meetingId)
        validateMember(meetingId, memberId)

        lockSettlement(meetingId, settlementId)
        val payment = settlementMemberRepository.findBySettlementIdAndMemberId(settlementId, memberId)
            ?: throw ApiException(ErrorCode.FORBIDDEN, "정산 대상 구성원만 납부 완료를 제출할 수 있습니다.")
        if (payment.status != PaymentStatus.UNPAID) {
            throw ApiException(ErrorCode.CONFLICT, "이미 납부 완료를 제출했거나 완납한 정산입니다.")
        }

        // 변경 감지(Dirty Checking)로 트랜잭션 종료 시 반영
        val submittedAt = OffsetDateTime.now()
        payment.submit(submittedAt)

        return PaymentSubmitResponse(
            paymentId = checkNotNull(payment.id) { "납부 항목 식별자가 없습니다." },
            status = payment.status,
            submittedAt = submittedAt.withOffsetSameInstant(KST)
        )
    }

    @Transactional
    fun approvePayment(meetingId: Long, memberId: Long, settlementId: Long, paymentId: Long): PaymentApproveResponse {
        validateMeeting(meetingId)
        validateAdmin(meetingId, memberId)

        val settlement = lockSettlement(meetingId, settlementId)
        val payment = findPendingPayment(settlementId, paymentId, "확인 대기 상태의 납부만 승인할 수 있습니다.")

        val confirmedAt = OffsetDateTime.now()
        payment.approve(confirmedAt)

        // 대상자 전원이 완납하면 정산 완료
        val payments = settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(settlementId)
        if (payments.all { it.status == PaymentStatus.PAID }) {
            settlement.complete()
        }

        return PaymentApproveResponse(
            paymentId = paymentId,
            status = payment.status,
            confirmedAt = confirmedAt.withOffsetSameInstant(KST)
        )
    }

    @Transactional
    fun rejectPayment(
        meetingId: Long,
        memberId: Long,
        settlementId: Long,
        paymentId: Long,
        reason: String?
    ): PaymentRejectResponse {
        validateMeeting(meetingId)
        validateAdmin(meetingId, memberId)

        lockSettlement(meetingId, settlementId)
        val payment = findPendingPayment(settlementId, paymentId, "확인 대기 상태의 납부만 반려할 수 있습니다.")

        payment.reject(reason)

        // TODO: 푸시 알림 - 해당 구성원에게 반려 안내 알림 발송

        return PaymentRejectResponse(
            paymentId = paymentId,
            status = payment.status,
            rejectionReason = payment.rejectionReason
        )
    }

    // 납부 상태를 확인하기 전에 정산을 잠가, 먼저 끝난 요청의 결과를 보고 판단하도록 함 (동시 승인·중복 처리 방지)
    private fun lockSettlement(meetingId: Long, settlementId: Long): Settlement =
        settlementRepository.findByIdAndMeetingIdForUpdate(settlementId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "정산을 찾을 수 없습니다.")

    // 승인·반려는 확인 대기(PENDING) 상태의 납부 항목만 가능
    private fun findPendingPayment(settlementId: Long, paymentId: Long, conflictMessage: String): SettlementMember {
        val payment = settlementMemberRepository.findByIdAndSettlementId(paymentId, settlementId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "납부 항목을 찾을 수 없습니다.")
        if (payment.status != PaymentStatus.PENDING) {
            throw ApiException(ErrorCode.CONFLICT, conflictMessage)
        }
        return payment
    }

    private fun toPaymentResponse(payment: SettlementMember): PaymentResponse =
        PaymentResponse(
            paymentId = checkNotNull(payment.id) { "납부 항목 식별자가 없습니다." },
            memberId = payment.memberId,
            userName = null, // TODO: User 도메인 연동 시 app_user.name으로 채움
            amount = payment.amount,
            status = payment.status,
            submittedAt = payment.submittedAt?.withOffsetSameInstant(KST)
        )

    // 모임 존재 여부 검증 함수 (추후 Meeting 도메인 연동 자리, #50)
    private fun validateMeeting(meetingId: Long) {
        // TODO: 모임이 없거나 삭제되었으면 404 NOT_FOUND
    }

    // 관리자 권한 검증 함수 (추후 Member 도메인 연동 자리, #50)
    private fun validateAdmin(meetingId: Long, memberId: Long) {
        // TODO: 모임의 ADMIN이 아니면 403 FORBIDDEN
    }

    // 모임 구성원 권한 검증 함수 (추후 Member 도메인 연동 자리, #50)
    private fun validateMember(meetingId: Long, memberId: Long) {
        // TODO: 모임 구성원이 아니면 403 FORBIDDEN
    }

    // 관리자 여부 판단 (추후 Member 도메인 연동 자리, #50)
    private fun isAdmin(meetingId: Long, memberId: Long): Boolean {
        // TODO: 구성원의 role이 ADMIN인지 확인. 연동 전까지 임시 사용자는 관리자로 간주
        return true
    }

    // 정산 대상 검증 함수 (추후 Member 도메인 연동 자리, #50)
    private fun validateTargetMembers(meetingId: Long, memberIds: List<Long>) {
        // TODO: 대상 구성원이 모두 해당 모임 구성원이 아니면 400 VALIDATION_ERROR
    }

    // 연결 일정 검증 함수 (추후 일정 PR #38 머지 후 연동 자리, #50)
    private fun validateSchedule(meetingId: Long, scheduleId: Long?) {
        // TODO: scheduleId가 있는데 해당 모임의 일정이 아니면 404 NOT_FOUND
    }

    // 정산 안내 공지: 제목은 공지 제목 최대 길이를 넘으면 잘라 쓰고, 내용에는 정산명 전체를 담음
    private fun settlementNotice(settlement: Settlement, amountPerMember: Int) = NoticeCreateRequest(
        title = "[정산] ${settlement.title}".take(NOTICE_TITLE_MAX_LENGTH),
        content = "정산명: ${settlement.title}\n" +
            "기본 1인당 금액: ${"%,d".format(Locale.KOREA, amountPerMember)}원\n" +
            "입금 계좌: ${settlement.bankName} ${settlement.accountNumber} (${settlement.accountHolder})\n" +
            "납부 기한: ${settlement.dueAt.withOffsetSameInstant(KST).format(NOTICE_DUE_AT_FORMAT)}\n" +
            "개인별 실제 납부 금액은 정산 상세에서 확인해 주세요."
    )

    companion object {
        // API 명세상 응답 일시는 KST(+09:00)
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
        private const val NOTICE_TITLE_MAX_LENGTH = 50
        private val NOTICE_DUE_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

// 1/N 계산: 원 단위 미만은 절사하고, 나누어떨어지지 않는 잔여 금액은 생성자가 부담
// 생성자가 대상이 아니면 대상 중 가입순 첫 번째(구성원 ID가 가장 작은) 구성원이 부담
internal fun splitAmount(totalAmount: Int, memberIds: List<Long>, creatorMemberId: Long): Map<Long, Int> {
    val baseAmount = totalAmount / memberIds.size
    val remainder = totalAmount % memberIds.size
    val payer = if (creatorMemberId in memberIds) creatorMemberId else memberIds.min()
    return memberIds.associateWith { if (it == payer) baseAmount + remainder else baseAmount }
}

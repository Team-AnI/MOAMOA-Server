package com.teamani.moamoa.settlement.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.settlement.dto.MyPaymentResponse
import com.teamani.moamoa.settlement.dto.PaymentApproveResponse
import com.teamani.moamoa.settlement.dto.PaymentRefundResponse
import com.teamani.moamoa.settlement.dto.PaymentRejectResponse
import com.teamani.moamoa.settlement.dto.PaymentResponse
import com.teamani.moamoa.settlement.dto.PaymentSubmitResponse
import com.teamani.moamoa.settlement.dto.PaymentSummaryResponse
import com.teamani.moamoa.settlement.dto.SettlementCancelResponse
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.dto.SettlementCreateResponse
import com.teamani.moamoa.settlement.dto.SettlementDetailResponse
import com.teamani.moamoa.settlement.dto.SettlementListResponse
import com.teamani.moamoa.settlement.dto.SettlementSummaryResponse
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.entity.SettlementMember
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.user.repository.AppUserRepository
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
    private val noticeService: NoticeService,
    private val memberService: MemberService,
    private val memberRepository: MemberRepository,
    private val appUserRepository: AppUserRepository,
    private val scheduleRepository: ScheduleRepository
) {

    @Transactional
    fun createSettlement(meetingId: Long, userId: Long, request: SettlementCreateRequest): SettlementCreateResponse {
        // 요청만으로 판단할 수 있는 오류(400)를 권한 검증보다 먼저 확인 (명세 공통 오류 순서)
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

        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403, 생성자·공지 작성자는 요청한 관리자의 구성원 ID
        val memberId = checkNotNull(memberService.validateAdmin(meetingId, userId).id) { "구성원 식별자가 없습니다." }
        validateTargetMembers(meetingId, memberIds)
        validateSchedule(meetingId, request.scheduleId)
        request.replacesSettlementId?.let { validateReplacedSettlement(meetingId, it) }

        val amounts = splitAmount(totalAmount, memberIds, memberId)

        val settlement = settlementRepository.save(
            Settlement(
                meetingId = meetingId,
                scheduleId = request.scheduleId,
                replacesSettlementId = request.replacesSettlementId,
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
            val notice = noticeService.createNoticeByMember(
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
    fun getSettlements(meetingId: Long, userId: Long): SettlementListResponse {
        // 모임 없음·종료 404 → 구성원 아님 403
        val memberId = checkNotNull(memberService.validateMember(meetingId, userId).id) { "구성원 식별자가 없습니다." }

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
                    canceledAt = settlement.canceledAt?.withOffsetSameInstant(KST),
                    myAmount = myPayment?.amount,
                    myPaymentStatus = myPayment?.status,
                    myConfirmedAt = myPayment?.confirmedAt?.withOffsetSameInstant(KST)
                )
            }
        )
    }

    @Transactional(readOnly = true)
    fun getSettlement(meetingId: Long, userId: Long, settlementId: Long): SettlementDetailResponse {
        // 모임 없음·종료 404 → 구성원 아님 403
        val member = memberService.validateMember(meetingId, userId)
        val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }

        val settlement = settlementRepository.findByIdAndMeetingId(settlementId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "정산을 찾을 수 없습니다.")
        val payments = settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(settlementId)
        val myPayment = payments.find { it.memberId == memberId }
        // 일정 삭제 시 정산의 일정 연결이 해제되므로 연결된 일정은 항상 존재
        val schedule = settlement.scheduleId?.let { scheduleRepository.findByIdAndMeetingId(it, meetingId) }

        return SettlementDetailResponse(
            settlementId = settlementId,
            title = settlement.title,
            scheduleId = settlement.scheduleId,
            scheduleTitle = schedule?.title,
            scheduleStartAt = schedule?.startsAt?.withOffsetSameInstant(KST),
            totalAmount = settlement.totalAmount,
            bankName = settlement.bankName,
            accountNumber = settlement.accountNumber,
            accountHolder = settlement.accountHolder,
            dueAt = settlement.dueAt.withOffsetSameInstant(KST),
            status = settlement.status,
            noticeId = settlement.noticeId,
            canceledAt = settlement.canceledAt?.withOffsetSameInstant(KST),
            cancelReason = settlement.cancelReason,
            replacesSettlementId = settlement.replacesSettlementId,
            replacedBySettlementId = settlementRepository.findByReplacesSettlementId(settlementId)?.id,
            myPayment = myPayment?.let {
                MyPaymentResponse(paymentId = checkNotNull(it.id) { "납부 항목 식별자가 없습니다." }, amount = it.amount, status = it.status)
            },
            summary = PaymentSummaryResponse(
                totalCount = payments.size,
                unpaidCount = payments.count { it.status == PaymentStatus.UNPAID },
                pendingCount = payments.count { it.status == PaymentStatus.PENDING },
                paidCount = payments.count { it.status == PaymentStatus.PAID },
                refundedCount = payments.count { it.status == PaymentStatus.REFUNDED },
                paidAmount = payments.filter { it.status == PaymentStatus.PAID }.sumOf { it.amount }
            ),
            // 구성원 전원의 납부 현황은 ADMIN에게만 제공
            payments = if (member.role == MemberRole.ADMIN) toPaymentResponses(meetingId, payments) else null
        )
    }

    @Transactional
    fun submitPayment(meetingId: Long, userId: Long, settlementId: Long): PaymentSubmitResponse {
        // 모임 없음·종료 404 → 구성원 아님 403
        val memberId = checkNotNull(memberService.validateMember(meetingId, userId).id) { "구성원 식별자가 없습니다." }

        val settlement = lockSettlement(meetingId, settlementId)
        val payment = settlementMemberRepository.findBySettlementIdAndMemberId(settlementId, memberId)
            ?: throw ApiException(ErrorCode.FORBIDDEN, "정산 대상 구성원만 납부 완료를 제출할 수 있습니다.")
        requireNotCanceled(settlement)
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
    fun approvePayment(meetingId: Long, userId: Long, settlementId: Long, paymentId: Long): PaymentApproveResponse {
        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403
        memberService.validateAdmin(meetingId, userId)

        val settlement = lockSettlement(meetingId, settlementId)
        val payment = findPayment(settlementId, paymentId)
        requireNotCanceled(settlement)
        requirePending(payment, "확인 대기 상태의 납부만 승인할 수 있습니다.")

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
        userId: Long,
        settlementId: Long,
        paymentId: Long,
        reason: String?
    ): PaymentRejectResponse {
        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403
        memberService.validateAdmin(meetingId, userId)

        val settlement = lockSettlement(meetingId, settlementId)
        val payment = findPayment(settlementId, paymentId)
        requireNotCanceled(settlement)
        requirePending(payment, "확인 대기 상태의 납부만 반려할 수 있습니다.")

        payment.reject(reason)

        // TODO: 푸시 알림 - 해당 구성원에게 반려 안내 알림 발송

        return PaymentRejectResponse(
            paymentId = paymentId,
            status = payment.status,
            rejectionReason = payment.rejectionReason
        )
    }

    // 정산 취소 (4-12): 확인 대기 구성원이 없을 때만, 연결된 안내 공지는 [취소] 표시 후 취소 안내 공지로 교체
    @Transactional
    fun cancelSettlement(meetingId: Long, userId: Long, settlementId: Long, reason: String?): SettlementCancelResponse {
        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403, 취소 안내 공지 작성자는 취소한 관리자
        val memberId = checkNotNull(memberService.validateAdmin(meetingId, userId).id) { "구성원 식별자가 없습니다." }

        // 연결된 공지를 정산보다 먼저 잠금 (공지 삭제와 같은 공지 → 정산 순서라 동시에 와도 교착 없이 차례로 처리)
        // 정산 엔티티를 미리 불러오면 잠금 조회 때 그 값이 재사용되어 삭제로 바뀐 공지 연결을 놓치므로 공지 ID만 조회
        settlementRepository.findNoticeIdByIdAndMeetingId(settlementId, meetingId)
            ?.let { noticeService.lockNotice(meetingId, it) }
        // 납부 제출·승인·반려와 같은 정산 잠금으로 조회해, 처리 중인 납부 요청이 끝난 뒤 상태를 확인
        // (먼저 끝난 공지 삭제가 있으면 여기서 공지 연결이 이미 풀려 있어 취소만 함)
        val settlement = lockSettlement(meetingId, settlementId)
        if (settlement.status == SettlementStatus.CANCELED) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "이미 취소된 정산입니다.")
        }
        val payments = settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(settlementId)
        // 취소된 정산은 승인할 수 없어, 실제로 받은 돈이 기록되지 않는 일을 막기 위해 먼저 승인·반려하도록 함
        if (payments.any { it.status == PaymentStatus.PENDING }) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "확인 대기 중인 납부를 먼저 승인하거나 반려해 주세요.")
        }

        // 공백만 있는 사유는 사유 없음으로 보고, 정리한 값을 저장·공지·응답에 함께 사용
        val cancelReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        val canceledAt = OffsetDateTime.now()
        settlement.cancel(cancelReason, canceledAt)

        // 공지 처리에 실패하면 같은 트랜잭션이라 취소도 함께 롤백됨
        settlement.noticeId?.let { noticeId ->
            noticeService.markCanceled(meetingId, noticeId, canceledBanner(cancelReason))
            settlement.noticeId = noticeService.createNoticeByMember(
                meetingId,
                memberId,
                cancelNotice(settlement, cancelReason, hasPaidMember = payments.any { it.status == PaymentStatus.PAID })
            ).noticeId
        }

        return SettlementCancelResponse(
            settlementId = settlementId,
            status = settlement.status,
            canceledAt = canceledAt.withOffsetSameInstant(KST),
            cancelReason = settlement.cancelReason,
            noticeId = settlement.noticeId
        )
    }

    // 환불 처리 (4-13): 취소된 정산의 완납 항목만, 앱 밖에서 돌려준 뒤 기록
    @Transactional
    fun refundPayment(meetingId: Long, userId: Long, settlementId: Long, paymentId: Long): PaymentRefundResponse {
        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403
        memberService.validateAdmin(meetingId, userId)

        val settlement = lockSettlement(meetingId, settlementId)
        val payment = findPayment(settlementId, paymentId)
        if (settlement.status != SettlementStatus.CANCELED) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "취소된 정산만 환불 처리할 수 있습니다.")
        }
        if (payment.status != PaymentStatus.PAID) {
            throw ApiException(ErrorCode.CONFLICT, "완납한 납부만 환불 처리할 수 있습니다.")
        }

        val refundedAt = OffsetDateTime.now()
        payment.refund(refundedAt)

        return PaymentRefundResponse(paymentId = paymentId, status = payment.status, refundedAt = refundedAt.withOffsetSameInstant(KST))
    }

    // 납부 상태를 확인하기 전에 정산을 잠가, 먼저 끝난 요청의 결과를 보고 판단하도록 함 (동시 승인·중복 처리·취소 경합 방지)
    private fun lockSettlement(meetingId: Long, settlementId: Long): Settlement =
        settlementRepository.findByIdAndMeetingIdForUpdate(settlementId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "정산을 찾을 수 없습니다.")

    private fun findPayment(settlementId: Long, paymentId: Long): SettlementMember =
        settlementMemberRepository.findByIdAndSettlementId(paymentId, settlementId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "납부 항목을 찾을 수 없습니다.")

    // 취소된 정산은 납부 제출·승인·반려 불가 (422)
    private fun requireNotCanceled(settlement: Settlement) {
        if (settlement.status == SettlementStatus.CANCELED) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "취소된 정산입니다.")
        }
    }

    // 승인·반려는 확인 대기(PENDING) 상태의 납부 항목만 가능
    private fun requirePending(payment: SettlementMember, conflictMessage: String) {
        if (payment.status != PaymentStatus.PENDING) {
            throw ApiException(ErrorCode.CONFLICT, conflictMessage)
        }
    }

    // 대체할 정산은 같은 모임의 취소된 정산이고 아직 대체되지 않아야 함
    // 잠금 조회로 같은 정산을 동시에 대체하려는 요청을 순서대로 처리 (UK는 마지막 안전장치)
    private fun validateReplacedSettlement(meetingId: Long, replacesSettlementId: Long) {
        val replaced = settlementRepository.findByIdAndMeetingIdForUpdate(replacesSettlementId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "대체할 정산을 찾을 수 없습니다.")
        if (replaced.status != SettlementStatus.CANCELED) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "취소된 정산만 대체할 수 있습니다.")
        }
        if (settlementRepository.findByReplacesSettlementId(replacesSettlementId) != null) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "이미 다른 정산으로 대체된 정산입니다.")
        }
    }

    // 납부자 이름은 한 번에 조회, 모임을 나갔거나 탈퇴해 구성원 행이 없으면 "탈퇴한 사용자" (명세 4-3)
    // #50 전에 만든 정산은 대상 검증이 없어 다른 모임 구성원 ID가 있을 수 있으므로, 같은 모임 구성원만 이름 표시
    private fun toPaymentResponses(meetingId: Long, payments: List<SettlementMember>): List<PaymentResponse> {
        val membersById = memberRepository.findAllById(payments.map { it.memberId }.toSet())
            .filter { it.meetingId == meetingId }
            .associateBy { it.id }
        val usersById = appUserRepository.findAllById(membersById.values.map { it.userId }).associateBy { it.id }

        return payments.map { payment ->
            PaymentResponse(
                paymentId = checkNotNull(payment.id) { "납부 항목 식별자가 없습니다." },
                memberId = payment.memberId,
                userName = membersById[payment.memberId]?.let { usersById[it.userId] }?.name ?: WITHDRAWN_USER_NAME,
                amount = payment.amount,
                status = payment.status,
                submittedAt = payment.submittedAt?.withOffsetSameInstant(KST),
                refundedAt = payment.refundedAt?.withOffsetSameInstant(KST)
            )
        }
    }

    // 정산 대상은 모두 해당 모임의 구성원이어야 함 (아니면 404, 명세 4-1)
    private fun validateTargetMembers(meetingId: Long, memberIds: List<Long>) {
        val targetCount = memberRepository.findAllById(memberIds).count { it.meetingId == meetingId }
        if (targetCount != memberIds.size) {
            throw ApiException(ErrorCode.NOT_FOUND, "정산 대상 구성원을 찾을 수 없습니다.")
        }
    }

    // 연결 일정은 해당 모임의 일정이어야 함 (아니면 404, 명세 4-1)
    // 일정 삭제와 같은 일정 잠금으로 확인해, 확인 직후 삭제되어 삭제된 일정 ID가 새 정산에 남지 않게 함
    // (삭제가 먼저면 여기서 404, 생성이 먼저면 삭제가 새 정산까지 연결 해제)
    private fun validateSchedule(meetingId: Long, scheduleId: Long?) {
        if (scheduleId != null && scheduleRepository.findByIdAndMeetingIdForUpdate(scheduleId, meetingId) == null) {
            throw ApiException(ErrorCode.NOT_FOUND, "연결할 일정을 찾을 수 없습니다.")
        }
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

    // 기존 안내 공지 맨 앞에 넣는 취소 안내 (사유가 없으면 사유 부분 생략)
    private fun canceledBanner(reason: String?) =
        "⚠ 이 정산은 취소되었습니다." + if (reason.isNullOrBlank()) "" else " (사유: $reason)"

    // 새 취소 안내 공지: 정기 회비 회차 정산이면 [회비 취소] (정기 회비 삭제 후에도 남는 청구 주기로 판단), 완납한 구성원이 있을 때만 환불 안내
    private fun cancelNotice(settlement: Settlement, reason: String?, hasPaidMember: Boolean) = NoticeCreateRequest(
        title = "${if (settlement.billingPeriod != null) "[회비 취소]" else "[정산 취소]"} ${settlement.title}".take(NOTICE_TITLE_MAX_LENGTH),
        content = listOfNotNull(
            "정산명: ${settlement.title}",
            "이 정산은 취소되었습니다.",
            reason?.takeIf { it.isNotBlank() }?.let { "사유: $it" },
            "이미 납부한 금액은 관리자가 따로 돌려드립니다.".takeIf { hasPaidMember }
        ).joinToString("\n")
    )

    companion object {
        // API 명세상 응답 일시는 KST(+09:00)
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
        private const val NOTICE_TITLE_MAX_LENGTH = 50
        private const val WITHDRAWN_USER_NAME = "탈퇴한 사용자"
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

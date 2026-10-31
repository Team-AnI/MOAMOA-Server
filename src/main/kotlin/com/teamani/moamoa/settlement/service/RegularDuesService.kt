package com.teamani.moamoa.settlement.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.settlement.dto.RegularDuesCreateRequest
import com.teamani.moamoa.settlement.dto.RegularDuesCreateResponse
import com.teamani.moamoa.settlement.dto.RegularDuesDeleteResponse
import com.teamani.moamoa.settlement.dto.RegularDuesListResponse
import com.teamani.moamoa.settlement.dto.RegularDuesResponse
import com.teamani.moamoa.settlement.dto.RegularDuesUpdateRequest
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.RegularDues
import com.teamani.moamoa.settlement.repository.RegularDuesRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Optional

@Service
class RegularDuesService(
    private val regularDuesRepository: RegularDuesRepository,
    private val settlementRepository: SettlementRepository
) {

    @Transactional
    fun createRegularDues(meetingId: Long, memberId: Long, request: RegularDuesCreateRequest): RegularDuesCreateResponse {
        // 모임 존재 여부 및 ADMIN 권한 검증 (Member 도메인 연동 후속 이슈 #50 처리 예정)
        validateMeeting(meetingId)
        validateAdmin(meetingId, memberId)

        val cycleType = CycleType.valueOf(requireNotNull(request.cycleType) { "반복 주기는 필수입니다." })
        val cycleDay = requireNotNull(request.cycleDay) { "반복 지정일은 필수입니다." }
        validateCycleDay(cycleType, cycleDay)

        val regularDues = regularDuesRepository.save(
            RegularDues(
                meetingId = meetingId,
                createdBy = memberId,
                title = requireNotNull(request.title) { "회비명은 필수입니다." },
                amount = requireNotNull(request.amount) { "회비 금액은 필수입니다." },
                cycleType = cycleType,
                cycleDay = cycleDay,
                sendTime = request.sendTime?.let { LocalTime.parse(it, SEND_TIME_INPUT_FORMAT) } ?: RegularDues.DEFAULT_SEND_TIME,
                dueDays = requireNotNull(request.dueDays) { "납부 기한 일수는 필수입니다." },
                bankName = requireNotNull(request.bankName) { "은행명은 필수입니다." },
                accountNumber = requireNotNull(request.accountNumber) { "계좌번호는 필수입니다." },
                accountHolder = requireNotNull(request.accountHolder) { "예금주는 필수입니다." },
                postNotice = request.postNotice ?: false
            )
        )

        return RegularDuesCreateResponse(
            regularDuesId = checkNotNull(regularDues.id) { "정기 회비 식별자가 생성되지 않았습니다." },
            title = regularDues.title,
            isActive = regularDues.isActive
        )
    }

    @Transactional(readOnly = true)
    fun getRegularDuesList(meetingId: Long, memberId: Long): RegularDuesListResponse {
        validateMeeting(meetingId)
        validateMember(meetingId, memberId)

        return RegularDuesListResponse(
            regularDues = regularDuesRepository.findAllByMeetingIdOrderByIdAsc(meetingId).map { toResponse(it) }
        )
    }

    @Transactional
    fun updateRegularDues(
        meetingId: Long,
        memberId: Long,
        regularDuesId: Long,
        request: RegularDuesUpdateRequest
    ): RegularDuesResponse {
        validateMeeting(meetingId)
        validateAdmin(meetingId, memberId)

        val regularDues = regularDuesRepository.findByIdAndMeetingId(regularDuesId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "정기 회비를 찾을 수 없습니다.")

        // 모든 항목이 필수라 null로 보내면 비우지 않고 400
        val cycleType = request.cycleType.valueOrKeep("반복 주기")?.let { CycleType.valueOf(it) }
        val cycleDay = request.cycleDay.valueOrKeep("반복 지정일")
        if (cycleType != null && cycleDay == null) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "반복 주기를 바꿀 때는 반복 지정일도 함께 보내야 합니다.")
        }
        // 지정일만 보내면 기존 반복 주기 기준으로 검증
        if (cycleDay != null) {
            validateCycleDay(cycleType ?: regularDues.cycleType, cycleDay)
        }

        // 변경 감지(Dirty Checking)로 트랜잭션 종료 시 반영, 이미 생성된 회차 정산은 바뀌지 않음
        regularDues.update(
            title = request.title.valueOrKeep("회비명"),
            amount = request.amount.valueOrKeep("회비 금액"),
            cycleType = cycleType,
            cycleDay = cycleDay,
            sendTime = request.sendTime.valueOrKeep("발송 시간")?.let { LocalTime.parse(it, SEND_TIME_INPUT_FORMAT) },
            dueDays = request.dueDays.valueOrKeep("납부 기한 일수"),
            bankName = request.bankName.valueOrKeep("은행명"),
            accountNumber = request.accountNumber.valueOrKeep("계좌번호"),
            accountHolder = request.accountHolder.valueOrKeep("예금주"),
            postNotice = request.postNotice.valueOrKeep("공지 자동 발행 여부"),
            isActive = request.isActive.valueOrKeep("활성화 여부")
        )

        return toResponse(regularDues)
    }

    @Transactional
    fun deleteRegularDues(meetingId: Long, memberId: Long, regularDuesId: Long): RegularDuesDeleteResponse {
        validateMeeting(meetingId)
        validateAdmin(meetingId, memberId)

        val regularDues = regularDuesRepository.findByIdAndMeetingId(regularDuesId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "정기 회비를 찾을 수 없습니다.")

        // 이미 생성된 회차의 정산·납부 내역은 유지하고 연결만 해제한 뒤 삭제
        settlementRepository.clearRegularDuesId(regularDuesId)
        regularDuesRepository.delete(regularDues)

        return RegularDuesDeleteResponse(regularDuesId = regularDuesId)
    }

    private fun toResponse(regularDues: RegularDues): RegularDuesResponse =
        RegularDuesResponse(
            regularDuesId = checkNotNull(regularDues.id) { "정기 회비 식별자가 없습니다." },
            title = regularDues.title,
            amount = regularDues.amount,
            cycleType = regularDues.cycleType,
            cycleDay = regularDues.cycleDay,
            sendTime = regularDues.sendTime.format(SEND_TIME_FORMAT),
            dueDays = regularDues.dueDays,
            bankName = regularDues.bankName,
            accountNumber = regularDues.accountNumber,
            accountHolder = regularDues.accountHolder,
            isActive = regularDues.isActive,
            postNotice = regularDues.postNotice
        )

    // 보내지 않았으면(null) 기존 값 유지, null을 보냈으면(Optional.empty) 400
    private fun <T : Any> Optional<T>?.valueOrKeep(name: String): T? =
        this?.orElseThrow { ApiException(ErrorCode.VALIDATION_ERROR, "${name}은(는) 비울 수 없습니다.") }

    // 반복 주기별 지정일 검증 (WEEKLY: MON~SUN, MONTHLY: 1~28 또는 LAST)
    private fun validateCycleDay(cycleType: CycleType, cycleDay: String) {
        val valid = when (cycleType) {
            CycleType.WEEKLY -> cycleDay in WEEKLY_DAYS
            CycleType.MONTHLY -> cycleDay == "LAST" || cycleDay in MONTHLY_DAYS
        }
        if (!valid) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "반복 지정일이 반복 주기에 맞지 않습니다.")
        }
    }

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

    companion object {
        private val WEEKLY_DAYS = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        private val MONTHLY_DAYS = (1..28).map { it.toString() }.toSet()
        // 요청은 9:00·09:00 모두 허용, 응답은 09:00
        private val SEND_TIME_INPUT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
        private val SEND_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

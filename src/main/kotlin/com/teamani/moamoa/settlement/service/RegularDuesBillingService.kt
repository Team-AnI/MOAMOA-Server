package com.teamani.moamoa.settlement.service

import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.RegularDues
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.entity.SettlementMember
import com.teamani.moamoa.settlement.repository.RegularDuesRepository
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// 정기 회비 설정에 따라 회차 정산을 만드는 서비스 (스케줄러가 1분마다 호출)
@Service
class RegularDuesBillingService(
    private val regularDuesRepository: RegularDuesRepository,
    private val settlementRepository: SettlementRepository,
    private val settlementMemberRepository: SettlementMemberRepository,
    private val memberRepository: MemberRepository,
    private val meetingRepository: MeetingRepository,
    private val noticeService: NoticeService
) {

    // 지금 청구할 정기 회비 ID 목록 (청구일이고 발송 시각이 지났으며 이번 회차가 아직 없는 설정, 등록 전 발송 시각의 회차 제외)
    @Transactional(readOnly = true)
    fun findDueRegularDuesIds(now: OffsetDateTime): List<Long> {
        val nowKst = now.withOffsetSameInstant(KST)
        val today = nowKst.toLocalDate()
        val candidates = regularDuesRepository.findAllByIsActiveTrue()
            .filter { isBillingDay(it, today) && !nowKst.toLocalTime().isBefore(it.sendTime) && isRegisteredBySendTime(it, today) }
            .mapNotNull { regularDues -> regularDues.id?.let { it to billingPeriod(regularDues, today) } }
        if (candidates.isEmpty()) {
            return emptyList()
        }

        // 이미 청구된 (정기 회비, 회차)를 한 번에 조회한 뒤 메모리에서 제외
        val billed = settlementRepository
            .findAllByRegularDuesIdInAndBillingPeriodIn(candidates.map { it.first }, candidates.map { it.second }.toSet())
            .mapTo(HashSet()) { it.regularDuesId to it.billingPeriod }
        return candidates.filterNot { it in billed }.map { it.first }
    }

    // 회차 정산 1건 생성. 정기 회비마다 따로 호출해 한 건이 실패해도 다른 회비 청구는 유지
    // 동시 실행으로 같은 회차가 먼저 저장되면 unique 제약(regular_dues_id, billing_period) 위반으로 전체 롤백
    @Transactional
    fun bill(regularDuesId: Long, now: OffsetDateTime): Long? {
        val billingDate = now.withOffsetSameInstant(KST).toLocalDate()
        val regularDues = regularDuesRepository.findById(regularDuesId).orElse(null) ?: return null
        val period = billingPeriod(regularDues, billingDate)

        // 조회 후 설정이 비활성화되었거나, 등록 전에 발송 시각이 지난 회차거나, 이미 청구했거나, 종료된 모임이면 건너뜀
        if (!regularDues.isActive || !isRegisteredBySendTime(regularDues, billingDate) ||
            settlementRepository.existsByRegularDuesIdAndBillingPeriod(regularDuesId, period)
        ) {
            return null
        }
        if (!meetingRepository.existsByIdAndEndedAtIsNull(regularDues.meetingId)) {
            return null
        }

        // 청구 시점의 모임 구성원 전체 (신규 가입자는 가입 후 처음 도래하는 회차부터 포함)
        val memberIds = memberRepository.findAllByMeetingIdOrderByIdAsc(regularDues.meetingId).mapNotNull { it.id }
        if (memberIds.isEmpty()) {
            return null
        }

        // 총액이 정산 금액 저장 범위(Int)를 넘으면 음수로 저장되지 않도록 청구하지 않음
        val totalAmount = regularDues.amount.toLong() * memberIds.size
        require(totalAmount <= Int.MAX_VALUE) { "정산 총액이 허용 범위를 초과했습니다. regularDuesId=$regularDuesId" }

        val settlement = settlementRepository.save(
            Settlement(
                meetingId = regularDues.meetingId,
                regularDuesId = regularDuesId,
                title = regularDues.title,
                totalAmount = totalAmount.toInt(),
                bankName = regularDues.bankName,
                accountNumber = regularDues.accountNumber,
                accountHolder = regularDues.accountHolder,
                dueAt = OffsetDateTime.of(billingDate.plusDays(regularDues.dueDays.toLong()), DUE_TIME, KST),
                billingPeriod = period
            )
        )
        val settlementId = checkNotNull(settlement.id) { "정산 식별자가 생성되지 않았습니다." }
        settlementMemberRepository.saveAll(
            memberIds.map { SettlementMember(settlementId = settlementId, memberId = it, amount = regularDues.amount) }
        )

        // 공지 작성자는 정기 회비를 등록한 관리자
        if (regularDues.postNotice) {
            val notice = noticeService.createNotice(
                regularDues.meetingId,
                regularDues.createdBy,
                regularDuesNotice(regularDues, settlement, billingDate)
            )
            settlement.noticeId = notice.noticeId
        }

        // TODO: 푸시 알림 - 청구 대상 구성원에게 회비 납부 요청 알림 발송

        return settlementId
    }

    // 첫 회차 판단: 등록 시각이 그날 발송 시각 이후면 이번 회차는 건너뛰고 다음 청구일부터 청구 (등록 일시가 없는 기존 설정은 청구)
    private fun isRegisteredBySendTime(regularDues: RegularDues, billingDate: LocalDate): Boolean {
        val createdAt = regularDues.createdAt ?: return true
        return !OffsetDateTime.of(billingDate, regularDues.sendTime, KST).isBefore(createdAt)
    }

    private fun regularDuesNotice(regularDues: RegularDues, settlement: Settlement, billingDate: LocalDate) =
        NoticeCreateRequest(
            title = "[회비] ${regularDues.title} (${billingLabel(regularDues.cycleType, billingDate)})".take(NOTICE_TITLE_MAX_LENGTH),
            content = "회비명: ${regularDues.title}\n" +
                "1인당 금액: ${"%,d".format(Locale.KOREA, regularDues.amount)}원\n" +
                "입금 계좌: ${regularDues.bankName} ${regularDues.accountNumber} (${regularDues.accountHolder})\n" +
                "납부 기한: ${settlement.dueAt.withOffsetSameInstant(KST).format(NOTICE_DUE_AT_FORMAT)}"
        )

    companion object {
        // 청구 날짜·시각 판단과 납부 기한은 KST 기준
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
        private val DUE_TIME: LocalTime = LocalTime.of(23, 59)
        private const val NOTICE_TITLE_MAX_LENGTH = 50
        private val NOTICE_DUE_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

// 오늘이 청구일인지 확인 (MONTHLY: 지정일 또는 말일(LAST), WEEKLY: 지정 요일)
internal fun isBillingDay(regularDues: RegularDues, date: LocalDate): Boolean =
    when (regularDues.cycleType) {
        CycleType.MONTHLY ->
            if (regularDues.cycleDay == "LAST") date.dayOfMonth == date.lengthOfMonth()
            else date.dayOfMonth == regularDues.cycleDay.toInt()
        CycleType.WEEKLY -> date.dayOfWeek == weeklyDay(regularDues.cycleDay)
    }

// 회차 기준일 (MONTHLY: 해당 월 1일, WEEKLY: 해당 주 월요일). 지정일·요일을 바꿔도 같은 기간이면 같은 값
internal fun billingPeriod(regularDues: RegularDues, date: LocalDate): LocalDate =
    when (regularDues.cycleType) {
        CycleType.MONTHLY -> date.withDayOfMonth(1)
        CycleType.WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

// 공지 제목의 청구일 표시. 몇 월분인지 단정하지 않도록 청구일을 쓰고, 매주는 그 달의 몇 번째 청구인지 함께 표시
// (예: 2026년 9월 25일 / 2026년 10월 28일, 5주차)
internal fun billingLabel(cycleType: CycleType, billingDate: LocalDate): String {
    val date = billingDate.format(BILLING_DATE_FORMAT)
    return when (cycleType) {
        CycleType.MONTHLY -> date
        CycleType.WEEKLY -> "$date, ${(billingDate.dayOfMonth - 1) / DAYS_PER_WEEK + 1}주차"
    }
}

private const val DAYS_PER_WEEK = 7
private val BILLING_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

// MON~SUN을 요일로 변환
private fun weeklyDay(cycleDay: String): DayOfWeek =
    DayOfWeek.entries.first { it.name.startsWith(cycleDay) }

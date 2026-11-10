package com.teamani.moamoa.schedule

import com.teamani.moamoa.attendance.entity.Attendance
import com.teamani.moamoa.attendance.entity.AttendanceStatus
import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.schedule.service.ScheduleService
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.entity.SettlementMember
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.vote.entity.Vote
import com.teamani.moamoa.vote.entity.VoteStatus
import com.teamani.moamoa.vote.repository.VoteRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime

// 실제 트랜잭션·DB에서 일정 삭제 시 출석·투표 정리, 정산 연결 해제, 실패 시 롤백을 검증
@SpringBootTest
@ActiveProfiles("test")
class ScheduleDeleteIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var scheduleService: ScheduleService

    @MockitoSpyBean
    lateinit var scheduleRepository: ScheduleRepository

    @Autowired
    lateinit var attendanceRepository: AttendanceRepository

    @Autowired
    lateinit var voteRepository: VoteRepository

    @Autowired
    lateinit var settlementMemberRepository: SettlementMemberRepository

    @Autowired
    lateinit var settlementRepository: SettlementRepository

    init {
        Given("출석·투표 기록과 연결된 정산이 있는 일정이 있을 때") {
            // 다른 테스트 데이터와 섞이지 않도록 전용 모임 ID 사용
            val meetingId = 9601L
            val scheduleId = savedSchedule(meetingId)
            val otherScheduleId = savedSchedule(meetingId)
            saveRecords(scheduleId)
            saveRecords(otherScheduleId)
            val settlement = savedSettlement(meetingId, scheduleId)
            val settlementId = checkNotNull(settlement.id)
            val paymentId = savedPayment(settlementId)

            When("일정을 삭제하면") {
                scheduleService.deleteSchedule(meetingId, 1L, scheduleId)

                Then("일정과 해당 일정의 출석·투표 기록이 삭제된다") {
                    scheduleRepository.findById(scheduleId).isPresent shouldBe false
                    attendanceRepository.findAllByScheduleId(scheduleId) shouldBe emptyList()
                    voteRepository.findAllByScheduleId(scheduleId) shouldBe emptyList()
                }

                Then("정산은 ID·금액·상태와 납부 내역이 그대로 남고 연결된 일정만 해제된다") {
                    val remained = settlementRepository.findById(settlementId).orElseThrow()
                    remained.scheduleId.shouldBeNull()
                    remained.title shouldBe settlement.title
                    remained.totalAmount shouldBe settlement.totalAmount
                    remained.status shouldBe SettlementStatus.IN_PROGRESS

                    val payment = settlementMemberRepository.findById(paymentId).orElseThrow()
                    payment.amount shouldBe 10000
                    payment.status shouldBe PaymentStatus.PAID
                    payment.submittedAt?.toInstant() shouldBe SUBMITTED_AT.toInstant()
                    payment.confirmedAt?.toInstant() shouldBe CONFIRMED_AT.toInstant()
                }

                Then("다른 일정의 출석·투표 기록은 남는다") {
                    attendanceRepository.findAllByScheduleId(otherScheduleId).size shouldBe 1
                    voteRepository.findAllByScheduleId(otherScheduleId).size shouldBe 1
                }
            }
        }

        Given("연결 기록을 모두 정리한 뒤 마지막 일정 삭제에서 오류가 나는 상황에서") {
            val meetingId = 9602L
            val scheduleId = savedSchedule(meetingId)
            saveRecords(scheduleId)
            val settlementId = checkNotNull(savedSettlement(meetingId, scheduleId).id)

            When("일정을 삭제하면") {
                doThrow(IllegalStateException("일정 삭제 실패")).`when`(scheduleRepository).delete(any(Schedule::class.java))
                val exception = shouldThrow<IllegalStateException> {
                    scheduleService.deleteSchedule(meetingId, 1L, scheduleId)
                }
                reset(scheduleRepository)

                Then("먼저 실행한 출석·투표 삭제와 정산 연결 해제까지 모두 롤백되어 그대로 남는다") {
                    exception.message shouldBe "일정 삭제 실패"
                    scheduleRepository.findById(scheduleId).isPresent shouldBe true
                    attendanceRepository.findAllByScheduleId(scheduleId).size shouldBe 1
                    voteRepository.findAllByScheduleId(scheduleId).size shouldBe 1
                    settlementRepository.findById(settlementId).orElseThrow().scheduleId shouldBe scheduleId
                }
            }
        }
    }

    private fun savedSchedule(meetingId: Long): Long =
        checkNotNull(
            scheduleRepository.save(
                Schedule(
                    meetingId = meetingId,
                    title = "정기 모임",
                    startsAt = OffsetDateTime.parse("2099-10-11T19:00:00+09:00")
                )
            ).id
        )

    private fun saveRecords(scheduleId: Long) {
        attendanceRepository.save(Attendance(memberId = 3L, scheduleId = scheduleId, status = AttendanceStatus.PRESENT))
        voteRepository.save(
            Vote(
                memberId = 3L,
                scheduleId = scheduleId,
                meetingTime = OffsetDateTime.parse("2099-10-11T19:00:00+09:00"),
                status = VoteStatus.AVAILABLE,
                endedAt = OffsetDateTime.parse("2099-10-10T23:59:00+09:00")
            )
        )
    }

    private fun savedSettlement(meetingId: Long, scheduleId: Long): Settlement =
        settlementRepository.save(
            Settlement(
                meetingId = meetingId,
                scheduleId = scheduleId,
                title = "정기 모임 식비",
                totalAmount = 10000,
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아",
                dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00")
            )
        )

    private fun savedPayment(settlementId: Long): Long =
        checkNotNull(
            settlementMemberRepository.save(
                SettlementMember(
                    settlementId = settlementId,
                    memberId = 3L,
                    amount = 10000,
                    status = PaymentStatus.PAID,
                    submittedAt = SUBMITTED_AT,
                    confirmedAt = CONFIRMED_AT
                )
            ).id
        )

    companion object {
        private val SUBMITTED_AT: OffsetDateTime = OffsetDateTime.parse("2099-10-12T10:00:00+09:00")
        private val CONFIRMED_AT: OffsetDateTime = OffsetDateTime.parse("2099-10-13T10:00:00+09:00")
    }
}

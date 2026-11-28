package com.teamani.moamoa.settlement

import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.schedule.service.ScheduleService
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.SettlementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// 실제 트랜잭션·DB에서 정산 생성(일정 연결)과 일정 삭제가 동시에 들어올 때 같은 일정 잠금으로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class SettlementScheduleConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var settlementService: SettlementService

    @Autowired
    lateinit var scheduleService: ScheduleService

    @Autowired
    lateinit var settlementRepository: SettlementRepository

    @Autowired
    lateinit var scheduleRepository: ScheduleRepository

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    // 요청을 원하는 시점에 멈춰 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean
    lateinit var attendanceRepository: AttendanceRepository

    @MockitoSpyBean
    lateinit var settlementMemberRepository: SettlementMemberRepository

    init {
        afterTest {
            reset(attendanceRepository, settlementMemberRepository)
        }

        Given("관리자와 구성원이 있는 모임에 일정이 있을 때") {
            When("일정 삭제가 일정을 잠근 직후 그 일정을 연결한 정산 생성이 들어오면") {
                Then("정산 생성은 삭제가 끝날 때까지 기다린 뒤 일정이 없어 404이고 정산은 만들어지지 않는다") {
                    val saved = savedMeetingWithSchedule()
                    // 일정 삭제가 일정을 잠그고 연결 기록을 지우기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(attendanceRepository, pause::hold)).`when`(attendanceRepository)
                        .deleteAllByScheduleId(anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val delete = executor.start { scheduleService.deleteSchedule(saved.meetingId, ADMIN_USER_ID, saved.scheduleId) }
                        pause.awaitPaused()
                        val create = executor.start { settlementService.createSettlement(saved.meetingId, ADMIN_USER_ID, request(saved)) }

                        shouldThrow<TimeoutException> { create.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        delete.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (create.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.NOT_FOUND
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    scheduleRepository.findById(saved.scheduleId).isPresent shouldBe false
                    settlementRepository.findAll().count { it.meetingId == saved.meetingId } shouldBe 0
                }
            }

            When("정산 생성이 일정을 잠근 직후 그 일정의 삭제가 들어오면") {
                Then("일정 삭제는 생성이 끝날 때까지 기다린 뒤 처리되고, 새 정산의 일정 연결까지 해제된다") {
                    val saved = savedMeetingWithSchedule()
                    // 정산 생성이 일정을 잠그고 납부 항목을 저장하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(settlementMemberRepository, pause::hold)).`when`(settlementMemberRepository)
                        .saveAll(anyList())
                    val executor = Executors.newFixedThreadPool(2)
                    var settlementId: Long? = null

                    try {
                        val create = executor.submit(
                            Callable { settlementService.createSettlement(saved.meetingId, ADMIN_USER_ID, request(saved)).settlementId }
                        )
                        pause.awaitPaused()
                        val delete = executor.start { scheduleService.deleteSchedule(saved.meetingId, ADMIN_USER_ID, saved.scheduleId) }

                        shouldThrow<TimeoutException> { delete.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        settlementId = create.get(10, TimeUnit.SECONDS)
                        delete.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    scheduleRepository.findById(saved.scheduleId).isPresent shouldBe false
                    settlementRepository.findById(settlementId.shouldNotBeNull()).orElseThrow().scheduleId.shouldBeNull()
                }
            }
        }
    }

    private data class SavedMeeting(val meetingId: Long, val targetMemberId: Long, val scheduleId: Long)

    // 관리자(ADMIN_USER_ID)와 정산 대상 구성원 1명, 일정 1개가 있는 모임 저장
    private fun savedMeetingWithSchedule(): SavedMeeting {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = UUID.randomUUID().toString().take(8))).id)
        memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN))
        val targetMemberId = checkNotNull(
            memberRepository.save(Member(meetingId = meetingId, userId = MEMBER_USER_ID, role = MemberRole.MEMBER)).id
        )
        val scheduleId = checkNotNull(
            scheduleRepository.save(
                Schedule(meetingId = meetingId, title = "정기 모임", startsAt = OffsetDateTime.parse("2099-10-11T19:00:00+09:00"))
            ).id
        )
        return SavedMeeting(meetingId, targetMemberId, scheduleId)
    }

    private fun request(saved: SavedMeeting) = SettlementCreateRequest(
        scheduleId = saved.scheduleId,
        title = "정기 모임 회식",
        totalAmount = 30000,
        memberIds = listOf(saved.targetMemberId),
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아",
        dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00")
    )

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun beforeReal(spy: Any, action: () -> Unit) = Answer { invocation ->
        action()
        mockingDetails(spy).mockCreationSettings.defaultAnswer.answer(invocation)
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기 (일정 잠금을 쥔 채 멈추므로 H2 잠금 대기 2초 안에 해제)
    private class Pause {
        private val paused = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        fun hold() {
            if (first.getAndSet(false)) {
                paused.countDown()
                released.await(5, TimeUnit.SECONDS)
            }
        }

        fun awaitPaused() {
            paused.await(5, TimeUnit.SECONDS) shouldBe true
        }

        fun release() = released.countDown()
    }

    // 작업을 비동기로 실행하고 작업에서 발생한 예외(성공이면 null) 반환
    private fun ExecutorService.start(task: () -> Any?): Future<Throwable?> =
        submit(Callable { runCatching { task() }.exceptionOrNull() })

    companion object {
        private const val ADMIN_USER_ID = 9801L
        private const val MEMBER_USER_ID = 9802L

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

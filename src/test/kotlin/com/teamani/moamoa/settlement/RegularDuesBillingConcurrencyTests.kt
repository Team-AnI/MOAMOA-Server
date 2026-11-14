package com.teamani.moamoa.settlement

import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.meeting.service.MeetingService
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.settlement.dto.RegularDuesUpdateRequest
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.RegularDues
import com.teamani.moamoa.settlement.repository.RegularDuesRepository
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.RegularDuesBillingService
import com.teamani.moamoa.settlement.service.RegularDuesService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// 실제 트랜잭션·DB에서 같은 정기 회비의 자동 청구와 수정·삭제가 동시에 들어올 때 정기 회비 단위로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class RegularDuesBillingConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var billingService: RegularDuesBillingService

    @Autowired
    lateinit var regularDuesService: RegularDuesService

    @Autowired
    lateinit var regularDuesRepository: RegularDuesRepository

    @Autowired
    lateinit var settlementMemberRepository: SettlementMemberRepository

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var meetingService: MeetingService

    // 요청을 원하는 시점에 멈춰 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean
    lateinit var settlementRepository: SettlementRepository

    @MockitoSpyBean
    lateinit var memberRepository: MemberRepository

    init {
        afterTest {
            reset(settlementRepository, memberRepository)
            settlementMemberRepository.deleteAll()
            settlementRepository.deleteAll()
            regularDuesRepository.deleteAll()
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
        }

        Given("매월 10일 09:00 정기 회비가 청구 대상일 때") {
            val now = OffsetDateTime.parse("2099-11-10T09:00:00+09:00")

            When("자동 청구가 정기 회비를 잠근 직후 정기 회비 삭제가 들어오면") {
                Then("삭제는 청구가 끝날 때까지 기다린 뒤 처리되고, 방금 만든 회차 정산까지 연결이 해제된다") {
                    val saved = savedDues()
                    // 청구가 정기 회비를 잠그고 이번 회차 청구 여부를 확인하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(settlementRepository, pause::hold)).`when`(settlementRepository)
                        .existsByRegularDuesIdAndBillingPeriod(anyLong(), anyDate())
                    val executor = Executors.newFixedThreadPool(2)
                    var billedSettlementId: Long? = null

                    try {
                        val bill = executor.submit(Callable { billingService.bill(saved.regularDuesId, now) })
                        pause.awaitPaused()
                        val delete = executor.start {
                            regularDuesService.deleteRegularDues(saved.meetingId, ADMIN_MEMBER_ID, saved.regularDuesId)
                        }

                        shouldThrow<TimeoutException> { delete.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        billedSettlementId = bill.get(10, TimeUnit.SECONDS)
                        delete.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(settlementRepository)
                    regularDuesRepository.findById(saved.regularDuesId).isPresent shouldBe false
                    settlementRepository.findAll().count { it.regularDuesId == saved.regularDuesId } shouldBe 0
                    settlementRepository.findById(billedSettlementId.shouldNotBeNull()).orElseThrow().regularDuesId.shouldBeNull()
                }
            }

            When("자동 청구가 정기 회비를 잠근 직후 반복 지정일 수정이 들어오면") {
                Then("수정은 청구가 끝날 때까지 기다린 뒤 반영되고, 청구는 수정 전 설정으로 한 번만 이루어진다") {
                    val saved = savedDues()
                    val pause = Pause()
                    doAnswer(beforeReal(settlementRepository, pause::hold)).`when`(settlementRepository)
                        .existsByRegularDuesIdAndBillingPeriod(anyLong(), anyDate())
                    val request = RegularDuesUpdateRequest(cycleDay = Optional.of("20"))
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val bill = executor.submit(Callable { billingService.bill(saved.regularDuesId, now) })
                        pause.awaitPaused()
                        val update = executor.start {
                            regularDuesService.updateRegularDues(saved.meetingId, ADMIN_MEMBER_ID, saved.regularDuesId, request)
                        }

                        shouldThrow<TimeoutException> { update.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        bill.get(10, TimeUnit.SECONDS).shouldNotBeNull()
                        update.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(settlementRepository)
                    regularDuesRepository.findById(saved.regularDuesId).orElseThrow().cycleDay shouldBe "20"
                    settlementRepository.findAll().count { it.regularDuesId == saved.regularDuesId } shouldBe 1
                }
            }
        }

        Given("매월 10일 09:00 정기 회비가 청구 대상이고 모임이 진행 중일 때") {
            val now = OffsetDateTime.parse("2099-11-10T09:00:00+09:00")

            When("자동 청구가 모임을 잠그고 구성원을 조회하는 사이 모임 종료가 들어오면") {
                Then("모임 종료는 청구가 끝날 때까지 기다린 뒤 처리되고, 청구는 종료 전 모임 기준으로 한 번 이루어진다") {
                    val saved = savedDues()
                    // 청구가 모임 잠금을 얻고 구성원을 조회하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(memberRepository, pause::hold)).`when`(memberRepository)
                        .findAllByMeetingIdOrderByIdAsc(anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val bill = executor.submit(Callable { billingService.bill(saved.regularDuesId, now) })
                        pause.awaitPaused()
                        val end = executor.start { meetingService.deleteMeeting(saved.meetingId, ADMIN_USER_ID) }

                        shouldThrow<TimeoutException> { end.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        bill.get(10, TimeUnit.SECONDS).shouldNotBeNull()
                        end.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(memberRepository)
                    meetingRepository.findById(saved.meetingId).orElseThrow().endedAt.shouldNotBeNull()
                    settlementRepository.findAll().count { it.regularDuesId == saved.regularDuesId } shouldBe 1
                }
            }

            When("같은 정기 회비의 같은 회차를 두 요청이 동시에 청구하면") {
                Then("한 요청만 정산을 만들고, 정산과 납부 항목은 한 번만 생성된다") {
                    val saved = savedDues()
                    val ready = CountDownLatch(2)
                    val start = CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(2)

                    val results = try {
                        List(2) {
                            executor.submit(
                                Callable {
                                    ready.countDown()
                                    start.await()
                                    billingService.bill(saved.regularDuesId, now)
                                }
                            )
                        }.also {
                            ready.await()
                            start.countDown()
                        }.map { it.get(10, TimeUnit.SECONDS) }
                    } finally {
                        executor.shutdownNow()
                    }

                    results.count { it != null } shouldBe 1
                    val settlements = settlementRepository.findAll().filter { it.regularDuesId == saved.regularDuesId }
                    settlements.size shouldBe 1
                    settlementMemberRepository.findAll().count { it.settlementId == settlements.single().id } shouldBe 2
                }
            }
        }
    }

    private data class SavedDues(val meetingId: Long, val regularDuesId: Long)

    // 구성원 2명이 있는 모임에 매월 10일 09:00 정기 회비 저장 (등록은 청구일 이전)
    private fun savedDues(): SavedDues {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모임", inviteCode = UUID.randomUUID().toString().take(8))).id)
        memberRepository.save(Member(meetingId = meetingId, userId = 100L, role = MemberRole.ADMIN))
        memberRepository.save(Member(meetingId = meetingId, userId = 101L, role = MemberRole.MEMBER))
        val regularDuesId = checkNotNull(
            regularDuesRepository.save(
                RegularDues(
                    meetingId = meetingId,
                    createdBy = ADMIN_MEMBER_ID,
                    title = "월 회비",
                    amount = 10000,
                    cycleType = CycleType.MONTHLY,
                    cycleDay = "10",
                    sendTime = LocalTime.of(9, 0),
                    dueDays = 7,
                    bankName = "국민은행",
                    accountNumber = "123456789012",
                    accountHolder = "김모아",
                    createdAt = OffsetDateTime.parse("2099-11-01T00:00:00+09:00")
                )
            ).id
        )
        return SavedDues(meetingId, regularDuesId)
    }

    // Kotlin non-null 파라미터에 null을 넘기지 않도록 matcher만 등록하고 임의의 날짜를 반환
    private fun anyDate(): LocalDate = any(LocalDate::class.java) ?: LocalDate.MIN

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun beforeReal(spy: Any, action: () -> Unit) = Answer { invocation ->
        action()
        mockingDetails(spy).mockCreationSettings.defaultAnswer.answer(invocation)
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기 (정기 회비 잠금을 쥔 채 멈추므로 H2 잠금 대기 2초 안에 해제)
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
        private const val ADMIN_MEMBER_ID = 1L

        // savedDues()의 ADMIN 구성원 사용자 ID (모임 종료 권한 확인용)
        private const val ADMIN_USER_ID = 100L

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

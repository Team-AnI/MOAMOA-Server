package com.teamani.moamoa.settlement

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.entity.SettlementMember
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.SettlementService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실제 트랜잭션·DB에서 같은 정산의 납부 요청이 동시에 들어올 때 상태가 어긋나지 않는지 검증
@SpringBootTest
@ActiveProfiles("test")
class SettlementPaymentConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var settlementService: SettlementService

    @Autowired
    lateinit var settlementRepository: SettlementRepository

    @Autowired
    lateinit var settlementMemberRepository: SettlementMemberRepository

    init {
        Given("대상자 2명의 납부 항목이 모두 확인 대기(PENDING)인 정산이 여러 개 있을 때") {
            // 다른 테스트 데이터와 섞이지 않도록 전용 모임 ID 사용
            val meetingId = 9401L
            // 타이밍에 따라 드물게 생기는 경합이라 여러 번 반복해서 검증
            val rounds = List(30) {
                val settlementId = savedSettlement(meetingId)
                settlementId to listOf(
                    savedPayment(settlementId, 1L, PaymentStatus.PENDING),
                    savedPayment(settlementId, 2L, PaymentStatus.PENDING)
                )
            }

            When("정산마다 서로 다른 납부 항목을 동시에 승인하면") {
                val results = rounds.flatMap { (settlementId, paymentIds) ->
                    runConcurrently(
                        paymentIds.map { id -> { settlementService.approvePayment(meetingId, 1L, settlementId, id) } }
                    )
                }

                Then("모두 완납되고 모든 정산이 완료 상태가 된다") {
                    results.filterNotNull() shouldBe emptyList()
                    rounds.flatMap { it.second }.map { paymentStatus(it) }.toSet() shouldBe setOf(PaymentStatus.PAID)
                    rounds.map { settlementStatus(it.first) }.toSet() shouldBe setOf(SettlementStatus.COMPLETED)
                }
            }
        }

        Given("확인 대기(PENDING)인 납부 항목 하나가 있을 때") {
            val meetingId = 9402L
            val settlementId = savedSettlement(meetingId)
            val paymentId = savedPayment(settlementId, 1L, PaymentStatus.PENDING)

            When("같은 항목을 동시에 승인하면") {
                val results = runConcurrently(
                    List(4) { { settlementService.approvePayment(meetingId, 1L, settlementId, paymentId) } }
                )

                Then("한 요청만 성공하고 나머지는 409 CONFLICT다") {
                    results.count { it == null } shouldBe 1
                    results.filterNotNull().map { (it as ApiException).errorCode }.toSet() shouldBe setOf(ErrorCode.CONFLICT)
                    paymentStatus(paymentId) shouldBe PaymentStatus.PAID
                }
            }
        }

        Given("확인 대기(PENDING)인 납부 항목에 승인과 반려가 동시에 들어올 때") {
            val meetingId = 9403L
            val settlementId = savedSettlement(meetingId)
            // 다른 대상자가 남아 있으면 승인돼도 정산이 완료되지 않으므로 대상자는 한 명
            val paymentId = savedPayment(settlementId, 1L, PaymentStatus.PENDING)

            When("두 요청을 동시에 실행하면") {
                val (approveError, rejectError) = runConcurrently(
                    listOf(
                        { settlementService.approvePayment(meetingId, 1L, settlementId, paymentId) },
                        { settlementService.rejectPayment(meetingId, 1L, settlementId, paymentId, "입금 내역 없음") }
                    )
                )

                Then("한 요청만 성공하고 납부·정산 상태는 성공한 요청의 결과와 일치한다") {
                    listOf(approveError, rejectError).count { it == null } shouldBe 1
                    (listOf(approveError, rejectError).filterNotNull().single() as ApiException).errorCode shouldBe ErrorCode.CONFLICT

                    val payment = settlementMemberRepository.findById(paymentId).orElseThrow()
                    if (approveError == null) {
                        payment.status shouldBe PaymentStatus.PAID
                        payment.rejectionReason.shouldBeNull()
                        settlementStatus(settlementId) shouldBe SettlementStatus.COMPLETED
                    } else {
                        payment.status shouldBe PaymentStatus.UNPAID
                        payment.rejectionReason shouldBe "입금 내역 없음"
                        payment.confirmedAt.shouldBeNull()
                        settlementStatus(settlementId) shouldBe SettlementStatus.IN_PROGRESS
                    }
                }
            }
        }

        Given("미납(UNPAID)인 납부 항목 하나가 있을 때") {
            val meetingId = 9404L
            val memberId = 5L
            val settlementId = savedSettlement(meetingId)
            val paymentId = savedPayment(settlementId, memberId, PaymentStatus.UNPAID)

            When("같은 구성원이 납부 완료를 동시에 제출하면") {
                val results = runConcurrently(
                    List(4) { { settlementService.submitPayment(meetingId, memberId, settlementId) } }
                )

                Then("한 요청만 성공하고 나머지는 409 CONFLICT다") {
                    results.count { it == null } shouldBe 1
                    results.filterNotNull().map { (it as ApiException).errorCode }.toSet() shouldBe setOf(ErrorCode.CONFLICT)
                    paymentStatus(paymentId) shouldBe PaymentStatus.PENDING
                }
            }
        }

        Given("서로 다른 정산의 납부 항목이 있을 때") {
            val meetingId = 9405L
            val settlementIds = List(2) { savedSettlement(meetingId) }
            val paymentIds = settlementIds.map { savedPayment(it, 1L, PaymentStatus.PENDING) }

            When("각 항목을 동시에 승인하면") {
                val results = runConcurrently(
                    settlementIds.zip(paymentIds).map { (settlementId, paymentId) ->
                        { settlementService.approvePayment(meetingId, 1L, settlementId, paymentId) }
                    }
                )

                Then("서로 영향 없이 각 정산이 완료된다") {
                    results.filterNotNull() shouldBe emptyList()
                    settlementIds.map { settlementStatus(it) } shouldContainExactly
                        listOf(SettlementStatus.COMPLETED, SettlementStatus.COMPLETED)
                }
            }
        }
    }

    // 모든 작업을 같은 시점에 시작하고, 작업별 예외(성공이면 null)를 순서대로 반환
    private fun runConcurrently(tasks: List<() -> Any?>): List<Throwable?> {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tasks.size)

        try {
            val futures = tasks.map { task ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        start.await()
                        runCatching { task() }.exceptionOrNull()
                    }
                )
            }
            ready.await()
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun savedSettlement(meetingId: Long): Long =
        checkNotNull(
            settlementRepository.save(
                Settlement(
                    meetingId = meetingId,
                    title = "정산",
                    totalAmount = 80000,
                    bankName = "국민은행",
                    accountNumber = "123456789012",
                    accountHolder = "김모아",
                    dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00")
                )
            ).id
        )

    private fun savedPayment(settlementId: Long, memberId: Long, status: PaymentStatus): Long =
        checkNotNull(
            settlementMemberRepository.save(
                SettlementMember(settlementId = settlementId, memberId = memberId, amount = 10000, status = status)
            ).id
        )

    private fun paymentStatus(paymentId: Long): PaymentStatus =
        settlementMemberRepository.findById(paymentId).orElseThrow().status

    private fun settlementStatus(settlementId: Long): SettlementStatus =
        settlementRepository.findById(settlementId).orElseThrow().status
}

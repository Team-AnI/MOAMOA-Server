package com.teamani.moamoa.settlement

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.util.UUID
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

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var noticeService: NoticeService

    @Autowired
    lateinit var noticeRepository: NoticeRepository

    init {
        Given("취소된 정산이 여러 개 있을 때") {
            val meetingId = savedMeeting()
            val adminMemberId = checkNotNull(memberRepository.findByMeetingIdAndUserId(meetingId, ADMIN_USER_ID)?.id)
            val canceledIds = List(10) {
                val settlementId = savedSettlement(meetingId)
                settlementRepository.save(settlementRepository.findById(settlementId).orElseThrow().apply { cancel(null, OffsetDateTime.now()) })
                settlementId
            }

            When("같은 취소 정산을 대체하는 정산을 동시에 두 개씩 만들면") {
                val results = canceledIds.map { canceledId ->
                    canceledId to runConcurrently(
                        List(2) { { settlementService.createSettlement(meetingId, ADMIN_USER_ID, replacingRequest(canceledId, adminMemberId)) } }
                    )
                }

                Then("각각 하나만 생성되고 나머지는 422 UNPROCESSABLE_ENTITY이며, 대체 정산은 하나만 연결된다") {
                    results.forEach { (canceledId, errors) ->
                        errors.count { it == null } shouldBe 1
                        errors.filterNotNull().map { (it as ApiException).errorCode } shouldContainExactly listOf(ErrorCode.UNPROCESSABLE_ENTITY)
                        settlementRepository.findByReplacesSettlementId(canceledId).shouldNotBeNull()
                    }
                }
            }
        }

        Given("안내 공지가 연결된 정산이 여러 개 있을 때") {
            val meetingId = savedMeeting()
            val adminMemberId = checkNotNull(memberRepository.findByMeetingIdAndUserId(meetingId, ADMIN_USER_ID)?.id)
            val created = List(10) {
                settlementService.createSettlement(meetingId, ADMIN_USER_ID, replacingRequest(null, adminMemberId).copy(postNotice = true))
            }

            When("정산 취소와 그 안내 공지 삭제를 동시에 요청하면") {
                val results = created.flatMap { settlement ->
                    val noticeId = checkNotNull(settlement.noticeId)
                    runConcurrently(
                        listOf(
                            { settlementService.cancelSettlement(meetingId, ADMIN_USER_ID, settlement.settlementId, null) },
                            { noticeService.deleteNotice(meetingId, noticeId, ADMIN_USER_ID) }
                        )
                    )
                }

                Then("교착 없이 모두 성공하고, 정산은 취소되며 남은 공지 연결은 실제 공지를 가리킨다") {
                    results.filterNotNull() shouldBe emptyList()
                    created.forEach { settlement ->
                        val saved = settlementRepository.findById(settlement.settlementId).orElseThrow()
                        saved.status shouldBe SettlementStatus.CANCELED
                        // 삭제가 먼저면 연결 없음, 취소가 먼저면 새 취소 공지에 연결
                        saved.noticeId?.let { noticeRepository.existsById(it) shouldBe true }
                    }
                }
            }
        }

        Given("미납 납부 항목 하나가 있는 정산이 여러 개 있을 때") {
            val meetingId = savedMeeting()
            val memberId = checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = CANCEL_MEMBER_USER_ID, role = MemberRole.MEMBER)).id)
            val rounds = List(10) {
                val settlementId = savedSettlement(meetingId)
                settlementId to savedPayment(settlementId, memberId, PaymentStatus.UNPAID)
            }

            When("정산 취소와 납부 완료 제출을 동시에 요청하면") {
                rounds.forEach { (settlementId, _) ->
                    runConcurrently(
                        listOf(
                            { settlementService.cancelSettlement(meetingId, ADMIN_USER_ID, settlementId, null) },
                            { settlementService.submitPayment(meetingId, CANCEL_MEMBER_USER_ID, settlementId) }
                        )
                    )
                }

                Then("취소됐는데 확인 대기인 납부가 남는 경우는 없다 (먼저 끝난 요청 결과를 보고 나머지가 422)") {
                    rounds.forEach { (settlementId, paymentId) ->
                        val canceled = settlementStatus(settlementId) == SettlementStatus.CANCELED
                        val pending = paymentStatus(paymentId) == PaymentStatus.PENDING
                        (canceled && pending) shouldBe false
                        (canceled || pending) shouldBe true
                    }
                }
            }
        }

        Given("대상자 2명의 납부 항목이 모두 확인 대기(PENDING)인 정산이 여러 개 있을 때") {
            // 승인·반려·제출은 권한을 확인하므로 실제 모임과 관리자 구성원을 저장
            val meetingId = savedMeeting()
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
                        paymentIds.map { id -> { settlementService.approvePayment(meetingId, ADMIN_USER_ID, settlementId, id) } }
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
            val meetingId = savedMeeting()
            val settlementId = savedSettlement(meetingId)
            val paymentId = savedPayment(settlementId, 1L, PaymentStatus.PENDING)

            When("같은 항목을 동시에 승인하면") {
                val results = runConcurrently(
                    List(4) { { settlementService.approvePayment(meetingId, ADMIN_USER_ID, settlementId, paymentId) } }
                )

                Then("한 요청만 성공하고 나머지는 409 CONFLICT다") {
                    results.count { it == null } shouldBe 1
                    results.filterNotNull().map { (it as ApiException).errorCode }.toSet() shouldBe setOf(ErrorCode.CONFLICT)
                    paymentStatus(paymentId) shouldBe PaymentStatus.PAID
                }
            }
        }

        Given("확인 대기(PENDING)인 납부 항목에 승인과 반려가 동시에 들어올 때") {
            val meetingId = savedMeeting()
            val settlementId = savedSettlement(meetingId)
            // 다른 대상자가 남아 있으면 승인돼도 정산이 완료되지 않으므로 대상자는 한 명
            val paymentId = savedPayment(settlementId, 1L, PaymentStatus.PENDING)

            When("두 요청을 동시에 실행하면") {
                val (approveError, rejectError) = runConcurrently(
                    listOf(
                        { settlementService.approvePayment(meetingId, ADMIN_USER_ID, settlementId, paymentId) },
                        { settlementService.rejectPayment(meetingId, ADMIN_USER_ID, settlementId, paymentId, "입금 내역 없음") }
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
            val meetingId = savedMeeting()
            val memberUserId = MEMBER_USER_ID
            val memberId = checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = memberUserId, role = MemberRole.MEMBER)).id)
            val settlementId = savedSettlement(meetingId)
            val paymentId = savedPayment(settlementId, memberId, PaymentStatus.UNPAID)

            When("같은 구성원이 납부 완료를 동시에 제출하면") {
                val results = runConcurrently(
                    List(4) { { settlementService.submitPayment(meetingId, memberUserId, settlementId) } }
                )

                Then("한 요청만 성공하고 나머지는 409 CONFLICT다") {
                    results.count { it == null } shouldBe 1
                    results.filterNotNull().map { (it as ApiException).errorCode }.toSet() shouldBe setOf(ErrorCode.CONFLICT)
                    paymentStatus(paymentId) shouldBe PaymentStatus.PENDING
                }
            }
        }

        Given("서로 다른 정산의 납부 항목이 있을 때") {
            val meetingId = savedMeeting()
            val settlementIds = List(2) { savedSettlement(meetingId) }
            val paymentIds = settlementIds.map { savedPayment(it, 1L, PaymentStatus.PENDING) }

            When("각 항목을 동시에 승인하면") {
                val results = runConcurrently(
                    settlementIds.zip(paymentIds).map { (settlementId, paymentId) ->
                        { settlementService.approvePayment(meetingId, ADMIN_USER_ID, settlementId, paymentId) }
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

    // 관리자(ADMIN_USER_ID)가 있는 모임 저장
    private fun savedMeeting(): Long {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = UUID.randomUUID().toString().take(8))).id)
        memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN))
        return meetingId
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

    private fun replacingRequest(replacesSettlementId: Long?, memberId: Long) = SettlementCreateRequest(
        title = "재청구",
        replacesSettlementId = replacesSettlementId,
        totalAmount = 10000,
        memberIds = listOf(memberId),
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아",
        dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00")
    )

    companion object {
        private const val ADMIN_USER_ID = 9401L
        private const val MEMBER_USER_ID = 9402L
        private const val CANCEL_MEMBER_USER_ID = 9403L
    }
}

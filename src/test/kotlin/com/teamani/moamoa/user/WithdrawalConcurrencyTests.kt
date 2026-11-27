package com.teamani.moamoa.user

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.RegularDues
import com.teamani.moamoa.settlement.repository.RegularDuesRepository
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.RegularDuesBillingService
import com.teamani.moamoa.settlement.service.SettlementService
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.dto.WithdrawalRequest
import com.teamani.moamoa.user.repository.PasswordResetTokenRepository
import com.teamani.moamoa.user.service.AuthService
import com.teamani.moamoa.user.service.UserService
import com.teamani.moamoa.vote.repository.VoteRepository
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
import java.time.LocalTime
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

// 실제 트랜잭션·DB에서 모임 나가기·회원 탈퇴가 정산 생성·정기 회비 청구·로그인·재발급과 동시에 들어올 때
// 같은 잠금(구성원·사용자)으로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class WithdrawalConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var billingService: RegularDuesBillingService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var meetingRepository: MeetingRepository
    @Autowired lateinit var memberRepository: MemberRepository
    @Autowired lateinit var settlementRepository: SettlementRepository
    @Autowired lateinit var regularDuesRepository: RegularDuesRepository

    // 요청을 원하는 시점에 멈춰 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean lateinit var settlementMemberRepository: SettlementMemberRepository
    @MockitoSpyBean lateinit var voteRepository: VoteRepository
    @MockitoSpyBean lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    init {
        afterTest {
            reset(settlementMemberRepository, voteRepository, passwordResetTokenRepository)
        }

        Given("관리자와 구성원이 있는 모임에서") {
            When("정산 생성이 대상 구성원을 잠근 직후 그 구성원이 모임 나가기를 요청하면") {
                Then("나가기는 생성이 끝날 때까지 기다린 뒤 새 미납 정산을 보고 422이다") {
                    val saved = savedMeeting()
                    // 정산 생성이 대상 구성원을 잠그고 납부 항목을 저장하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(settlementMemberRepository, pause::hold)).`when`(settlementMemberRepository).saveAll(anyList())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val create = executor.start { settlementService.createSettlement(saved.meetingId, ADMIN_USER_ID, request(saved)) }
                        pause.awaitPaused()
                        val leave = executor.start { memberService.leaveMeeting(saved.meetingId, MEMBER_USER_ID) }

                        shouldThrow<TimeoutException> { leave.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        create.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (leave.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    memberRepository.findById(saved.memberId).isPresent shouldBe true
                }
            }

            When("모임 나가기가 구성원을 잠근 직후 그 구성원을 대상으로 정산 생성이 들어오면") {
                Then("정산 생성은 나가기가 끝날 때까지 기다린 뒤 대상이 없어 404이고 정산은 만들어지지 않는다") {
                    val saved = savedMeeting()
                    // 나가기가 구성원을 잠그고 기록을 지우기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(voteRepository, pause::hold)).`when`(voteRepository).deleteAllByMemberId(anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val leave = executor.start { memberService.leaveMeeting(saved.meetingId, MEMBER_USER_ID) }
                        pause.awaitPaused()
                        val create = executor.start { settlementService.createSettlement(saved.meetingId, ADMIN_USER_ID, request(saved)) }

                        shouldThrow<TimeoutException> { create.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        leave.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (create.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.NOT_FOUND
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    settlementRepository.findAll().count { it.meetingId == saved.meetingId } shouldBe 0
                }
            }

            When("정기 회비 자동 청구가 구성원을 잠근 직후 구성원이 모임 나가기를 요청하면") {
                Then("나가기는 청구가 끝날 때까지 기다린 뒤 새 회차 미납을 보고 422이다") {
                    val saved = savedMeeting()
                    val regularDuesId = savedRegularDues(saved)
                    // 청구가 구성원을 잠그고 납부 항목을 저장하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(settlementMemberRepository, pause::hold)).`when`(settlementMemberRepository).saveAll(anyList())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val bill = executor.submit(
                            Callable { billingService.bill(regularDuesId, OffsetDateTime.parse("2099-11-10T09:00:00+09:00")) }
                        )
                        pause.awaitPaused()
                        val leave = executor.start { memberService.leaveMeeting(saved.meetingId, MEMBER_USER_ID) }

                        shouldThrow<TimeoutException> { leave.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        bill.get(10, TimeUnit.SECONDS).shouldNotBeNull()
                        (leave.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    memberRepository.findById(saved.memberId).isPresent shouldBe true
                }
            }
        }

        Given("로그인한 사용자가 탈퇴할 때") {
            When("탈퇴가 사용자를 잠근 직후 같은 계정의 로그인과 토큰 재발급이 들어오면") {
                Then("로그인·재발급은 탈퇴가 끝날 때까지 기다린 뒤 탈퇴한 계정으로 보고 401이다") {
                    val email = "withdrawal-race-${UUID.randomUUID().toString().take(8)}@moamoa.test"
                    val signedUp = userService.signUp(
                        UserSignUpRequest(name = "김모아", email = email, password = PASSWORD, agreedToTerms = true)
                    )
                    // 탈퇴가 사용자를 잠그고 토큰을 지우는 중에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(passwordResetTokenRepository, pause::hold)).`when`(passwordResetTokenRepository)
                        .deleteAllByUserId(anyLong())
                    val executor = Executors.newFixedThreadPool(3)

                    try {
                        val withdraw = executor.start { userService.withdraw(signedUp.userId, WithdrawalRequest(password = PASSWORD)) }
                        pause.awaitPaused()
                        val login = executor.start { authService.login(LoginRequest(email = email, password = PASSWORD)) }
                        val refresh = executor.start { authService.refresh(TokenRefreshRequest(refreshToken = signedUp.refreshToken)) }

                        shouldThrow<TimeoutException> { login.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        shouldThrow<TimeoutException> { refresh.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        withdraw.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (login.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.UNAUTHORIZED
                        (refresh.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.UNAUTHORIZED
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                }
            }
        }
    }

    private data class SavedMeeting(val meetingId: Long, val adminMemberId: Long, val memberId: Long)

    // 관리자(ADMIN_USER_ID)와 구성원(MEMBER_USER_ID) 1명이 있는 모임 저장
    private fun savedMeeting(): SavedMeeting {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = UUID.randomUUID().toString().take(8))).id)
        val adminMemberId = checkNotNull(
            memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN)).id
        )
        val memberId = checkNotNull(
            memberRepository.save(Member(meetingId = meetingId, userId = MEMBER_USER_ID, role = MemberRole.MEMBER)).id
        )
        return SavedMeeting(meetingId, adminMemberId, memberId)
    }

    // 2099-11-10 09:00에 청구 대상인 매월 10일 정기 회비
    private fun savedRegularDues(saved: SavedMeeting): Long = checkNotNull(
        regularDuesRepository.save(
            RegularDues(
                meetingId = saved.meetingId,
                createdBy = saved.adminMemberId,
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

    private fun request(saved: SavedMeeting) = SettlementCreateRequest(
        title = "회식비",
        totalAmount = 10000,
        memberIds = listOf(saved.memberId),
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

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기 (잠금을 쥔 채 멈추므로 H2 잠금 대기 2초 안에 해제)
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
        private const val ADMIN_USER_ID = 9821L
        private const val MEMBER_USER_ID = 9822L
        private const val PASSWORD = "moamoa1234"

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

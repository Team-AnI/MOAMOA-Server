package com.teamani.moamoa.user

import com.teamani.moamoa.attendance.service.AttendanceService
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.dto.MeetingCreateRequest
import com.teamani.moamoa.meeting.dto.MeetingJoinRequest
import com.teamani.moamoa.meeting.service.MeetingService
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.schedule.service.ScheduleResponseService
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.SettlementService
import com.teamani.moamoa.user.dto.LoginRequest
import com.teamani.moamoa.user.dto.TokenRefreshRequest
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.dto.WithdrawalRequest
import com.teamani.moamoa.user.repository.AppUserRepository
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import com.teamani.moamoa.user.service.AuthService
import com.teamani.moamoa.user.service.UserService
import com.teamani.moamoa.user.service.sha256
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

// 실제 트랜잭션·DB에서 회원 탈퇴(7-9)와 모임 나가기(1-8)의 미납·관리자 규칙, 탈퇴 후 처리 검증
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawalIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var meetingService: MeetingService
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var scheduleResponseService: ScheduleResponseService
    @Autowired lateinit var attendanceService: AttendanceService
    @Autowired lateinit var appUserRepository: AppUserRepository
    @Autowired lateinit var refreshTokenRepository: RefreshTokenRepository
    @Autowired lateinit var memberRepository: MemberRepository
    @Autowired lateinit var scheduleRepository: ScheduleRepository
    @Autowired lateinit var settlementRepository: SettlementRepository
    @Autowired lateinit var settlementMemberRepository: SettlementMemberRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    init {
        Given("일반 구성원이 진행 중인 모임(제출한 정산·취소된 미납 정산)과 종료된 모임(유일한 관리자·미납 정산)에 있을 때") {
            When("올바른 비밀번호로 탈퇴하면") {
                Then("모든 모임에서 나가고 개인정보·토큰이 지워지며, 현재 구성원 목록에 나오지 않고 다시 로그인·재발급·7일 내 재가입을 할 수 없다") {
                    val admin = signUp()
                    val user = signUp()
                    val meetingId = createMeeting(admin)
                    meetingService.joinMeeting(user.userId, MeetingJoinRequest(inviteCode = inviteCode(meetingId, admin)))
                    val userMemberId = memberId(meetingId, user)

                    // 납부 완료를 제출한 정산, 취소된 정산의 미납은 탈퇴를 막지 않음
                    val submitted = createSettlement(meetingId, admin, userMemberId)
                    settlementService.submitPayment(meetingId, user.userId, submitted)
                    val canceled = createSettlement(meetingId, admin, userMemberId)
                    settlementService.cancelSettlement(meetingId, admin.userId, canceled, null)

                    // 종료된 모임은 유일한 관리자·미납 판단에서 빠짐
                    val endedMeetingId = createMeeting(user)
                    createSettlement(endedMeetingId, user, memberId(endedMeetingId, user))
                    meetingService.deleteMeeting(endedMeetingId, user.userId)

                    val scheduleId = checkNotNull(
                        scheduleRepository.save(
                            Schedule(meetingId = meetingId, title = "정기 모임", startsAt = OffsetDateTime.parse("2099-10-11T19:00:00+09:00"))
                        ).id
                    )

                    mockMvc.post("/v1/me/withdrawal") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"password":"$PASSWORD"}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.success") { value(true) }
                    }

                    val withdrawn = appUserRepository.findById(user.userId).orElseThrow()
                    withdrawn.name shouldBe "탈퇴한 사용자"
                    withdrawn.email shouldBe "withdrawn:${user.userId}"
                    withdrawn.phone.shouldBeNull()
                    passwordEncoder.matches(PASSWORD, withdrawn.password) shouldBe false
                    withdrawn.deletedAt.shouldNotBeNull()
                    withdrawn.withdrawnEmailHash shouldBe sha256(user.email)
                    memberRepository.findAllByUserIdOrderByIdAsc(user.userId).size shouldBe 0
                    refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(user.userId).size shouldBe 0

                    // 현재 구성원 기준 목록(1-7·2-7·6-2)에 탈퇴한 사용자가 나오지 않음
                    memberService.getMembers(meetingId, admin.userId).members.map { it.memberId } shouldNotContain userMemberId
                    scheduleResponseService.getResponses(meetingId, admin.userId, scheduleId).summary.totalCount shouldBe 1
                    attendanceService.getAttendances(meetingId, admin.userId, scheduleId).attendances.map { it.memberId } shouldNotContain userMemberId

                    // 정산·납부 기록은 남음
                    settlementMemberRepository.findBySettlementIdAndMemberId(submitted, userMemberId)?.status shouldBe PaymentStatus.PENDING

                    errorOf { authService.login(LoginRequest(email = user.email, password = PASSWORD)) } shouldBe ErrorCode.UNAUTHORIZED
                    errorOf { authService.refresh(TokenRefreshRequest(refreshToken = user.refreshToken)) } shouldBe ErrorCode.UNAUTHORIZED
                    errorOf { userService.signUp(signUpRequest(user.email)) } shouldBe ErrorCode.CONFLICT
                }
            }
        }

        Given("탈퇴하려는 사용자가 있을 때") {
            When("비밀번호가 틀리면") {
                Then("400 VALIDATION_ERROR이고 계정은 그대로다") {
                    val user = signUp()

                    errorOf { userService.withdraw(user.userId, withdrawalRequest("wrong1234")) } shouldBe ErrorCode.VALIDATION_ERROR
                    appUserRepository.findById(user.userId).orElseThrow().deletedAt.shouldBeNull()
                }
            }

            When("진행 중인 모임의 유일한 관리자이면") {
                Then("422이고 모임에서 나가지 않는다") {
                    val user = signUp()
                    val meetingId = createMeeting(user)

                    errorOf { userService.withdraw(user.userId, withdrawalRequest()) } shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                    memberRepository.findByMeetingIdAndUserId(meetingId, user.userId).shouldNotBeNull()
                    appUserRepository.findById(user.userId).orElseThrow().deletedAt.shouldBeNull()
                }
            }
        }

        Given("구성원에게 납부 완료를 제출하지 않은 정산이 있을 때") {
            When("모임 나가기·탈퇴를 요청하고, 제출한 뒤 나간 다음 관리자가 반려·승인하면") {
                Then("미납이면 둘 다 422, 제출 후에는 나갈 수 있고 나간 구성원의 미납은 관리자가 바로 승인해 정산이 완료된다") {
                    val admin = signUp()
                    val user = signUp()
                    val meetingId = createMeeting(admin)
                    meetingService.joinMeeting(user.userId, MeetingJoinRequest(inviteCode = inviteCode(meetingId, admin)))
                    val userMemberId = memberId(meetingId, user)
                    val settlementId = createSettlement(meetingId, admin, userMemberId)
                    val paymentId = checkNotNull(settlementMemberRepository.findBySettlementIdAndMemberId(settlementId, userMemberId)?.id)

                    errorOf { memberService.leaveMeeting(meetingId, user.userId) } shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                    errorOf { userService.withdraw(user.userId, withdrawalRequest()) } shouldBe ErrorCode.UNPROCESSABLE_ENTITY

                    settlementService.submitPayment(meetingId, user.userId, settlementId)
                    memberService.leaveMeeting(meetingId, user.userId)

                    // 나간 뒤 반려하면 미납으로 남고, 앱 밖에서 받은 뒤 관리자가 바로 승인
                    settlementService.rejectPayment(meetingId, admin.userId, settlementId, paymentId, null)
                    settlementService.approvePayment(meetingId, admin.userId, settlementId, paymentId).status shouldBe PaymentStatus.PAID
                    settlementRepository.findById(settlementId).orElseThrow().status shouldBe SettlementStatus.COMPLETED
                }
            }

            When("모임에 남아 있는 구성원의 미납을 관리자가 승인하면") {
                Then("지금처럼 409이다 (제출해야 승인 가능)") {
                    val admin = signUp()
                    val user = signUp()
                    val meetingId = createMeeting(admin)
                    meetingService.joinMeeting(user.userId, MeetingJoinRequest(inviteCode = inviteCode(meetingId, admin)))
                    val userMemberId = memberId(meetingId, user)
                    val settlementId = createSettlement(meetingId, admin, userMemberId)
                    val paymentId = checkNotNull(settlementMemberRepository.findBySettlementIdAndMemberId(settlementId, userMemberId)?.id)

                    errorOf { settlementService.approvePayment(meetingId, admin.userId, settlementId, paymentId) } shouldBe ErrorCode.CONFLICT
                }
            }
        }

        Given("탈퇴한 사용자의 액세스 토큰이 아직 만료되지 않았을 때") {
            When("모임을 만들거나 초대코드로 가입하면") {
                Then("401이고 구성원이 생기지 않는다") {
                    val admin = signUp()
                    val meetingId = createMeeting(admin)
                    val user = signUp()
                    userService.withdraw(user.userId, withdrawalRequest())

                    errorOf { meetingService.createMeeting(user.userId, MeetingCreateRequest(name = "탈퇴 후 모임")) } shouldBe ErrorCode.UNAUTHORIZED
                    errorOf {
                        meetingService.joinMeeting(user.userId, MeetingJoinRequest(inviteCode = inviteCode(meetingId, admin)))
                    } shouldBe ErrorCode.UNAUTHORIZED
                    memberRepository.findAllByUserIdOrderByIdAsc(user.userId).size shouldBe 0
                }
            }
        }

        Given("탈퇴한 지 7일이 지난 계정과 지나지 않은 계정이 있을 때") {
            When("탈퇴 이메일 해시 정리를 실행하면") {
                Then("7일이 지난 계정의 해시만 지워진다") {
                    val old = signUp()
                    val recent = signUp()
                    userService.withdraw(old.userId, withdrawalRequest())
                    userService.withdraw(recent.userId, withdrawalRequest())
                    val now = OffsetDateTime.now()
                    jdbcTemplate.update("update app_user set deleted_at = ? where id = ?", now.minusDays(8), old.userId)

                    userService.clearExpiredWithdrawnEmailHashes(now)

                    appUserRepository.findById(old.userId).orElseThrow().withdrawnEmailHash.shouldBeNull()
                    appUserRepository.findById(recent.userId).orElseThrow().withdrawnEmailHash shouldBe sha256(recent.email)
                }
            }
        }
    }

    private data class SignedUp(val userId: Long, val email: String, val accessToken: String, val refreshToken: String)

    private fun signUp(): SignedUp {
        val email = "withdrawal-${UUID.randomUUID().toString().take(8)}@moamoa.test"
        val response = userService.signUp(signUpRequest(email))
        return SignedUp(response.userId, email, response.accessToken, response.refreshToken)
    }

    private fun signUpRequest(email: String) =
        UserSignUpRequest(name = "김모아", email = email, password = PASSWORD, phone = "01012345678", agreedToTerms = true)

    private fun withdrawalRequest(password: String = PASSWORD) =
        WithdrawalRequest(password = password)

    private fun createMeeting(admin: SignedUp): Long =
        meetingService.createMeeting(admin.userId, MeetingCreateRequest(name = "모아모아")).meetingId

    private fun inviteCode(meetingId: Long, admin: SignedUp): String =
        meetingService.getInviteCode(meetingId, admin.userId).inviteCode

    private fun memberId(meetingId: Long, user: SignedUp): Long =
        checkNotNull(memberRepository.findByMeetingIdAndUserId(meetingId, user.userId)?.id)

    private fun createSettlement(meetingId: Long, admin: SignedUp, targetMemberId: Long): Long =
        settlementService.createSettlement(
            meetingId,
            admin.userId,
            SettlementCreateRequest(
                title = "회식비",
                totalAmount = 10000,
                memberIds = listOf(targetMemberId),
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아",
                dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00")
            )
        ).settlementId

    private fun errorOf(block: () -> Any?): ErrorCode = shouldThrow<ApiException> { block() }.errorCode

    companion object {
        private const val PASSWORD = "moamoa1234"
    }
}

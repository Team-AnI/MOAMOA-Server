package com.teamani.moamoa.settlement

import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.RegularDues
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.repository.RegularDuesRepository
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.RegularDuesBillingService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

// 실제 DB(H2)에서 정기 회비 자동 청구의 대상 선정, 정산 생성, 중복 방지, 공지 발행을 검증
@SpringBootTest
@ActiveProfiles("test")
class RegularDuesBillingIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var billingService: RegularDuesBillingService

    @Autowired
    lateinit var regularDuesRepository: RegularDuesRepository

    @Autowired
    lateinit var settlementRepository: SettlementRepository

    @Autowired
    lateinit var settlementMemberRepository: SettlementMemberRepository

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var noticeRepository: NoticeRepository

    init {
        Given("매월 10일 09:00 정기 회비와 구성원 3명이 있는 모임이 있을 때") {
            val meetingId = savedMeeting(memberCount = 3)
            val duesId = savedDues(meetingId, CycleType.MONTHLY, "10", dueDays = 7)

            When("청구일 발송 시각 전이면") {
                val dueIds = billingService.findDueRegularDuesIds(kst("2099-11-10T08:59"))

                Then("청구 대상이 아니다") {
                    dueIds shouldNotContain duesId
                }
            }

            When("청구일 발송 시각이 지나 청구하면") {
                val now = kst("2099-11-10T09:00")
                billingService.findDueRegularDuesIds(now) shouldContain duesId
                val settlementId = billingService.bill(duesId, now).shouldNotBeNull()

                Then("구성원 전체를 대상으로 회차 정산과 납부 항목이 만들어지고 마감은 7일 뒤 23:59다") {
                    val settlement = settlementRepository.findById(settlementId).orElseThrow()
                    settlement.regularDuesId shouldBe duesId
                    settlement.billingPeriod shouldBe LocalDate.parse("2099-11-01")
                    settlement.totalAmount shouldBe 30000
                    settlement.createdBy.shouldBeNull()
                    settlement.status shouldBe SettlementStatus.IN_PROGRESS
                    settlement.dueAt.toInstant() shouldBe kst("2099-11-17T23:59").toInstant()

                    val payments = settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(settlementId)
                    payments.size shouldBe 3
                    payments.all { it.amount == 10000 && it.status == PaymentStatus.UNPAID } shouldBe true
                }

                Then("같은 회차는 다시 청구 대상이 아니고 다시 실행해도 정산이 늘지 않는다") {
                    billingService.findDueRegularDuesIds(kst("2099-11-10T09:01")) shouldNotContain duesId
                    billingService.bill(duesId, kst("2099-11-10T09:01")).shouldBeNull()
                    settlementsOf(duesId) shouldBe 1
                }
            }
        }

        Given("매주 수요일 정기 회비가 이번 주에 이미 청구되었을 때") {
            val meetingId = savedMeeting(memberCount = 2)
            val duesId = savedDues(meetingId, CycleType.WEEKLY, "WED", dueDays = 0)
            billingService.bill(duesId, kst("2099-11-04T09:00")).shouldNotBeNull()

            When("같은 주 금요일로 요일을 바꾸고 금요일이 되면") {
                val dues = regularDuesRepository.findById(duesId).orElseThrow()
                dues.cycleDay = "FRI"
                regularDuesRepository.save(dues)
                val dueIds = billingService.findDueRegularDuesIds(kst("2099-11-06T09:00"))

                Then("같은 주라 다시 청구하지 않는다") {
                    dueIds shouldNotContain duesId
                    settlementsOf(duesId) shouldBe 1
                }
            }

            When("다음 주 금요일이 되면") {
                val now = kst("2099-11-13T09:00")
                val settlementId = billingService.bill(duesId, now).shouldNotBeNull()

                Then("새 회차로 청구하고 dueDays 0이면 당일 23:59가 마감이다") {
                    val settlement = settlementRepository.findById(settlementId).orElseThrow()
                    settlement.billingPeriod shouldBe LocalDate.parse("2099-11-09")
                    settlement.dueAt.toInstant() shouldBe kst("2099-11-13T23:59").toInstant()
                }
            }
        }

        Given("청구하면 안 되는 정기 회비가 있을 때") {
            val activeMeetingId = savedMeeting(memberCount = 1)
            val inactiveDuesId = savedDues(activeMeetingId, CycleType.MONTHLY, "LAST", isActive = false)
            val endedMeetingId = savedMeeting(memberCount = 1, ended = true)
            val endedMeetingDuesId = savedDues(endedMeetingId, CycleType.MONTHLY, "LAST")
            val emptyMeetingId = savedMeeting(memberCount = 0)
            val emptyMeetingDuesId = savedDues(emptyMeetingId, CycleType.MONTHLY, "LAST")
            val now = kst("2099-11-30T09:00")

            When("청구일에 확인하면") {
                val dueIds = billingService.findDueRegularDuesIds(now)

                Then("비활성화한 정기 회비는 대상이 아니다") {
                    dueIds shouldNotContain inactiveDuesId
                }

                Then("종료된 모임이나 구성원이 없는 모임은 청구하지 않는다") {
                    billingService.bill(endedMeetingDuesId, now).shouldBeNull()
                    billingService.bill(emptyMeetingDuesId, now).shouldBeNull()
                    settlementsOf(endedMeetingDuesId) shouldBe 0
                    settlementsOf(emptyMeetingDuesId) shouldBe 0
                }
            }
        }

        Given("매월 10일 09:00 정기 회비를 청구일 당일에 등록했을 때") {
            val meetingId = savedMeeting(memberCount = 1)
            val afterSendTimeId = savedDues(meetingId, CycleType.MONTHLY, "10", createdAt = kst("2099-11-10T10:00"))
            val beforeSendTimeId = savedDues(meetingId, CycleType.MONTHLY, "10", createdAt = kst("2099-11-10T08:00"))
            val noCreatedAtId = savedDues(meetingId, CycleType.MONTHLY, "10", createdAt = null)

            When("그날 발송 시각이 지난 뒤 확인하면") {
                val now = kst("2099-11-10T10:01")
                val dueIds = billingService.findDueRegularDuesIds(now)

                Then("발송 시각 이후에 등록한 설정은 이번 회차를 청구하지 않는다") {
                    dueIds shouldNotContain afterSendTimeId
                    billingService.bill(afterSendTimeId, now).shouldBeNull()
                    settlementsOf(afterSendTimeId) shouldBe 0
                }

                Then("발송 시각 전에 등록한 설정과 등록 일시가 없는 기존 설정은 청구한다") {
                    dueIds shouldContain beforeSendTimeId
                    dueIds shouldContain noCreatedAtId
                }
            }

            When("다음 달 청구일이 되면") {
                val now = kst("2099-12-10T09:00")

                Then("발송 시각 이후에 등록한 설정도 청구한다") {
                    billingService.findDueRegularDuesIds(now) shouldContain afterSendTimeId
                    billingService.bill(afterSendTimeId, now).shouldNotBeNull()
                }
            }
        }

        Given("정산 총액이 Int 범위 경계인 정기 회비가 있을 때") {
            val fitMeetingId = savedMeeting(memberCount = 1)
            val fitDuesId = savedDues(fitMeetingId, CycleType.MONTHLY, "LAST", amount = Int.MAX_VALUE)
            val overMeetingId = savedMeeting(memberCount = 2)
            val overDuesId = savedDues(overMeetingId, CycleType.MONTHLY, "LAST", amount = Int.MAX_VALUE / 2 + 1)
            val now = kst("2099-10-31T09:00")

            When("총액이 Int 최댓값이면") {
                val settlementId = billingService.bill(fitDuesId, now).shouldNotBeNull()

                Then("정상 청구된다") {
                    settlementRepository.findById(settlementId).orElseThrow().totalAmount shouldBe Int.MAX_VALUE
                }
            }

            When("총액이 Int 최댓값을 넘으면") {
                Then("음수로 저장하지 않고 청구에 실패한다") {
                    shouldThrow<IllegalArgumentException> { billingService.bill(overDuesId, now) }
                    settlementsOf(overDuesId) shouldBe 0
                }
            }
        }

        Given("공지 자동 발행을 켠 정기 회비가 있을 때") {
            val meetingId = savedMeeting(memberCount = 2)
            val duesId = savedDues(meetingId, CycleType.MONTHLY, "1", postNotice = true)

            When("청구하면") {
                val settlementId = billingService.bill(duesId, kst("2099-12-01T09:00")).shouldNotBeNull()

                Then("납부 안내 공지가 등록자 이름으로 만들어지고 회차 정산에 연결된다") {
                    val notice = noticeRepository.findAllByMeetingId(meetingId, PageRequest.of(0, 20)).content.single()
                    notice.title shouldBe "[회비] 월 회비 (2099년 12월 1일)"
                    notice.membersId shouldBe 1L
                    notice.content shouldBe "회비명: 월 회비\n" +
                        "1인당 금액: 10,000원\n" +
                        "입금 계좌: 국민은행 123456789012 (김모아)\n" +
                        "납부 기한: 2099-12-08 23:59"
                    settlementRepository.findById(settlementId).orElseThrow().noticeId shouldBe notice.id
                }
            }
        }

        Given("같은 날 청구하는 정기 회비가 여럿이고 일부는 이번 회차, 일부는 지난 회차만 청구되었을 때") {
            val meetingId = savedMeeting(memberCount = 1)
            val billedLastMonthId = savedDues(meetingId, CycleType.MONTHLY, "15")
            val billedThisMonthId = savedDues(meetingId, CycleType.MONTHLY, "15")
            billingService.bill(billedLastMonthId, kst("2099-10-15T09:00")).shouldNotBeNull()
            billingService.bill(billedThisMonthId, kst("2099-11-15T09:00")).shouldNotBeNull()

            When("청구 대상을 조회하면") {
                val dueIds = billingService.findDueRegularDuesIds(kst("2099-11-15T09:30"))

                Then("이번 회차가 청구된 회비만 제외되고 지난 회차만 청구된 회비는 청구 대상이다") {
                    dueIds shouldContain billedLastMonthId
                    dueIds shouldNotContain billedThisMonthId
                }
            }
        }
    }

    private fun kst(dateTime: String): OffsetDateTime = OffsetDateTime.parse("$dateTime:00+09:00")

    private fun savedMeeting(memberCount: Int, ended: Boolean = false): Long {
        val meeting = meetingRepository.save(
            Meeting(
                name = "모임",
                inviteCode = UUID.randomUUID().toString().take(8),
                endedAt = if (ended) OffsetDateTime.now() else null
            )
        )
        val meetingId = checkNotNull(meeting.id)
        repeat(memberCount) { index ->
            memberRepository.save(
                Member(meetingId = meetingId, userId = 100L + index, role = if (index == 0) MemberRole.ADMIN else MemberRole.MEMBER)
            )
        }
        return meetingId
    }

    private fun savedDues(
        meetingId: Long,
        cycleType: CycleType,
        cycleDay: String,
        dueDays: Int = 7,
        isActive: Boolean = true,
        postNotice: Boolean = false,
        amount: Int = 10000,
        createdAt: OffsetDateTime? = OffsetDateTime.now()
    ): Long =
        checkNotNull(
            regularDuesRepository.save(
                RegularDues(
                    meetingId = meetingId,
                    createdBy = 1L,
                    title = "월 회비",
                    amount = amount,
                    cycleType = cycleType,
                    cycleDay = cycleDay,
                    sendTime = LocalTime.of(9, 0),
                    dueDays = dueDays,
                    bankName = "국민은행",
                    accountNumber = "123456789012",
                    accountHolder = "김모아",
                    isActive = isActive,
                    postNotice = postNotice,
                    createdAt = createdAt
                )
            ).id
        )

    private fun settlementsOf(regularDuesId: Long): Int =
        settlementRepository.findAll().count { it.regularDuesId == regularDuesId }
}

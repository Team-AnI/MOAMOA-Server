package com.teamani.moamoa.settlement.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.entity.SettlementMember
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.repository.AppUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.OffsetDateTime

class SettlementServiceTests : BehaviorSpec({

    val settlementRepository: SettlementRepository = mock(SettlementRepository::class.java)
    val settlementMemberRepository: SettlementMemberRepository = mock(SettlementMemberRepository::class.java)
    val noticeService: NoticeService = mock(NoticeService::class.java)
    val memberService: MemberService = mock(MemberService::class.java)
    val memberRepository: MemberRepository = mock(MemberRepository::class.java)
    val appUserRepository: AppUserRepository = mock(AppUserRepository::class.java)
    val scheduleRepository: ScheduleRepository = mock(ScheduleRepository::class.java)
    val settlementService = SettlementService(
        settlementRepository,
        settlementMemberRepository,
        noticeService,
        memberService,
        memberRepository,
        appUserRepository,
        scheduleRepository
    )

    // 기본: 사용자 1은 모임 1의 관리자(구성원 1)
    val admin = Member(id = 1L, meetingId = 1L, userId = 1L, role = MemberRole.ADMIN)
    `when`(memberService.validateAdmin(1L, 1L)).thenReturn(admin)
    `when`(memberService.validateMember(1L, 1L)).thenReturn(admin)
    // 구성원 ID 9000 이상은 다른 모임 구성원, 8000번대는 모임을 나가 구성원 행 없음, 그 외는 모임 1 구성원 (사용자 ID = 구성원 ID + 100)
    doAnswer { invocation ->
        invocation.getArgument<Iterable<Long>>(0).filterNot { it in 8000L..8999L }.map { id ->
            Member(id = id, meetingId = if (id >= 9000L) 2L else 1L, userId = id + 100, role = MemberRole.MEMBER)
        }
    }.`when`(memberRepository).findAllById(any())
    doAnswer { invocation ->
        invocation.getArgument<Iterable<Long>>(0).map { id ->
            AppUser(
                id = id,
                name = "사용자$id",
                email = "user$id@moamoa.test",
                password = "password",
                termsAgreedAt = OffsetDateTime.parse("2026-10-01T00:00:00Z")
            )
        }
    }.`when`(appUserRepository).findAllById(any())
    `when`(scheduleRepository.findByIdAndMeetingId(20L, 1L)).thenReturn(
        Schedule(id = 20L, meetingId = 1L, title = "10월 정기 모임", startsAt = OffsetDateTime.parse("2026-10-11T10:00:00Z"))
    )

    afterEach { clearInvocations(settlementRepository, settlementMemberRepository, noticeService, memberService) }

    fun settlement(id: Long, createdAt: String = "2026-10-14T01:00:00Z", title: String = "10월 정기 모임 식비") = Settlement(
        id = id,
        meetingId = 1L,
        scheduleId = 20L,
        createdBy = 1L,
        title = title,
        totalAmount = 45000,
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아",
        dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z"),
        createdAt = OffsetDateTime.parse(createdAt)
    )

    Given("1/N 정산 금액을 계산할 때") {
        When("총액이 인원수로 나누어떨어지면") {
            val amounts = splitAmount(45000, listOf(3L, 5L, 7L), 3L)

            Then("모두 같은 금액을 부담한다") {
                amounts shouldBe mapOf(3L to 15000, 5L to 15000, 7L to 15000)
            }
        }

        When("나누어떨어지지 않고 생성자가 대상에 포함되면") {
            val amounts = splitAmount(10000, listOf(5L, 1L, 9L), 1L)

            Then("원 단위 미만은 절사하고 잔여 금액은 생성자가 부담하며 합계는 총액과 같다") {
                amounts shouldBe mapOf(5L to 3333, 1L to 3334, 9L to 3333)
                amounts.values.sum() shouldBe 10000
            }
        }

        When("나누어떨어지지 않고 생성자가 대상에 없으면") {
            val amounts = splitAmount(10000, listOf(5L, 2L, 9L), 1L)

            Then("가입순 첫 번째(구성원 ID가 가장 작은) 구성원이 잔여 금액을 부담한다") {
                amounts shouldBe mapOf(5L to 3333, 2L to 3334, 9L to 3333)
                amounts.values.sum() shouldBe 10000
            }
        }

        When("대상이 한 명이면") {
            val amounts = splitAmount(12345, listOf(4L), 1L)

            Then("총액 전부를 부담한다") {
                amounts shouldBe mapOf(4L to 12345)
            }
        }
    }

    Given("정산 생성 요청 데이터가 주어졌을 때") {
        val request = SettlementCreateRequest(
            scheduleId = 20L,
            title = "10월 정기 모임 식비",
            totalAmount = 10000,
            memberIds = listOf(1L, 5L, 9L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
        )

        `when`(settlementRepository.save(any(Settlement::class.java))).thenReturn(settlement(40L))

        When("createSettlement 메서드를 호출하면") {
            val response = settlementService.createSettlement(1L, 1L, request)

            Then("정산과 구성원별 납부 항목이 저장되고 금액 합계는 총액과 같다") {
                @Suppress("UNCHECKED_CAST")
                val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<SettlementMember>>
                verify(settlementMemberRepository).saveAll(captor.capture())
                val payments = captor.value

                payments.map { it.memberId } shouldBe listOf(1L, 5L, 9L)
                payments.all { it.settlementId == 40L && it.status == PaymentStatus.UNPAID } shouldBe true
                payments.first { it.memberId == 1L }.amount shouldBe 3334
                payments.sumOf { it.amount } shouldBe 10000

                response.settlementId shouldBe 40L
                response.memberCount shouldBe 3
                response.status shouldBe SettlementStatus.IN_PROGRESS
                response.noticeId.shouldBeNull()
                verifyNoInteractions(noticeService)
            }
        }
    }

    Given("공지 자동 연동을 켠 정산 생성 요청이 주어졌을 때") {
        val request = SettlementCreateRequest(
            scheduleId = 20L,
            title = "10월 정기 모임 식비",
            totalAmount = 10000,
            memberIds = listOf(1L, 5L, 9L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z"),
            postNotice = true
        )
        val expectedNotice = NoticeCreateRequest(
            title = "[정산] 10월 정기 모임 식비",
            content = "정산명: 10월 정기 모임 식비\n" +
                "기본 1인당 금액: 3,333원\n" +
                "입금 계좌: 국민은행 123456789012 (김모아)\n" +
                "납부 기한: 2026-10-20 23:59\n" +
                "개인별 실제 납부 금액은 정산 상세에서 확인해 주세요."
        )

        `when`(settlementRepository.save(any(Settlement::class.java))).thenReturn(settlement(40L))
        `when`(noticeService.createNoticeByMember(1L, 1L, expectedNotice))
            .thenReturn(NoticeCreateResponse(noticeId = 12L, title = "[정산] 10월 정기 모임 식비"))

        When("createSettlement 메서드를 호출하면") {
            val response = settlementService.createSettlement(1L, 1L, request)

            Then("정산 안내 공지가 생성되고 정산에 공지 ID가 연결된다") {
                verify(noticeService).createNoticeByMember(1L, 1L, expectedNotice)
                response.noticeId shouldBe 12L
            }
        }
    }

    Given("정산명이 길어 공지 제목이 50자를 넘는 정산 생성 요청이 주어졌을 때") {
        val longTitle = "가".repeat(50)
        val request = SettlementCreateRequest(
            title = longTitle,
            totalAmount = 10000,
            memberIds = listOf(1L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z"),
            postNotice = true
        )
        val expectedNotice = NoticeCreateRequest(
            title = ("[정산] " + longTitle).take(50),
            content = "정산명: $longTitle\n" +
                "기본 1인당 금액: 10,000원\n" +
                "입금 계좌: 국민은행 123456789012 (김모아)\n" +
                "납부 기한: 2026-10-20 23:59\n" +
                "개인별 실제 납부 금액은 정산 상세에서 확인해 주세요."
        )

        `when`(settlementRepository.save(any(Settlement::class.java))).thenReturn(settlement(41L, title = longTitle))
        `when`(noticeService.createNoticeByMember(1L, 1L, expectedNotice))
            .thenReturn(NoticeCreateResponse(noticeId = 13L, title = expectedNotice.title.orEmpty()))

        When("createSettlement 메서드를 호출하면") {
            val response = settlementService.createSettlement(1L, 1L, request)

            Then("공지 제목은 앞 50자까지만 쓰고 공지 내용에는 정산명 전체가 들어간다") {
                verify(noticeService).createNoticeByMember(1L, 1L, expectedNotice)
                expectedNotice.title.orEmpty().length shouldBe 50
                response.noticeId shouldBe 13L
            }
        }
    }

    Given("정산 대상 구성원이 비어 있는 생성 요청이 주어졌을 때") {
        val request = SettlementCreateRequest(
            title = "10월 정기 모임 식비",
            totalAmount = 10000,
            memberIds = emptyList(),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
        )

        When("createSettlement 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                settlementService.createSettlement(1L, 1L, request)
            }

            Then("0으로 나누기 전에 VALIDATION_ERROR 예외가 발생하고 정산은 저장되지 않는다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verify(settlementRepository, never()).save(any(Settlement::class.java))
                verify(settlementMemberRepository, never()).saveAll(anyList())
            }
        }
    }

    Given("정산 대상 구성원이 중복된 생성 요청이 주어졌을 때") {
        val request = SettlementCreateRequest(
            title = "10월 정기 모임 식비",
            totalAmount = 10000,
            memberIds = listOf(1L, 5L, 5L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
        )

        When("createSettlement 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                settlementService.createSettlement(1L, 1L, request)
            }

            Then("VALIDATION_ERROR 예외가 발생하고 정산은 저장되지 않는다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verify(settlementRepository, never()).save(any(Settlement::class.java))
                verify(settlementMemberRepository, never()).saveAll(anyList())
            }
        }
    }

    Given("총 정산 금액이 대상 인원수보다 적은 생성 요청이 주어졌을 때") {
        val request = SettlementCreateRequest(
            title = "10월 정기 모임 식비",
            totalAmount = 2,
            memberIds = listOf(1L, 2L, 3L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
        )

        When("createSettlement 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                settlementService.createSettlement(1L, 1L, request)
            }

            Then("VALIDATION_ERROR 예외가 발생하고 0원 납부 항목은 만들어지지 않는다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verify(settlementRepository, never()).save(any(Settlement::class.java))
                verify(settlementMemberRepository, never()).saveAll(anyList())
            }
        }
    }

    Given("모임에 정산이 있고 현재 구성원이 일부 정산의 대상일 때") {
        val newer = settlement(41L, "2026-10-15T01:00:00Z")
        val older = settlement(40L, "2026-10-14T01:00:00Z")

        `when`(settlementRepository.findAllByMeetingIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(listOf(newer, older))
        `when`(settlementMemberRepository.findAllBySettlementIdIn(listOf(41L, 40L))).thenReturn(
            listOf(
                SettlementMember(id = 90L, settlementId = 41L, memberId = 5L, amount = 22500),
                SettlementMember(id = 91L, settlementId = 41L, memberId = 9L, amount = 22500),
                SettlementMember(
                    id = 81L, settlementId = 40L, memberId = 1L, amount = 15000,
                    status = PaymentStatus.PAID, confirmedAt = OffsetDateTime.parse("2026-10-16T03:00:00Z")
                ),
                SettlementMember(id = 82L, settlementId = 40L, memberId = 5L, amount = 15000),
                SettlementMember(id = 83L, settlementId = 40L, memberId = 9L, amount = 15000)
            )
        )

        When("getSettlements 메서드를 호출하면") {
            val response = settlementService.getSettlements(1L, 1L)

            Then("최신순으로 대상 인원과 내 납부 정보를 반환하고 대상이 아닌 정산의 내 정보는 null이다") {
                response.settlements.map { it.settlementId } shouldBe listOf(41L, 40L)

                val notTarget = response.settlements[0]
                notTarget.memberCount shouldBe 2
                notTarget.myAmount.shouldBeNull()
                notTarget.myPaymentStatus.shouldBeNull()
                notTarget.myConfirmedAt.shouldBeNull()

                val target = response.settlements[1]
                target.memberCount shouldBe 3
                target.myAmount shouldBe 15000
                target.myPaymentStatus shouldBe PaymentStatus.PAID
                target.myConfirmedAt.toString() shouldBe "2026-10-16T12:00+09:00"
                target.dueAt.toString() shouldBe "2026-10-20T23:59+09:00"
                target.createdAt.toString() shouldBe "2026-10-14T10:00+09:00"
            }
        }
    }

    Given("모임에 정산이 하나도 없을 때") {
        `when`(memberService.validateMember(2L, 1L)).thenReturn(Member(id = 11L, meetingId = 2L, userId = 1L, role = MemberRole.MEMBER))
        `when`(settlementRepository.findAllByMeetingIdOrderByCreatedAtDescIdDesc(2L)).thenReturn(emptyList())

        When("getSettlements 메서드를 호출하면") {
            val response = settlementService.getSettlements(2L, 1L)

            Then("빈 목록을 반환하고 납부 항목은 조회하지 않는다") {
                response.settlements shouldBe emptyList()
                verify(settlementMemberRepository, never()).findAllBySettlementIdIn(anyList())
            }
        }
    }

    Given("정산 상세 조회 대상 정산과 납부 항목이 있을 때") {
        `when`(settlementRepository.findByIdAndMeetingId(40L, 1L)).thenReturn(settlement(40L))
        `when`(settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(40L)).thenReturn(
            listOf(
                SettlementMember(
                    id = 81L, settlementId = 40L, memberId = 1L, amount = 15000,
                    status = PaymentStatus.PENDING, submittedAt = OffsetDateTime.parse("2026-10-15T11:00:00Z")
                ),
                SettlementMember(id = 82L, settlementId = 40L, memberId = 5L, amount = 15000, status = PaymentStatus.PAID),
                SettlementMember(id = 83L, settlementId = 40L, memberId = 9L, amount = 15000),
                SettlementMember(id = 84L, settlementId = 40L, memberId = 8001L, amount = 15000),
                // #50 전 데이터처럼 다른 모임(2) 구성원이 대상에 들어간 경우
                SettlementMember(id = 85L, settlementId = 40L, memberId = 9001L, amount = 15000)
            )
        )

        When("getSettlement 메서드를 호출하면") {
            val response = settlementService.getSettlement(1L, 1L, 40L)

            Then("내 납부 정보, 상태별 요약, 연결 일정, 구성원별 납부 목록(이름 포함)을 반환한다") {
                val myPayment = response.myPayment.shouldNotBeNull()
                myPayment.paymentId shouldBe 81L
                myPayment.status shouldBe PaymentStatus.PENDING

                response.summary.totalCount shouldBe 5
                response.summary.unpaidCount shouldBe 3
                response.summary.pendingCount shouldBe 1
                response.summary.paidCount shouldBe 1
                response.summary.paidAmount shouldBe 15000

                val payments = response.payments.shouldNotBeNull()
                payments.map { it.memberId } shouldBe listOf(1L, 5L, 9L, 8001L, 9001L)
                // 모임을 나간 구성원(8001)과 다른 모임 구성원(9001)은 "탈퇴한 사용자"
                payments.map { it.userName } shouldBe listOf("사용자101", "사용자105", "사용자109", "탈퇴한 사용자", "탈퇴한 사용자")
                payments[0].submittedAt.toString() shouldBe "2026-10-15T20:00+09:00"
                response.noticeId.shouldBeNull()
                response.scheduleTitle shouldBe "10월 정기 모임"
                response.scheduleStartAt.toString() shouldBe "2026-10-11T19:00+09:00"
            }
        }

        When("일반 구성원(MEMBER)이 조회하면") {
            `when`(memberService.validateMember(1L, 105L))
                .thenReturn(Member(id = 5L, meetingId = 1L, userId = 105L, role = MemberRole.MEMBER))
            val response = settlementService.getSettlement(1L, 105L, 40L)

            Then("본인 납부 정보와 합계만 반환하고 구성원별 납부 목록은 null이다") {
                response.myPayment.shouldNotBeNull().paymentId shouldBe 82L
                response.summary.totalCount shouldBe 5
                response.payments.shouldBeNull()
            }
        }
    }

    Given("정산 대상 구성원이 납부 완료를 제출할 때") {
        `when`(settlementRepository.findByIdAndMeetingIdForUpdate(40L, 1L)).thenReturn(settlement(40L))

        When("미납 상태이고 이전에 반려된 적이 있으면") {
            val payment = SettlementMember(
                id = 81L, settlementId = 40L, memberId = 1L, amount = 15000, rejectionReason = "입금 내역 없음"
            )
            `when`(settlementMemberRepository.findBySettlementIdAndMemberId(40L, 1L)).thenReturn(payment)
            val response = settlementService.submitPayment(1L, 1L, 40L)

            Then("확인 대기로 바뀌고 제출 일시가 기록되며 이전 반려 사유는 지워진다") {
                payment.status shouldBe PaymentStatus.PENDING
                payment.submittedAt.shouldNotBeNull()
                payment.rejectionReason.shouldBeNull()
                response.paymentId shouldBe 81L
                response.status shouldBe PaymentStatus.PENDING
            }
        }

        When("이미 확인 대기 상태이면") {
            `when`(settlementMemberRepository.findBySettlementIdAndMemberId(40L, 1L)).thenReturn(
                SettlementMember(id = 81L, settlementId = 40L, memberId = 1L, amount = 15000, status = PaymentStatus.PENDING)
            )
            val exception = shouldThrow<ApiException> { settlementService.submitPayment(1L, 1L, 40L) }

            Then("CONFLICT 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.CONFLICT
            }
        }

        When("정산 대상이 아니면") {
            `when`(settlementMemberRepository.findBySettlementIdAndMemberId(40L, 1L)).thenReturn(null)
            val exception = shouldThrow<ApiException> { settlementService.submitPayment(1L, 1L, 40L) }

            Then("FORBIDDEN 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
    }

    Given("관리자가 확인 대기 중인 납부를 승인할 때") {
        fun pendingPayment() =
            SettlementMember(id = 81L, settlementId = 40L, memberId = 1L, amount = 15000, status = PaymentStatus.PENDING)

        When("다른 대상자가 아직 완납하지 않았으면") {
            val settlement = settlement(40L)
            val payment = pendingPayment()
            `when`(settlementRepository.findByIdAndMeetingIdForUpdate(40L, 1L)).thenReturn(settlement)
            `when`(settlementMemberRepository.findByIdAndSettlementId(81L, 40L)).thenReturn(payment)
            `when`(settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(40L)).thenReturn(
                listOf(payment, SettlementMember(id = 82L, settlementId = 40L, memberId = 5L, amount = 15000))
            )
            val response = settlementService.approvePayment(1L, 1L, 40L, 81L)

            Then("완납으로 바뀌고 승인 일시가 기록되며 정산은 진행 중으로 남는다") {
                payment.status shouldBe PaymentStatus.PAID
                payment.confirmedAt.shouldNotBeNull()
                response.status shouldBe PaymentStatus.PAID
                settlement.status shouldBe SettlementStatus.IN_PROGRESS
            }
        }

        When("마지막 대상자까지 완납하면") {
            val settlement = settlement(40L)
            val payment = pendingPayment()
            `when`(settlementRepository.findByIdAndMeetingIdForUpdate(40L, 1L)).thenReturn(settlement)
            `when`(settlementMemberRepository.findByIdAndSettlementId(81L, 40L)).thenReturn(payment)
            `when`(settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(40L)).thenReturn(
                listOf(
                    payment,
                    SettlementMember(id = 82L, settlementId = 40L, memberId = 5L, amount = 15000, status = PaymentStatus.PAID)
                )
            )
            settlementService.approvePayment(1L, 1L, 40L, 81L)

            Then("정산이 완료 상태가 된다") {
                settlement.status shouldBe SettlementStatus.COMPLETED
            }
        }

        When("확인 대기 상태가 아니면") {
            `when`(settlementRepository.findByIdAndMeetingIdForUpdate(40L, 1L)).thenReturn(settlement(40L))
            `when`(settlementMemberRepository.findByIdAndSettlementId(81L, 40L)).thenReturn(
                SettlementMember(id = 81L, settlementId = 40L, memberId = 1L, amount = 15000)
            )
            val exception = shouldThrow<ApiException> { settlementService.approvePayment(1L, 1L, 40L, 81L) }

            Then("CONFLICT 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.CONFLICT
            }
        }

        When("다른 정산의 납부 항목이면") {
            `when`(settlementRepository.findByIdAndMeetingIdForUpdate(40L, 1L)).thenReturn(settlement(40L))
            `when`(settlementMemberRepository.findByIdAndSettlementId(999L, 40L)).thenReturn(null)
            val exception = shouldThrow<ApiException> { settlementService.approvePayment(1L, 1L, 40L, 999L) }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    Given("관리자가 확인 대기 중인 납부를 반려할 때") {
        `when`(settlementRepository.findByIdAndMeetingIdForUpdate(40L, 1L)).thenReturn(settlement(40L))

        When("반려 사유를 함께 보내면") {
            val payment = SettlementMember(id = 81L, settlementId = 40L, memberId = 1L, amount = 15000, status = PaymentStatus.PENDING)
            `when`(settlementMemberRepository.findByIdAndSettlementId(81L, 40L)).thenReturn(payment)
            val response = settlementService.rejectPayment(1L, 1L, 40L, 81L, "입금 내역을 확인할 수 없습니다.")

            Then("미납으로 돌아가고 반려 사유가 저장된다") {
                payment.status shouldBe PaymentStatus.UNPAID
                payment.rejectionReason shouldBe "입금 내역을 확인할 수 없습니다."
                response.status shouldBe PaymentStatus.UNPAID
                response.rejectionReason shouldBe "입금 내역을 확인할 수 없습니다."
            }
        }

        When("이미 완납된 납부면") {
            `when`(settlementMemberRepository.findByIdAndSettlementId(81L, 40L)).thenReturn(
                SettlementMember(id = 81L, settlementId = 40L, memberId = 1L, amount = 15000, status = PaymentStatus.PAID)
            )
            val exception = shouldThrow<ApiException> { settlementService.rejectPayment(1L, 1L, 40L, 81L, null) }

            Then("CONFLICT 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.CONFLICT
            }
        }
    }

    Given("없거나 다른 모임에 속한 정산을 조회하려 할 때") {
        When("getSettlement 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                settlementService.getSettlement(1L, 1L, 999L)
            }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    Given("구성원 ID와 사용자 ID가 다른 관리자가 정산을 만들 때") {
        `when`(memberService.validateAdmin(1L, 10L)).thenReturn(Member(id = 3L, meetingId = 1L, userId = 10L, role = MemberRole.ADMIN))
        `when`(settlementRepository.save(any(Settlement::class.java))).thenReturn(settlement(42L))
        `when`(noticeService.createNoticeByMember(anyLong(), anyLong(), any(NoticeCreateRequest::class.java) ?: NoticeCreateRequest("t", "c")))
            .thenReturn(NoticeCreateResponse(noticeId = 14L, title = "[정산] 10월 정기 모임 식비"))
        val request = SettlementCreateRequest(
            title = "10월 정기 모임 식비",
            totalAmount = 10000,
            memberIds = listOf(3L, 5L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z"),
            postNotice = true
        )

        When("createSettlement 메서드를 호출하면") {
            settlementService.createSettlement(1L, 10L, request)

            Then("사용자 ID가 아닌 관리자의 구성원 ID를 생성자와 안내 공지 작성자로 쓴다") {
                val captor = ArgumentCaptor.forClass(Settlement::class.java)
                verify(settlementRepository).save(captor.capture())
                captor.value.createdBy shouldBe 3L
                verify(noticeService).createNoticeByMember(
                    anyLong(),
                    org.mockito.ArgumentMatchers.eq(3L),
                    any(NoticeCreateRequest::class.java) ?: NoticeCreateRequest("t", "c")
                )
            }
        }
    }

    Given("정산 대상이나 연결 일정이 해당 모임에 없을 때") {
        fun request(memberIds: List<Long>, scheduleId: Long? = null) = SettlementCreateRequest(
            title = "10월 정기 모임 식비",
            scheduleId = scheduleId,
            totalAmount = 10000,
            memberIds = memberIds,
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
        )

        listOf(
            "다른 모임 구성원이 대상에 포함되면" to request(listOf(1L, 9001L)),
            "다른 모임의 일정을 연결하면" to request(listOf(1L, 5L), scheduleId = 21L)
        ).forEach { (case, request) ->
            When(case) {
                val exception = shouldThrow<ApiException> { settlementService.createSettlement(1L, 1L, request) }

                Then("NOT_FOUND 예외가 발생하고 정산을 저장하지 않는다") {
                    exception.errorCode shouldBe ErrorCode.NOT_FOUND
                    verifyNoInteractions(settlementRepository, settlementMemberRepository, noticeService)
                }
            }
        }
    }

    Given("모임 관리자(ADMIN)가 아닌 사용자가 정산 생성·납부 승인·반려를 요청할 때") {
        val memberUserId = 2L
        `when`(memberService.validateAdmin(1L, memberUserId))
            .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 관리자만 요청할 수 있습니다."))
        val createRequest = SettlementCreateRequest(
            title = "10월 정기 모임 식비",
            totalAmount = 10000,
            memberIds = listOf(1L, 5L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
        )

        listOf<Pair<String, () -> Any>>(
            "createSettlement" to { settlementService.createSettlement(1L, memberUserId, createRequest) },
            "approvePayment" to { settlementService.approvePayment(1L, memberUserId, 40L, 81L) },
            "rejectPayment" to { settlementService.rejectPayment(1L, memberUserId, 40L, 81L, null) }
        ).forEach { (method, call) ->
            When("$method 메서드를 호출하면") {
                val exception = shouldThrow<ApiException> { call() }

                Then("FORBIDDEN 예외가 발생하고 정산·납부 항목을 조회·저장하지 않는다") {
                    exception.errorCode shouldBe ErrorCode.FORBIDDEN
                    verify(memberService).validateAdmin(1L, memberUserId)
                    verifyNoInteractions(settlementRepository, settlementMemberRepository, noticeService)
                }
            }
        }
    }

    Given("모임 구성원이 아닌 사용자가 정산 목록·상세 조회와 납부 완료 제출을 요청할 때") {
        val outsiderUserId = 3L
        `when`(memberService.validateMember(1L, outsiderUserId))
            .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 구성원만 요청할 수 있습니다."))

        listOf<Pair<String, () -> Any>>(
            "getSettlements" to { settlementService.getSettlements(1L, outsiderUserId) },
            "getSettlement" to { settlementService.getSettlement(1L, outsiderUserId, 40L) },
            "submitPayment" to { settlementService.submitPayment(1L, outsiderUserId, 40L) }
        ).forEach { (method, call) ->
            When("$method 메서드를 호출하면") {
                val exception = shouldThrow<ApiException> { call() }

                Then("FORBIDDEN 예외가 발생하고 정산·납부 항목을 조회하지 않는다") {
                    exception.errorCode shouldBe ErrorCode.FORBIDDEN
                    verify(memberService).validateMember(1L, outsiderUserId)
                    verifyNoInteractions(settlementRepository, settlementMemberRepository)
                }
            }
        }
    }

    Given("권한이 없는 사용자가 요청만으로 잘못된 정산 생성 요청을 보낼 때") {
        val outsiderUserId = 4L
        `when`(memberService.validateAdmin(1L, outsiderUserId))
            .thenThrow(ApiException(ErrorCode.FORBIDDEN, "모임 관리자만 요청할 수 있습니다."))
        val request = SettlementCreateRequest(
            title = "10월 정기 모임 식비",
            totalAmount = 10000,
            memberIds = listOf(1L, 1L),
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아",
            dueAt = OffsetDateTime.parse("2026-10-20T14:59:00Z")
        )

        When("대상 구성원이 중복된 정산을 생성하면") {
            val exception = shouldThrow<ApiException> { settlementService.createSettlement(1L, outsiderUserId, request) }

            Then("명세 오류 순서대로 권한(403)보다 요청 오류(400 VALIDATION_ERROR)가 먼저다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verify(memberService, never()).validateAdmin(1L, outsiderUserId)
            }
        }
    }

    Given("없거나 종료된 모임의 정산을 요청할 때") {
        val endedMeetingId = 404L
        `when`(memberService.validateAdmin(endedMeetingId, 1L))
            .thenThrow(ApiException(ErrorCode.NOT_FOUND, "모임을 찾을 수 없습니다."))
        `when`(memberService.validateMember(endedMeetingId, 1L))
            .thenThrow(ApiException(ErrorCode.NOT_FOUND, "모임을 찾을 수 없습니다."))

        listOf<Pair<String, () -> Any>>(
            "approvePayment" to { settlementService.approvePayment(endedMeetingId, 1L, 40L, 81L) },
            "getSettlements" to { settlementService.getSettlements(endedMeetingId, 1L) }
        ).forEach { (method, call) ->
            When("$method 메서드를 호출하면") {
                val exception = shouldThrow<ApiException> { call() }

                Then("NOT_FOUND 예외가 발생하고 정산을 조회하지 않는다") {
                    exception.errorCode shouldBe ErrorCode.NOT_FOUND
                    verifyNoInteractions(settlementRepository, settlementMemberRepository)
                }
            }
        }
    }
})

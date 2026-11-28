package com.teamani.moamoa.settlement

import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.notice.dto.NoticeUpdateRequest
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.entity.SettlementStatus
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.SettlementService
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.repository.AppUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 실제 트랜잭션·DB에서 정산과 안내 공지가 함께 생성·롤백되고, 공지 삭제 시 연결이 해제되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class SettlementNoticeIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var settlementService: SettlementService

    @Autowired
    lateinit var noticeService: NoticeService

    @Autowired
    lateinit var settlementRepository: SettlementRepository

    @Autowired
    lateinit var settlementMemberRepository: SettlementMemberRepository

    @MockitoSpyBean
    lateinit var noticeRepository: NoticeRepository

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    init {
        Given("안내 공지가 연결된 정산을 취소할 때") {
            val group = savedGroup()
            val created = settlementService.createSettlement(group.meetingId, group.adminUserId, request(group.memberIds, postNotice = true))
            val oldNoticeId = created.noticeId.shouldNotBeNull()

            When("사유와 함께 취소하면") {
                val response = settlementService.cancelSettlement(group.meetingId, group.adminUserId, created.settlementId, "금액 입력 오류")

                Then("기존 공지에 취소 표시를 하고, 관리자가 작성한 새 취소 공지가 정산에 연결된다") {
                    val oldNotice = noticeRepository.findById(oldNoticeId).orElseThrow()
                    oldNotice.title shouldBe "[취소] [정산] 10월 정기 모임 식비"
                    oldNotice.content.startsWith("⚠ 이 정산은 취소되었습니다. (사유: 금액 입력 오류)\n──── 기존 내용 ────\n정산명: 10월 정기 모임 식비") shouldBe true

                    val newNoticeId = response.noticeId.shouldNotBeNull()
                    settlementRepository.findById(created.settlementId).orElseThrow().noticeId shouldBe newNoticeId
                    val newNotice = noticeService.getNoticeDetail(group.meetingId, newNoticeId, group.adminUserId)
                    newNotice.title shouldBe "[정산 취소] 10월 정기 모임 식비"
                    newNotice.author.memberId shouldBe group.adminMemberId
                }
            }
        }

        Given("공백만 있는 사유로 정산을 취소할 때") {
            val group = savedGroup()
            val created = settlementService.createSettlement(group.meetingId, group.adminUserId, request(group.memberIds, postNotice = true))
            val oldNoticeId = created.noticeId.shouldNotBeNull()

            When("취소하면") {
                val response = settlementService.cancelSettlement(group.meetingId, group.adminUserId, created.settlementId, "   ")

                Then("사유 없음으로 저장·응답하고, 공지에도 사유를 쓰지 않는다") {
                    response.cancelReason shouldBe null
                    settlementRepository.findById(created.settlementId).orElseThrow().cancelReason shouldBe null
                    noticeRepository.findById(oldNoticeId).orElseThrow().content
                        .startsWith("⚠ 이 정산은 취소되었습니다.\n──── 기존 내용 ────") shouldBe true
                }
            }
        }

        Given("관리자가 안내 공지 제목을 수정하는 도중에 정산을 취소할 때") {
            val group = savedGroup()
            val created = settlementService.createSettlement(group.meetingId, group.adminUserId, request(group.memberIds, postNotice = true))
            val noticeId = created.noticeId.shouldNotBeNull()

            When("수정이 공지를 읽은 직후 멈춘 사이에 취소를 요청하면") {
                // 수정이 공지를 읽은 직후(첫 조회)에만 멈춤. 잠금 조회·일반 조회 모두 걸어 잠금이 없을 때도 같은 지점에서 멈추게 함
                val pause = Pause()
                doAnswer(afterReal { pause.hold() }).`when`(noticeRepository).findByIdAndMeetingIdForUpdate(anyLong(), anyLong())
                doAnswer(afterReal { pause.hold() }).`when`(noticeRepository).findById(anyLong())
                val executor = Executors.newFixedThreadPool(2)
                val (updateError, cancelError) = try {
                    val update = executor.submit(Callable {
                        runCatching {
                            noticeService.updateNotice(group.meetingId, noticeId, group.adminUserId, NoticeUpdateRequest(title = Optional.of("10월 식비 안내")))
                        }.exceptionOrNull()
                    })
                    pause.awaitPaused()
                    val cancel = executor.submit(Callable {
                        runCatching { settlementService.cancelSettlement(group.meetingId, group.adminUserId, created.settlementId, "금액 입력 오류") }.exceptionOrNull()
                    })
                    // 취소가 공지 잠금을 기다리는 상태가 되도록 잠시 둔 뒤 수정을 진행
                    Thread.sleep(WAIT_MILLIS)
                    pause.release()
                    update.get(10, TimeUnit.SECONDS) to cancel.get(10, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                    reset(noticeRepository)
                }

                Then("취소는 수정이 끝난 뒤 그 결과 위에 처리되어, 수정한 제목 앞에 [취소]가 붙고 본문의 취소 안내도 남는다") {
                    updateError.shouldBeNull()
                    cancelError.shouldBeNull()
                    val notice = noticeRepository.findById(noticeId).orElseThrow()
                    notice.title shouldBe "[취소] 10월 식비 안내"
                    notice.content.startsWith("⚠ 이 정산은 취소되었습니다. (사유: 금액 입력 오류)") shouldBe true
                }
            }
        }

        Given("새 취소 공지 저장이 실패하는 상황에서 정산을 취소할 때") {
            val group = savedGroup()
            val created = settlementService.createSettlement(group.meetingId, group.adminUserId, request(group.memberIds, postNotice = true))
            val oldNoticeId = created.noticeId.shouldNotBeNull()

            When("취소하면") {
                doThrow(IllegalStateException("공지 저장 실패")).`when`(noticeRepository).save(any(Notice::class.java))
                val exception = shouldThrow<IllegalStateException> {
                    settlementService.cancelSettlement(group.meetingId, group.adminUserId, created.settlementId, "금액 입력 오류")
                }
                reset(noticeRepository)

                Then("정산 취소와 기존 공지 수정이 모두 롤백되어 그대로 남는다") {
                    exception.message shouldBe "공지 저장 실패"
                    val settlement = settlementRepository.findById(created.settlementId).orElseThrow()
                    settlement.status shouldBe SettlementStatus.IN_PROGRESS
                    settlement.canceledAt.shouldBeNull()
                    settlement.noticeId shouldBe oldNoticeId
                    noticeRepository.findById(oldNoticeId).orElseThrow().title shouldBe "[정산] 10월 정기 모임 식비"
                    noticesOf(group.meetingId).size shouldBe 1
                }
            }
        }

        Given("공지 저장이 실패하는 상황에서 공지 자동 등록을 요청했을 때") {
            val group = savedGroup()
            val paymentCountBefore = settlementMemberRepository.count()

            When("정산을 생성하면") {
                doThrow(IllegalStateException("공지 저장 실패")).`when`(noticeRepository).save(any(Notice::class.java))
                val exception = shouldThrow<IllegalStateException> {
                    settlementService.createSettlement(group.meetingId, group.adminUserId, request(group.memberIds, postNotice = true))
                }
                reset(noticeRepository)

                Then("예외가 전달되고 정산·납부 항목·공지가 모두 남지 않는다") {
                    exception.message shouldBe "공지 저장 실패"
                    settlementRepository.findAllByMeetingIdOrderByCreatedAtDescIdDesc(group.meetingId) shouldBe emptyList()
                    settlementMemberRepository.count() shouldBe paymentCountBefore
                    noticesOf(group.meetingId) shouldBe emptyList()
                }
            }
        }

        Given("공지 자동 등록을 요청했을 때") {
            val group = savedGroup()

            When("정산을 생성하면") {
                val response = settlementService.createSettlement(group.meetingId, group.adminUserId, request(group.memberIds, postNotice = true))

                Then("정산·납부 항목·공지가 함께 저장되고 공지가 정산에 연결된다") {
                    val notices = noticesOf(group.meetingId)
                    notices.size shouldBe 1
                    response.noticeId shouldBe notices.single().id
                    settlementRepository.findById(response.settlementId).orElseThrow().noticeId shouldBe response.noticeId
                    settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(response.settlementId).size shouldBe 3
                }

                Then("정산 생성자와 안내 공지 작성자는 요청한 관리자의 구성원이고, 공지에 그 관리자 이름이 표시된다") {
                    settlementRepository.findById(response.settlementId).orElseThrow().createdBy shouldBe group.adminMemberId
                    val author = noticeService.getNotices(group.meetingId, 0, 20, group.adminUserId).notices.single().author
                    author.memberId shouldBe group.adminMemberId
                    author.userName shouldBe "관리자"
                }
            }
        }

        Given("정산에 연결된 안내 공지가 있을 때") {
            val group = savedGroup()
            val created = settlementService.createSettlement(group.meetingId, group.adminUserId, request(group.memberIds, postNotice = true))
            val noticeId = created.noticeId.shouldNotBeNull()

            When("공지를 삭제하면") {
                noticeService.deleteNotice(group.meetingId, noticeId, group.adminUserId)

                Then("공지만 삭제되고 정산과 납부 항목은 유지되며 연결은 해제된다") {
                    noticesOf(group.meetingId) shouldBe emptyList()
                    settlementRepository.findById(created.settlementId).orElseThrow().noticeId.shouldBeNull()
                    settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(created.settlementId).size shouldBe 3
                }
            }
        }
    }

    private fun noticesOf(meetingId: Long): List<Notice> =
        noticeRepository.findAllByMeetingId(meetingId, PageRequest.of(0, 20)).content

    // 정산·공지 권한을 확인하므로 실제 모임에 관리자 1명(사용자 정보 포함)과 일반 구성원 2명을 저장
    private fun savedGroup(): Group {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = UUID.randomUUID().toString().take(8))).id)
        val adminUserId = checkNotNull(
            appUserRepository.save(
                AppUser(
                    name = "관리자",
                    email = "settlement-notice-${UUID.randomUUID()}@moamoa.test",
                    password = "password",
                    termsAgreedAt = OffsetDateTime.now()
                )
            ).id
        )
        val adminMemberId = savedMember(meetingId, adminUserId, MemberRole.ADMIN)
        val memberIds = listOf(adminMemberId) + List(2) { index -> savedMember(meetingId, MEMBER_USER_ID_BASE + index, MemberRole.MEMBER) }
        return Group(meetingId, adminUserId, adminMemberId, memberIds)
    }

    private fun savedMember(meetingId: Long, userId: Long, role: MemberRole): Long =
        checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = userId, role = role)).id)

    private fun request(memberIds: List<Long>, postNotice: Boolean) = SettlementCreateRequest(
        title = "10월 정기 모임 식비",
        totalAmount = 10000,
        memberIds = memberIds,
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아",
        dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00"),
        postNotice = postNotice
    )

    // 실제 Repository 메서드를 호출한 뒤 끼워 넣은 동작 실행 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun afterReal(action: () -> Unit) = Answer { invocation ->
        val result = mockingDetails(noticeRepository).mockCreationSettings.defaultAnswer.answer(invocation)
        action()
        result
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기
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

    private data class Group(val meetingId: Long, val adminUserId: Long, val adminMemberId: Long, val memberIds: List<Long>)

    companion object {
        private const val MEMBER_USER_ID_BASE = 9500L

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

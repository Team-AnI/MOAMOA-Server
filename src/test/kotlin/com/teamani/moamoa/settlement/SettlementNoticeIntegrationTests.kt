package com.teamani.moamoa.settlement

import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime
import java.util.UUID

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

    private data class Group(val meetingId: Long, val adminUserId: Long, val adminMemberId: Long, val memberIds: List<Long>)

    companion object {
        private const val MEMBER_USER_ID_BASE = 9500L
    }
}

package com.teamani.moamoa.settlement

import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.notice.service.NoticeService
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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime

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

    init {
        Given("공지 저장이 실패하는 상황에서 공지 자동 등록을 요청했을 때") {
            // 다른 테스트 데이터와 섞이지 않도록 전용 모임 ID 사용
            val meetingId = 9501L
            val paymentCountBefore = settlementMemberRepository.count()

            When("정산을 생성하면") {
                doThrow(IllegalStateException("공지 저장 실패")).`when`(noticeRepository).save(any(Notice::class.java))
                val exception = shouldThrow<IllegalStateException> {
                    settlementService.createSettlement(meetingId, 1L, request(postNotice = true))
                }
                reset(noticeRepository)

                Then("예외가 전달되고 정산·납부 항목·공지가 모두 남지 않는다") {
                    exception.message shouldBe "공지 저장 실패"
                    settlementRepository.findAllByMeetingIdOrderByCreatedAtDescIdDesc(meetingId) shouldBe emptyList()
                    settlementMemberRepository.count() shouldBe paymentCountBefore
                    noticesOf(meetingId) shouldBe emptyList()
                }
            }
        }

        Given("공지 자동 등록을 요청했을 때") {
            val meetingId = 9502L

            When("정산을 생성하면") {
                val response = settlementService.createSettlement(meetingId, 1L, request(postNotice = true))

                Then("정산·납부 항목·공지가 함께 저장되고 공지가 정산에 연결된다") {
                    val notices = noticesOf(meetingId)
                    notices.size shouldBe 1
                    response.noticeId shouldBe notices.single().id
                    settlementRepository.findById(response.settlementId).orElseThrow().noticeId shouldBe response.noticeId
                    settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(response.settlementId).size shouldBe 3
                }
            }
        }

        Given("정산에 연결된 안내 공지가 있을 때") {
            val meetingId = 9503L
            val created = settlementService.createSettlement(meetingId, 1L, request(postNotice = true))
            val noticeId = created.noticeId.shouldNotBeNull()

            When("공지를 삭제하면") {
                noticeService.deleteNotice(meetingId, noticeId, 1L)

                Then("공지만 삭제되고 정산과 납부 항목은 유지되며 연결은 해제된다") {
                    noticesOf(meetingId) shouldBe emptyList()
                    settlementRepository.findById(created.settlementId).orElseThrow().noticeId.shouldBeNull()
                    settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(created.settlementId).size shouldBe 3
                }
            }
        }
    }

    private fun noticesOf(meetingId: Long): List<Notice> =
        noticeRepository.findAllByMeetingId(meetingId, PageRequest.of(0, 20)).content

    private fun request(postNotice: Boolean) = SettlementCreateRequest(
        title = "10월 정기 모임 식비",
        totalAmount = 10000,
        memberIds = listOf(1L, 5L, 9L),
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아",
        dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00"),
        postNotice = postNotice
    )
}

package com.teamani.moamoa.notice.service

import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.OffsetDateTime
import java.util.Optional

class NoticeServiceTests : BehaviorSpec({

    val noticeRepository: NoticeRepository = mock(NoticeRepository::class.java)
    val noticeService = NoticeService(noticeRepository)

    afterEach { clearInvocations(noticeRepository) }

    Given("모임 공지사항 작성 요청 데이터가 주어졌을 때") {
        val meetingId = 1L
        val userId = 1L
        val request = NoticeCreateRequest(
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다."
        )

        val savedEntity = Notice(
            id = 30L,
            meetingId = meetingId,
            membersId = userId,
            title = requireNotNull(request.title),
            content = requireNotNull(request.content)
        )

        `when`(noticeRepository.save(any(Notice::class.java))).thenReturn(savedEntity)

        When("createNotice 메서드를 호출하면") {
            val response = noticeService.createNotice(meetingId, userId, request)

            Then("Notice 엔티티가 영속화되고 올바른 응답 DTO가 반환된다") {
                response.shouldNotBeNull()
                response.noticeId shouldBe 30L
                response.title shouldBe "10월 회비 안내"
                verify(noticeRepository).save(any(Notice::class.java))
            }
        }
    }

    Given("삭제할 공지사항 식별자가 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val userId = 1L
        val targetNotice = Notice(
            id = noticeId,
            meetingId = meetingId,
            membersId = userId,
            title = "삭제할 공지",
            content = "삭제할 공지 내용"
        )

        `when`(noticeRepository.findById(noticeId)).thenReturn(Optional.of(targetNotice))

        When("deleteNotice 메서드를 호출하면") {
            val response = noticeService.deleteNotice(meetingId, noticeId, userId)

            Then("공지가 삭제되고 삭제된 noticeId가 반환된다") {
                response.shouldNotBeNull()
                response.noticeId shouldBe 30L
                verify(noticeRepository).delete(targetNotice)
            }
        }
    }

    Given("공지사항 상세 조회 요청이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val userId = 1L
        val testCreatedAt = OffsetDateTime.now()

        // 테스트용 Notice 엔티티 생성 (createdAt 명시 주입)
        val notice = Notice(
            meetingId = meetingId,
            membersId = userId,
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다.",
            createdAt = testCreatedAt,
            id = noticeId
        )

        // findById 모킹: Optional.of(notice) 반환
        `when`(noticeRepository.findById(noticeId)).thenReturn(java.util.Optional.of(notice))

        When("getNoticeDetail을 호출하면") {
            val response = noticeService.getNoticeDetail(meetingId, noticeId, userId)

            Then("상세 정보(식별자, 제목, 내용, 생성일시)가 정상 반환된다") {
                response.noticeId shouldBe noticeId
                response.title shouldBe "10월 회비 안내"
                response.content shouldBe "10월 회비는 10일까지 납부 부탁드립니다."
                response.createdAt shouldBe testCreatedAt
                verify(noticeRepository).findById(noticeId)
            }
        }
    }
})

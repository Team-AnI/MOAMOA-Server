package com.teamani.moamoa.notice.service

import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class NoticeServiceTests : BehaviorSpec({

    val noticeRepository: NoticeRepository = mock(NoticeRepository::class.java)
    val noticeService = NoticeService(noticeRepository)

    Given("모임 공지사항 작성 요청 데이터가 주어졌을 때") {
        val meetingId = 1L
        val userId = 1L
        val request = NoticeCreateRequest(
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다."
        )

        val savedEntity = Notice(
            meetingId = meetingId,
            membersId = userId,
            title = requireNotNull(request.title),
            content = requireNotNull(request.content),
            id = 30L
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
})

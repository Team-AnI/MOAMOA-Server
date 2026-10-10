//package com.teamani.moamoa.notice.service
//
//import com.teamani.moamoa.notice.dto.NoticeCreateRequest
//import com.teamani.moamoa.notice.entity.Notice
//import com.teamani.moamoa.notice.repository.NoticeRepository
//import org.junit.jupiter.api.Assertions.assertEquals
//import org.junit.jupiter.api.Assertions.assertNotNull
//import org.junit.jupiter.api.DisplayName
//import org.junit.jupiter.api.Test
//import org.mockito.ArgumentMatchers.any
//import org.mockito.Mockito.`when`
//import org.mockito.Mockito.mock
//
//class NoticeServiceTests {
//
//    private val noticeRepository: NoticeRepository = mock(NoticeRepository::class.java)
//    private val noticeService = NoticeService(noticeRepository)
//
//    @Test
//    @DisplayName("공지 작성 시 Notice 엔티티가 저장되고 올바른 NoticeResponse DTO가 반환된다")
//    fun createNoticeTest() {
//        // given
//        val meetingId = 1L
//        val userId = 1L
//        val request = NoticeCreateRequest(
//            title = "10월 회비 안내",
//            content = "10월 회비는 10일까지 납부 부탁드립니다."
//        )
//
//        val savedEntity = Notice(
//            meetingId = meetingId,
//            authorId = userId,
//            title = requireNotNull(request.title),
//            content = requireNotNull(request.content),
//            id = 30L
//        )
//
//        `when`(noticeRepository.save(any(Notice::class.java))).thenReturn(savedEntity)
//
//        // when
//        val response = noticeService.createNotice(meetingId, userId, request)
//
//        // then
//        assertNotNull(response)
//        assertEquals(30L, response.noticeId)
//        assertEquals("10월 회비 안내", response.title)
//    }
//}


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
import java.util.Optional

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
})

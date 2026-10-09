package com.teamani.moamoa.notice.service

import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class NoticeServiceTests {

    private val noticeRepository: NoticeRepository = mock(NoticeRepository::class.java)
    private val noticeService = NoticeService(noticeRepository)

    @Test
    @DisplayName("공지 작성 시 Notice 엔티티가 저장되고 올바른 NoticeResponse DTO가 반환된다")
    fun createNoticeTest() {
        // given
        val meetingId = 1L
        val userId = 1L
        val request = NoticeCreateRequest(
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다."
        )

        val savedEntity = Notice(
            meetingId = meetingId,
            authorId = userId,
            title = requireNotNull(request.title),
            content = requireNotNull(request.content),
            id = 30L
        )

        `when`(noticeRepository.save(any(Notice::class.java))).thenReturn(savedEntity)

        // when
        val response = noticeService.createNotice(meetingId, userId, request)

        // then
        assertNotNull(response)
        assertEquals(30L, response.noticeId)
        assertEquals("10월 회비 안내", response.title)
    }
}
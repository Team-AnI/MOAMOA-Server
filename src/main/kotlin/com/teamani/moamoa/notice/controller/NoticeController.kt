package com.teamani.moamoa.notice.controller

import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.service.NoticeService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@RestController
@RequestMapping("/v1/meetings/{meetingId}/notices")
class NoticeController(
    private val noticeService: NoticeService
) {

    @PostMapping
    fun createNotice(
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: NoticeCreateRequest
    ): ResponseEntity<Map<String, Any?>> {
        // 인증 구현 전 임시 관리자 ID (데모용 1L)
        val currentUserId = 1L

        val response: NoticeCreateResponse = noticeService.createNotice(meetingId, currentUserId, request)

        // API 명세서 Envelope 포맷 규격: { success, data, error, timestamp }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            mapOf(
                "success" to true,
                "data" to response,
                "error" to null,
                "timestamp" to OffsetDateTime.now()
            )
        )
    }
}

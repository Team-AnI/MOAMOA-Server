package com.teamani.moamoa.notice.controller

import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.dto.NoticeListResponse
import com.teamani.moamoa.notice.service.NoticeService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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

    @DeleteMapping("/{noticeId}")
    fun deleteNotice(
        @PathVariable meetingId: Long,
        @PathVariable noticeId: Long
    ): ResponseEntity<Map<String, Any?>> {
        val currentUserId = 1L // TODO: 인증 구현 시 실제 사용자 ID로 교체

        val response: NoticeDeleteResponse = noticeService.deleteNotice(meetingId, noticeId, currentUserId)

        // API 명세서 Envelope 포맷 규격: { success, data: { noticeId }, error: null, timestamp }
        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "data" to response,
                "error" to null,
                "timestamp" to OffsetDateTime.now()
            )
        )
    }

    @GetMapping("/{noticeId}")
    fun getNoticeDetail(
        @PathVariable meetingId: Long,
        @PathVariable noticeId: Long
    ): ResponseEntity<Map<String, Any?>> {
        val currentUserId = 1L
        val response: NoticeDetailResponse = noticeService.getNoticeDetail(meetingId, noticeId, currentUserId)

        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "data" to response,
                "error" to null,
                "timestamp" to OffsetDateTime.now()
            )
        )
    }

    @GetMapping
    fun getNotices(
        @PathVariable meetingId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<Map<String, Any?>> {
        val currentUserId = 1L
        val response: NoticeListResponse = noticeService.getNotices(meetingId, page, size, currentUserId)

        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "data" to response,
                "error" to null,
                "timestamp" to OffsetDateTime.now()
            )
        )
    }
}

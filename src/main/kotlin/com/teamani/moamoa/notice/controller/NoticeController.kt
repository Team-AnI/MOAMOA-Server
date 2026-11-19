package com.teamani.moamoa.notice.controller

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.dto.NoticeListResponse
import com.teamani.moamoa.notice.dto.NoticeReadResponse
import com.teamani.moamoa.notice.dto.NoticeReadStatusResponse
import com.teamani.moamoa.notice.dto.NoticeUpdateRequest
import com.teamani.moamoa.notice.dto.NoticeUpdateResponse
import com.teamani.moamoa.notice.service.NoticeService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/meetings/{meetingId}/notices")
class NoticeController(
    private val noticeService: NoticeService
) {

    @PostMapping
    fun createNotice(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: NoticeCreateRequest
    ): ResponseEntity<CommonResponse<NoticeCreateResponse>> {
        val response: NoticeCreateResponse = noticeService.createNotice(meetingId, currentUserId, request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }

    @DeleteMapping("/{noticeId}")
    fun deleteNotice(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable noticeId: Long
    ): ResponseEntity<CommonResponse<NoticeDeleteResponse>> {
        val response: NoticeDeleteResponse = noticeService.deleteNotice(meetingId, noticeId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @GetMapping("/{noticeId}")
    fun getNoticeDetail(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable noticeId: Long
    ): ResponseEntity<CommonResponse<NoticeDetailResponse>> {
        val response: NoticeDetailResponse = noticeService.getNoticeDetail(meetingId, noticeId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @GetMapping
    fun getNotices(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<CommonResponse<NoticeListResponse>> {
        val response: NoticeListResponse = noticeService.getNotices(meetingId, page, size, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @PatchMapping("/{noticeId}")
    fun updateNotice(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable noticeId: Long,
        @Valid @RequestBody request: NoticeUpdateRequest
    ): ResponseEntity<CommonResponse<NoticeUpdateResponse>> {
        val response: NoticeUpdateResponse = noticeService.updateNotice(meetingId, noticeId, currentUserId, request)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @PutMapping("/{noticeId}/reads/me")
    fun readNotice(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable noticeId: Long
    ): ResponseEntity<CommonResponse<NoticeReadResponse>> {
        val response: NoticeReadResponse = noticeService.readNotice(meetingId, noticeId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @GetMapping("/{noticeId}/reads")
    fun getNoticeReads(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable noticeId: Long
    ): ResponseEntity<CommonResponse<NoticeReadStatusResponse>> {
        val response: NoticeReadStatusResponse = noticeService.getNoticeReads(meetingId, noticeId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }
}

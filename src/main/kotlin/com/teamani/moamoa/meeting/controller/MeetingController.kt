package com.teamani.moamoa.meeting.controller

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.meeting.dto.MeetingCreateRequest
import com.teamani.moamoa.meeting.dto.MeetingCreateResponse
import com.teamani.moamoa.meeting.dto.MeetingDeleteResponse
import com.teamani.moamoa.meeting.dto.MeetingDetailResponse
import com.teamani.moamoa.meeting.dto.MeetingInviteCodeResponse
import com.teamani.moamoa.meeting.dto.MeetingJoinRequest
import com.teamani.moamoa.meeting.dto.MeetingJoinResponse
import com.teamani.moamoa.meeting.dto.MeetingUpdateRequest
import com.teamani.moamoa.meeting.dto.MeetingUpdateResponse
import com.teamani.moamoa.meeting.dto.MyMeetingListResponse
import com.teamani.moamoa.meeting.service.MeetingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// 모임 API와 내 모임(/v1/me/meetings) API를 함께 처리하므로 메서드별 전체 경로 지정
@RestController
class MeetingController(
    private val meetingService: MeetingService
) {

    @PostMapping("/v1/meetings")
    fun createMeeting(
        @CurrentUserId currentUserId: Long,
        @Valid @RequestBody request: MeetingCreateRequest
    ): ResponseEntity<CommonResponse<MeetingCreateResponse>> {
        val response: MeetingCreateResponse = meetingService.createMeeting(currentUserId, request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }

    @GetMapping("/v1/me/meetings")
    fun getMyMeetings(
        @CurrentUserId currentUserId: Long
    ): ResponseEntity<CommonResponse<MyMeetingListResponse>> {
        val response: MyMeetingListResponse = meetingService.getMyMeetings(currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @PostMapping("/v1/me/meetings")
    fun joinMeeting(
        @CurrentUserId currentUserId: Long,
        @Valid @RequestBody request: MeetingJoinRequest
    ): ResponseEntity<CommonResponse<MeetingJoinResponse>> {
        val response: MeetingJoinResponse = meetingService.joinMeeting(currentUserId, request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }

    @GetMapping("/v1/meetings/{meetingId}")
    fun getMeeting(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long
    ): ResponseEntity<CommonResponse<MeetingDetailResponse>> {
        val response: MeetingDetailResponse = meetingService.getMeeting(meetingId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @PatchMapping("/v1/meetings/{meetingId}")
    fun updateMeeting(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: MeetingUpdateRequest
    ): ResponseEntity<CommonResponse<MeetingUpdateResponse>> {
        val response: MeetingUpdateResponse = meetingService.updateMeeting(meetingId, currentUserId, request)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @DeleteMapping("/v1/meetings/{meetingId}")
    fun deleteMeeting(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long
    ): ResponseEntity<CommonResponse<MeetingDeleteResponse>> {
        val response: MeetingDeleteResponse = meetingService.deleteMeeting(meetingId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @GetMapping("/v1/meetings/{meetingId}/invite-code")
    fun getInviteCode(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long
    ): ResponseEntity<CommonResponse<MeetingInviteCodeResponse>> {
        val response: MeetingInviteCodeResponse = meetingService.getInviteCode(meetingId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }
}

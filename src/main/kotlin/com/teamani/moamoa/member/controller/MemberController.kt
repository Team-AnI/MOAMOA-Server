package com.teamani.moamoa.member.controller

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.member.dto.MemberLeaveResponse
import com.teamani.moamoa.member.dto.MemberListResponse
import com.teamani.moamoa.member.service.MemberService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/meetings/{meetingId}/members")
class MemberController(
    private val memberService: MemberService
) {

    @GetMapping
    fun getMembers(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long
    ): ResponseEntity<CommonResponse<MemberListResponse>> {
        val response: MemberListResponse = memberService.getMembers(meetingId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @DeleteMapping("/me")
    fun leaveMeeting(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long
    ): ResponseEntity<CommonResponse<MemberLeaveResponse>> {
        val response: MemberLeaveResponse = memberService.leaveMeeting(meetingId, currentUserId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }
}

package com.teamani.moamoa.vote.controller

import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.vote.dto.MyVoteSubmitRequest
import com.teamani.moamoa.vote.dto.MyVoteSubmitResponse
import com.teamani.moamoa.vote.dto.VoteConfirmRequest
import com.teamani.moamoa.vote.dto.VoteConfirmResponse
import com.teamani.moamoa.vote.dto.VoteCreateRequest
import com.teamani.moamoa.vote.dto.VoteCreateResponse
import com.teamani.moamoa.vote.dto.VoteDetailResponse
import com.teamani.moamoa.vote.dto.VoteParticipationResponse
import com.teamani.moamoa.vote.service.VoteService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// 투표 생성(/schedule-votes)과 일정 하위 투표 API(/schedules/{scheduleId}/...)를 함께 처리하므로 메서드별 전체 경로 지정
@RestController
class VoteController(
    private val voteService: VoteService
) {

    @PostMapping("/v1/meetings/{meetingId}/schedule-votes")
    fun createVote(
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: VoteCreateRequest
    ): ResponseEntity<CommonResponse<VoteCreateResponse>> {
        val currentUserId = 1L // TODO: 인증 구현 시 실제 사용자 ID로 교체

        val response: VoteCreateResponse = voteService.createVote(meetingId, currentUserId, request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }

    @GetMapping("/v1/meetings/{meetingId}/schedules/{scheduleId}/candidates")
    fun getVote(
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long
    ): ResponseEntity<CommonResponse<VoteDetailResponse>> {
        val currentUserId = 1L // TODO: 인증 구현 시 실제 사용자 ID로 교체

        val response: VoteDetailResponse = voteService.getVote(meetingId, currentUserId, scheduleId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @PutMapping("/v1/meetings/{meetingId}/schedules/{scheduleId}/votes/me")
    fun submitMyVote(
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long,
        @Valid @RequestBody request: MyVoteSubmitRequest
    ): ResponseEntity<CommonResponse<MyVoteSubmitResponse>> {
        val currentUserId = 1L // TODO: 인증 구현 시 실제 사용자 ID로 교체

        val response: MyVoteSubmitResponse = voteService.submitMyVote(meetingId, currentUserId, scheduleId, request)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @GetMapping("/v1/meetings/{meetingId}/schedules/{scheduleId}/vote-responses")
    fun getVoteParticipation(
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long
    ): ResponseEntity<CommonResponse<VoteParticipationResponse>> {
        val currentUserId = 1L // TODO: 인증 구현 시 실제 사용자 ID로 교체

        val response: VoteParticipationResponse =
            voteService.getVoteParticipation(meetingId, currentUserId, scheduleId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @PostMapping("/v1/meetings/{meetingId}/schedules/{scheduleId}/confirmation")
    fun confirmVote(
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long,
        @Valid @RequestBody request: VoteConfirmRequest
    ): ResponseEntity<CommonResponse<VoteConfirmResponse>> {
        val currentUserId = 1L // TODO: 인증 구현 시 실제 사용자 ID로 교체

        val response: VoteConfirmResponse = voteService.confirmVote(meetingId, currentUserId, scheduleId, request)

        return ResponseEntity.ok(CommonResponse(data = response))
    }
}

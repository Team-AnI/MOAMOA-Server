package com.teamani.moamoa.schedule.controller

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.schedule.dto.ScheduleMyResponseRequest
import com.teamani.moamoa.schedule.dto.ScheduleMyResponseResponse
import com.teamani.moamoa.schedule.dto.ScheduleResponsesResponse
import com.teamani.moamoa.schedule.service.ScheduleResponseService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/meetings/{meetingId}/schedules/{scheduleId}/responses")
class ScheduleResponseController(
    private val scheduleResponseService: ScheduleResponseService
) {

    @PutMapping("/me")
    fun respond(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long,
        @Valid @RequestBody request: ScheduleMyResponseRequest
    ): ResponseEntity<CommonResponse<ScheduleMyResponseResponse>> =
        ResponseEntity.ok(CommonResponse(data = scheduleResponseService.respond(meetingId, currentUserId, scheduleId, request)))

    @GetMapping
    fun getResponses(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long
    ): ResponseEntity<CommonResponse<ScheduleResponsesResponse>> =
        ResponseEntity.ok(CommonResponse(data = scheduleResponseService.getResponses(meetingId, currentUserId, scheduleId)))
}

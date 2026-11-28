package com.teamani.moamoa.attendance.controller

import com.teamani.moamoa.attendance.dto.AttendanceListResponse
import com.teamani.moamoa.attendance.dto.AttendanceUpdateRequest
import com.teamani.moamoa.attendance.dto.AttendanceUpdateResponse
import com.teamani.moamoa.attendance.service.AttendanceService
import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/meetings/{meetingId}/schedules/{scheduleId}/attendances")
class AttendanceController(
    private val attendanceService: AttendanceService
) {

    @PatchMapping
    fun updateAttendances(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long,
        @Valid @RequestBody request: AttendanceUpdateRequest
    ): ResponseEntity<CommonResponse<AttendanceUpdateResponse>> {
        val response: AttendanceUpdateResponse =
            attendanceService.updateAttendances(meetingId, currentUserId, scheduleId, request)

        return ResponseEntity.ok(CommonResponse(data = response))
    }

    @GetMapping
    fun getAttendances(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable scheduleId: Long
    ): ResponseEntity<CommonResponse<AttendanceListResponse>> {
        val response: AttendanceListResponse = attendanceService.getAttendances(meetingId, currentUserId, scheduleId)

        return ResponseEntity.ok(CommonResponse(data = response))
    }
}

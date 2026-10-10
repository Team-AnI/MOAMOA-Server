package com.teamani.moamoa.schedule.controller

import com.teamani.moamoa.schedule.dto.ScheduleCreateRequest
import com.teamani.moamoa.schedule.dto.ScheduleCreateResponse
import com.teamani.moamoa.schedule.service.ScheduleService
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
@RequestMapping("/v1/meetings/{meetingId}/schedules")
class ScheduleController(
    private val scheduleService: ScheduleService
) {

    @PostMapping
    fun createSchedule(
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: ScheduleCreateRequest
    ): ResponseEntity<Map<String, Any?>> {
        val currentUserId = 1L // TODO: 인증 구현 시 실제 사용자 ID로 교체

        val response: ScheduleCreateResponse = scheduleService.createSchedule(meetingId, currentUserId, request)

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

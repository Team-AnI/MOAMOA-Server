package com.teamani.moamoa.schedule.dto

import com.teamani.moamoa.schedule.entity.ScheduleResponseStatus
import com.teamani.moamoa.user.entity.ProfileColor
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.OffsetDateTime

// 응답 상태는 허용 값이 아닐 때 JSON_PARSE_ERROR가 아닌 VALIDATION_ERROR가 되도록 문자열로 받아 검증
data class ScheduleMyResponseRequest(
    @field:NotNull(message = "참석 응답은 필수입니다.")
    @field:Pattern(regexp = "ATTENDING|NOT_ATTENDING|UNDECIDED", message = "참석 응답은 ATTENDING, NOT_ATTENDING, UNDECIDED 중 하나여야 합니다.")
    val status: String?
)

data class ScheduleMyResponseResponse(
    val scheduleId: Long,
    val status: ScheduleResponseStatus,
    // 마지막으로 응답한 일시
    val respondedAt: OffsetDateTime
)

data class ScheduleResponsesResponse(
    val scheduleId: Long,
    // 실제로 적용되는 응답 마감 (정하지 않았으면 시작 일시, 투표 중 일정은 null)
    val responseDeadlineAt: OffsetDateTime?,
    // 요청한 구성원의 응답 (응답하지 않았으면 null)
    val myStatus: ScheduleResponseStatus?,
    val summary: ScheduleResponseSummaryResponse,
    val attendingMembers: List<ScheduleRespondedMemberResponse>,
    val notAttendingMembers: List<ScheduleRespondedMemberResponse>,
    val undecidedMembers: List<ScheduleRespondedMemberResponse>,
    val noResponseMembers: List<ScheduleNoResponseMemberResponse>
)

data class ScheduleResponseSummaryResponse(
    val attendingCount: Int,
    val notAttendingCount: Int,
    val undecidedCount: Int,
    val noResponseCount: Int,
    val totalCount: Int
)

data class ScheduleRespondedMemberResponse(
    val memberId: Long,
    val userName: String,
    val profileColor: ProfileColor,
    val respondedAt: OffsetDateTime
)

data class ScheduleNoResponseMemberResponse(
    val memberId: Long,
    val userName: String,
    val profileColor: ProfileColor
)

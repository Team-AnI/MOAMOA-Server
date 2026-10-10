package com.teamani.moamoa.schedule.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class ScheduleCreateRequest(
    @field:NotBlank(message = "일정 제목은 필수입니다.")
    @field:Size(max = 100, message = "제목은 100자 이하여야 합니다.")
    val title: String?,

    val description: String? = null,

    @field:NotNull(message = "시작 일시는 필수입니다.")
    val startAt: OffsetDateTime?,

    val endAt: OffsetDateTime? = null,

    @field:Size(max = 255, message = "장소는 255자 이하여야 합니다.")
    val location: String? = null
) {
    // 종료 일시가 있으면 시작 일시보다 이전일 수 없음
    @get:JsonIgnore
    @get:AssertTrue(message = "종료 일시는 시작 일시 이후여야 합니다.")
    val isEndAtAfterStartAt: Boolean
        get() = startAt == null || endAt == null || !endAt.isBefore(startAt)
}

data class ScheduleCreateResponse(
    val scheduleId: Long,
    val title: String,
    val startAt: OffsetDateTime
)

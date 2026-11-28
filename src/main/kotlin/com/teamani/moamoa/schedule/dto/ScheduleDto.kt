package com.teamani.moamoa.schedule.dto

import com.teamani.moamoa.schedule.entity.ScheduleStatus
import jakarta.validation.constraints.FutureOrPresent
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.Optional

// 종료 일시가 시작 일시 이후인지는 수정 API와 같은 규칙으로 Service에서 검증
data class ScheduleCreateRequest(
    @field:NotBlank(message = "일정 제목은 필수입니다.")
    @field:Size(max = 50, message = "제목은 50자 이하여야 합니다.")
    val title: String?,

    val description: String? = null,

    @field:NotNull(message = "시작 일시는 필수입니다.")
    @field:FutureOrPresent(message = "시작 일시는 현재 시각 이후여야 합니다.")
    val startAt: OffsetDateTime?,

    val endAt: OffsetDateTime? = null,

    @field:Size(max = 255, message = "장소는 255자 이하여야 합니다.")
    val location: String? = null,

    @field:Size(max = 255, message = "준비물은 255자 이하여야 합니다.")
    val preparation: String? = null,

    // 시작 일시 이전이거나 같은지는 Service에서 검증
    @field:FutureOrPresent(message = "참석 응답 마감 일시는 현재 시각 이후여야 합니다.")
    val responseDeadlineAt: OffsetDateTime? = null
)

data class ScheduleCreateResponse(
    val scheduleId: Long,
    val title: String,
    val startAt: OffsetDateTime
)

data class ScheduleListResponse(
    val schedules: List<ScheduleSummaryResponse>
)

data class ScheduleSummaryResponse(
    val scheduleId: Long,
    val title: String,
    val startAt: OffsetDateTime,
    val endAt: OffsetDateTime?,
    val location: String?,
    // 투표 중(VOTING) 일정은 startAt에 가장 이른 후보 시간이 임시로 들어가므로 status로 구분
    val status: ScheduleStatus
)

// 부분 수정: 미전송은 null(유지), null 전송은 Optional.empty, 값 전송은 Optional.of
// 선택 항목(description, endAt, location)은 null 전송 시 값을 비우고, 필수 항목(title, startAt)은 VALIDATION_ERROR
// 종료 일시 순서는 기존 값과 합친 결과로 Service에서 검증
data class ScheduleUpdateRequest(
    val title: Optional<
        @NotNull(message = "일정 제목은 비울 수 없습니다.")
        @Pattern(regexp = "(?s).*\\S.*", message = "일정 제목은 공백일 수 없습니다.")
        @Size(max = 50, message = "제목은 50자 이하여야 합니다.")
        String
    >? = null,

    val description: Optional<String>? = null,

    // 과거 여부는 기존 값과 달라졌을 때만 Service에서 검증 (같은 값을 다시 보내는 것은 변경이 아님)
    val startAt: Optional<
        @NotNull(message = "시작 일시는 비울 수 없습니다.")
        OffsetDateTime
    >? = null,

    val endAt: Optional<OffsetDateTime>? = null,

    val location: Optional<
        @Size(max = 255, message = "장소는 255자 이하여야 합니다.")
        String
    >? = null,

    val preparation: Optional<
        @Size(max = 255, message = "준비물은 255자 이하여야 합니다.")
        String
    >? = null,

    // null 전송 시 마감을 비움(시작 일시까지 응답). 과거 여부는 기존 값과 달라졌을 때만 Service에서 검증
    val responseDeadlineAt: Optional<OffsetDateTime>? = null
)

data class ScheduleDeleteResponse(
    val scheduleId: Long
)

data class ScheduleDetailResponse(
    val scheduleId: Long,
    val title: String,
    val description: String?,
    val startAt: OffsetDateTime,
    val endAt: OffsetDateTime?,
    val location: String?,
    val preparation: String?,
    // 참석 응답 마감 일시 (정하지 않았으면 null, 이때는 시작 일시까지 응답 가능)
    val responseDeadlineAt: OffsetDateTime?,
    val status: ScheduleStatus
)

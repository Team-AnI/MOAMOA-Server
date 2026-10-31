package com.teamani.moamoa.vote.dto

import com.teamani.moamoa.schedule.entity.ScheduleStatus
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

// 후보 시간 중복은 표기(+09:00, Z)와 무관하게 같은 순간인지로 Service에서 검증
// 원소도 nullable로 받아야 null 원소가 JSON_PARSE_ERROR가 아닌 VALIDATION_ERROR로 처리됨
data class VoteCreateRequest(
    @field:NotBlank(message = "투표 제목은 필수입니다.")
    @field:Size(max = 50, message = "투표 제목은 50자 이하여야 합니다.")
    val title: String?,

    @field:NotNull(message = "투표 마감 일시는 필수입니다.")
    @field:Future(message = "투표 마감 일시는 현재 이후여야 합니다.")
    val voteDeadlineAt: OffsetDateTime?,

    @field:NotEmpty(message = "후보 시간은 1개 이상이어야 합니다.")
    val meetingTimes: List<@NotNull(message = "후보 시간은 null일 수 없습니다.") OffsetDateTime?>?
)

data class VoteCreateResponse(
    val scheduleId: Long,
    val title: String,
    val voteDeadlineAt: OffsetDateTime,
    val meetingTimes: List<OffsetDateTime>
)

data class VoteDetailResponse(
    val scheduleId: Long,
    val title: String,
    val status: ScheduleStatus,
    val voteDeadlineAt: OffsetDateTime,
    val votingOpen: Boolean,
    val memberCount: Long,
    val respondedCount: Long,
    val myResponded: Boolean,
    val candidates: List<VoteCandidateResponse>
)

data class VoteCandidateResponse(
    val meetingTime: OffsetDateTime,
    val availableCount: Long,
    val selectedByMe: Boolean
)

// 빈 배열은 "가능한 시간 없음" 응답 (중복·후보 아님은 Service에서 검증)
data class MyVoteSubmitRequest(
    @field:NotNull(message = "선택한 후보 시간 목록은 필수입니다.")
    val meetingTimes: List<@NotNull(message = "후보 시간은 null일 수 없습니다.") OffsetDateTime?>?
)

data class MyVoteSubmitResponse(
    val scheduleId: Long,
    val meetingTimes: List<OffsetDateTime>,
    val submittedAt: OffsetDateTime
)

data class VoteParticipationResponse(
    val memberCount: Long,
    val respondedCount: Long,
    val members: List<VoteMemberResponse>
)

// nickname은 User 도메인 구현 전까지 null 반환
data class VoteMemberResponse(
    val memberId: Long,
    val nickname: String?,
    val responded: Boolean,
    val submittedAt: OffsetDateTime?
)

data class VoteConfirmRequest(
    @field:NotNull(message = "확정할 후보 시간은 필수입니다.")
    val meetingTime: OffsetDateTime?
)

data class VoteConfirmResponse(
    val scheduleId: Long,
    val title: String,
    val startAt: OffsetDateTime,
    val status: ScheduleStatus
)

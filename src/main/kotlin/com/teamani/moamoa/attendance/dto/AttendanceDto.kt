package com.teamani.moamoa.attendance.dto

import com.teamani.moamoa.attendance.entity.AttendanceStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

// 요청에 포함한 구성원만 저장·변경 (memberId 중복은 Service에서 검증)
// 원소도 nullable로 받아야 null 원소가 JSON_PARSE_ERROR가 아닌 VALIDATION_ERROR로 처리됨
data class AttendanceUpdateRequest(
    @field:NotEmpty(message = "출석 목록은 1개 이상이어야 합니다.")
    @field:Valid
    val attendances: List<@NotNull(message = "출석 항목은 null일 수 없습니다.") AttendanceUpdateItemRequest?>?
)

// status는 UNCHECKED·오타도 JSON_PARSE_ERROR가 아닌 VALIDATION_ERROR가 되도록 문자열로 받아 허용 값 검증
data class AttendanceUpdateItemRequest(
    @field:NotNull(message = "구성원 ID는 필수입니다.")
    val memberId: Long?,

    @field:NotNull(message = "출석 상태는 필수입니다.")
    @field:Pattern(regexp = "PRESENT|LATE|ABSENT", message = "출석 상태는 PRESENT, LATE, ABSENT 중 하나여야 합니다.")
    val status: String?
)

data class AttendanceUpdateResponse(
    val scheduleId: Long,
    val attendances: List<AttendanceUpdateItemResponse>
)

data class AttendanceUpdateItemResponse(
    val memberId: Long,
    val status: AttendanceStatus
)

data class AttendanceListResponse(
    val scheduleId: Long,
    val attendances: List<AttendanceMemberResponse>
)

// nickname은 User 도메인 구현 전까지 null 반환
data class AttendanceMemberResponse(
    val memberId: Long,
    val nickname: String?,
    val status: AttendanceViewStatus
)

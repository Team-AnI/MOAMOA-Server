package com.teamani.moamoa.settlement.dto

import com.teamani.moamoa.settlement.entity.CycleType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import jakarta.validation.valueextraction.Unwrapping
import java.util.Optional

// cycleType·sendTime은 enum·시간 변환 실패가 JSON_PARSE_ERROR가 되지 않도록 문자열로 받고 형식을 검증
// cycleType과 cycleDay의 조합은 수정 API와 같은 규칙으로 Service에서 검증
data class RegularDuesCreateRequest(
    @field:NotBlank(message = "회비명은 필수입니다.")
    @field:Size(max = 50, message = "회비명은 50자 이하여야 합니다.")
    val title: String?,

    @field:NotNull(message = "회비 금액은 필수입니다.")
    @field:Positive(message = "회비 금액은 0보다 커야 합니다.")
    val amount: Int?,

    @field:NotNull(message = "반복 주기는 필수입니다.")
    @field:Pattern(regexp = CYCLE_TYPE_PATTERN, message = CYCLE_TYPE_MESSAGE)
    val cycleType: String?,

    @field:NotBlank(message = "반복 지정일은 필수입니다.")
    val cycleDay: String?,

    @field:Pattern(regexp = SEND_TIME_PATTERN, message = SEND_TIME_MESSAGE)
    val sendTime: String? = null,

    // 0이면 요청 발송 당일 마감
    @field:NotNull(message = "납부 기한 일수는 필수입니다.")
    @field:PositiveOrZero(message = "납부 기한 일수는 0 이상이어야 합니다.")
    val dueDays: Int?,

    @field:NotBlank(message = "은행명은 필수입니다.")
    @field:Size(max = 20, message = "은행명은 20자 이하여야 합니다.")
    val bankName: String?,

    @field:NotBlank(message = "계좌번호는 필수입니다.")
    @field:Size(max = 30, message = "계좌번호는 30자 이하여야 합니다.")
    val accountNumber: String?,

    @field:NotBlank(message = "예금주는 필수입니다.")
    @field:Size(max = 20, message = "예금주는 20자 이하여야 합니다.")
    val accountHolder: String?,

    val postNotice: Boolean? = null
)

data class RegularDuesCreateResponse(
    val regularDuesId: Long,
    val title: String,
    val isActive: Boolean
)

data class RegularDuesListResponse(
    val regularDues: List<RegularDuesResponse>
)

// 목록·수정 응답 공통
data class RegularDuesResponse(
    val regularDuesId: Long,
    val title: String,
    val amount: Int,
    val cycleType: CycleType,
    val cycleDay: String,
    val sendTime: String,
    val dueDays: Int,
    // 관리자 수정 화면에서 기존 계좌를 채우는 데 사용
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
    val isActive: Boolean,
    val postNotice: Boolean
)

// 부분 수정: 보내지 않은 필드(null)는 유지, 모든 항목이 필수라 null 전송(Optional.empty)은 Service에서 400 처리
data class RegularDuesUpdateRequest(
    @field:Pattern(regexp = NOT_BLANK_PATTERN, message = "회비명은 공백일 수 없습니다.", payload = [Unwrapping.Unwrap::class])
    @field:Size(max = 50, message = "회비명은 50자 이하여야 합니다.", payload = [Unwrapping.Unwrap::class])
    val title: Optional<String>? = null,

    @field:Positive(message = "회비 금액은 0보다 커야 합니다.", payload = [Unwrapping.Unwrap::class])
    val amount: Optional<Int>? = null,

    @field:Pattern(regexp = CYCLE_TYPE_PATTERN, message = CYCLE_TYPE_MESSAGE, payload = [Unwrapping.Unwrap::class])
    val cycleType: Optional<String>? = null,

    val cycleDay: Optional<String>? = null,

    @field:Pattern(regexp = SEND_TIME_PATTERN, message = SEND_TIME_MESSAGE, payload = [Unwrapping.Unwrap::class])
    val sendTime: Optional<String>? = null,

    @field:PositiveOrZero(message = "납부 기한 일수는 0 이상이어야 합니다.", payload = [Unwrapping.Unwrap::class])
    val dueDays: Optional<Int>? = null,

    @field:Pattern(regexp = NOT_BLANK_PATTERN, message = "은행명은 공백일 수 없습니다.", payload = [Unwrapping.Unwrap::class])
    @field:Size(max = 20, message = "은행명은 20자 이하여야 합니다.", payload = [Unwrapping.Unwrap::class])
    val bankName: Optional<String>? = null,

    @field:Pattern(regexp = NOT_BLANK_PATTERN, message = "계좌번호는 공백일 수 없습니다.", payload = [Unwrapping.Unwrap::class])
    @field:Size(max = 30, message = "계좌번호는 30자 이하여야 합니다.", payload = [Unwrapping.Unwrap::class])
    val accountNumber: Optional<String>? = null,

    @field:Pattern(regexp = NOT_BLANK_PATTERN, message = "예금주는 공백일 수 없습니다.", payload = [Unwrapping.Unwrap::class])
    @field:Size(max = 20, message = "예금주는 20자 이하여야 합니다.", payload = [Unwrapping.Unwrap::class])
    val accountHolder: Optional<String>? = null,

    val postNotice: Optional<Boolean>? = null,

    val isActive: Optional<Boolean>? = null
)

data class RegularDuesDeleteResponse(
    val regularDuesId: Long
)

private const val CYCLE_TYPE_PATTERN = "WEEKLY|MONTHLY"
private const val CYCLE_TYPE_MESSAGE = "반복 주기는 WEEKLY 또는 MONTHLY여야 합니다."
// 시는 한 자리(9:00)도 허용하고 저장·응답은 HH:mm, 24:00은 허용하지 않음
private const val SEND_TIME_PATTERN = "([01]?[0-9]|2[0-3]):[0-5][0-9]"
private const val SEND_TIME_MESSAGE = "발송 시간은 00:00~23:59 사이의 HH:mm 형식이어야 합니다."
private const val NOT_BLANK_PATTERN = "(?s).*\\S.*"

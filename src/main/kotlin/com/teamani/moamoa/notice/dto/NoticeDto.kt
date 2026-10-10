package com.teamani.moamoa.notice.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class NoticeCreateRequest(
    @field:NotBlank(message = "공지 제목은 필수입니다.")
    @field:Size(max = 50, message = "제목은 50자 이하여야 합니다.")
    val title: String?,

    @field:NotBlank(message = "공지 내용은 필수입니다.")
    val content: String?
)

data class NoticeCreateResponse(
    val noticeId: Long,
    val title: String,
)

data class NoticeDeleteResponse(
    val noticeId: Long
)

data class NoticeDetailResponse(
    val noticeId: Long,
    val title: String,
    val content: String,
    val createdAt: OffsetDateTime
)

data class NoticeSummaryResponse(
    val noticeId: Long,
    val title: String,
    val createdAt: OffsetDateTime
)

data class NoticeListResponse(
    val notices: List<NoticeSummaryResponse>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean
)

// 부분 수정: null이면 변경하지 않고, 값이 있으면 공백만으로 구성될 수 없음
data class NoticeUpdateRequest(
    @field:Pattern(regexp = "(?s).*\\S.*", message = "공지 제목은 공백일 수 없습니다.")
    @field:Size(max = 50, message = "제목은 50자 이하여야 합니다.")
    val title: String? = null,

    @field:Pattern(regexp = "(?s).*\\S.*", message = "공지 내용은 공백일 수 없습니다.")
    val content: String? = null
)

data class NoticeUpdateResponse(
    val noticeId: Long,
    val title: String,
    val content: String
)

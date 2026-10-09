package com.teamani.moamoa.notice.dto

import jakarta.validation.constraints.NotBlank
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
    val title: String
)

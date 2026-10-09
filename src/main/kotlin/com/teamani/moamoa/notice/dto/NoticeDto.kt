package com.teamani.moamoa.notice.dto

import jakarta.validation.constraints.NotBlank

data class NoticeCreateRequest(
    @field:NotBlank(message = "제목은 공백일 수 없습니다.")
    val title: String?,

    @field:NotBlank(message = "내용은 공백일 수 없습니다.")
    val content: String?
)

data class NoticeResponse(
    val noticeId: Long,
    val title: String
)
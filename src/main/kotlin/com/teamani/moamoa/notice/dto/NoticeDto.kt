package com.teamani.moamoa.notice.dto

import com.teamani.moamoa.user.entity.ProfileColor
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.Optional

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
    // 정산·정기 회비 안내 공지에 연결된 정산 ID (없으면 null)
    val settlementId: Long?,
    val createdAt: OffsetDateTime
)

data class NoticeSummaryResponse(
    val noticeId: Long,
    val title: String,
    // 공지를 읽은 현재 구성원 수
    val readCount: Int,
    // 현재 모임 구성원 수
    val totalCount: Int,
    val createdAt: OffsetDateTime
)

data class NoticeListResponse(
    val notices: List<NoticeSummaryResponse>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean
)

// 부분 수정: 미전송은 null(유지), null 전송은 Optional.empty, 값 전송은 Optional.of
// 제목·내용은 필수 항목이라 null 전송 시 400 VALIDATION_ERROR (검증은 타입 인자에 작성)
data class NoticeUpdateRequest(
    val title: Optional<
        @NotNull(message = "공지 제목은 비울 수 없습니다.")
        @Pattern(regexp = "(?s).*\\S.*", message = "공지 제목은 공백일 수 없습니다.")
        @Size(max = 50, message = "제목은 50자 이하여야 합니다.")
        String
    >? = null,

    val content: Optional<
        @NotNull(message = "공지 내용은 비울 수 없습니다.")
        @Pattern(regexp = "(?s).*\\S.*", message = "공지 내용은 공백일 수 없습니다.")
        String
    >? = null
)

data class NoticeUpdateResponse(
    val noticeId: Long,
    val title: String,
    val content: String
)

data class NoticeReadResponse(
    val noticeId: Long,
    // 처음 읽은 시각
    val readAt: OffsetDateTime
)

data class NoticeReadStatusResponse(
    val noticeId: Long,
    val readCount: Int,
    val totalCount: Int,
    // 읽은 시각 순
    val readMembers: List<NoticeReadMemberResponse>,
    // memberId 오름차순 (모임 가입 순)
    val unreadMembers: List<NoticeUnreadMemberResponse>
)

data class NoticeReadMemberResponse(
    val memberId: Long,
    val userName: String?,
    val profileColor: ProfileColor,
    val readAt: OffsetDateTime
)

data class NoticeUnreadMemberResponse(
    val memberId: Long,
    val userName: String?,
    val profileColor: ProfileColor
)

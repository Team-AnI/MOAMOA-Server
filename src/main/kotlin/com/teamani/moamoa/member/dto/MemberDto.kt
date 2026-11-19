package com.teamani.moamoa.member.dto

import com.teamani.moamoa.member.entity.MemberRole

data class MemberListResponse(
    val members: List<MemberSummaryResponse>
)

// userName은 User 도메인 구현 전까지 null 반환
data class MemberSummaryResponse(
    val memberId: Long,
    val userName: String?,
    val role: MemberRole
)

data class MemberLeaveResponse(
    val meetingId: Long
)

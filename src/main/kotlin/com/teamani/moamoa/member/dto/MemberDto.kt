package com.teamani.moamoa.member.dto

import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.user.entity.ProfileColor

data class MemberListResponse(
    val members: List<MemberSummaryResponse>
)

data class MemberSummaryResponse(
    val memberId: Long,
    val userName: String,
    val profileColor: ProfileColor,
    val role: MemberRole
)

data class MemberLeaveResponse(
    val meetingId: Long
)

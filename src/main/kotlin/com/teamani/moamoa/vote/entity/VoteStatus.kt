package com.teamani.moamoa.vote.entity

// 후보 시간별 응답 상태
enum class VoteStatus {
    // 투표 생성 시 후보 목록 기준으로 저장하는 관리자 행 (미응답)
    PENDING,

    // 참여 가능
    AVAILABLE,

    // 참여 불가
    UNAVAILABLE,
}

package com.teamani.moamoa.attendance.entity

// 관리자가 저장하는 출석 상태 (미체크 UNCHECKED는 조회 응답 전용이라 저장 값에서 제외)
enum class AttendanceStatus {
    // 출석
    PRESENT,

    // 지각
    LATE,

    // 결석
    ABSENT,
}

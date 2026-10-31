package com.teamani.moamoa.attendance.dto

import com.teamani.moamoa.attendance.entity.AttendanceStatus

// 출석 현황 조회 응답 전용 상태 (저장 값 3개에 미체크 UNCHECKED 추가)
enum class AttendanceViewStatus {
    PRESENT,
    LATE,
    ABSENT,

    // 출석 기록이 없는 구성원 (저장하지 않는 값)
    UNCHECKED;

    companion object {
        // 저장된 상태를 응답 상태로 변환 (기록이 없으면 UNCHECKED)
        fun from(status: AttendanceStatus?): AttendanceViewStatus =
            when (status) {
                AttendanceStatus.PRESENT -> PRESENT
                AttendanceStatus.LATE -> LATE
                AttendanceStatus.ABSENT -> ABSENT
                null -> UNCHECKED
            }
    }
}

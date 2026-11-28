package com.teamani.moamoa.schedule.entity

// 일정 참석 응답 상태 (출석 상태 ABSENT와 헷갈리지 않도록 불참은 NOT_ATTENDING)
enum class ScheduleResponseStatus {
    ATTENDING,
    NOT_ATTENDING,
    UNDECIDED
}

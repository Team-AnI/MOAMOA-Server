package com.teamani.moamoa.global.response

import java.time.OffsetDateTime

// 성공 응답 형식
// success=true, error=null 형태 고정. 응답 데이터가 없는 API(로그아웃 등)는 data=null
data class CommonResponse<T>(
    val success: Boolean = true,
    val data: T,
    val error: Any? = null,
    val timestamp: OffsetDateTime = currentTimestamp(),
)

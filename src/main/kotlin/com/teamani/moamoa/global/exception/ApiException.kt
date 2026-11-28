package com.teamani.moamoa.global.exception

import com.teamani.moamoa.global.response.ValidationDetail

// 비즈니스 규칙 위반 시 Service에서 던지는 예외
// HTTP 응답 변환은 GlobalExceptionHandler에서 처리
// details는 어떤 항목의 오류인지 알려야 할 때만 사용 (예: 7-6 token·newPassword 구분)
open class ApiException(
    val errorCode: ErrorCode,
    message: String? = errorCode.message,
    val details: List<ValidationDetail>? = null,
) : RuntimeException(message)

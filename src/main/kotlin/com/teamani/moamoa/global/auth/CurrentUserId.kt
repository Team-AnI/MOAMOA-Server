package com.teamani.moamoa.global.auth

// 액세스 토큰에서 꺼낸 요청자 userId를 Controller 파라미터로 받을 때 사용
// 이 파라미터가 있는 API는 토큰이 없거나 유효하지 않으면 401 UNAUTHORIZED
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserId

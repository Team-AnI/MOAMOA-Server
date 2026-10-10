package com.teamani.moamoa.notice.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

// 공지가 존재하지 않거나 요청한 모임의 공지가 아닐 때 404로 응답
@ResponseStatus(HttpStatus.NOT_FOUND)
class NoticeNotFoundException(message: String) : RuntimeException(message)

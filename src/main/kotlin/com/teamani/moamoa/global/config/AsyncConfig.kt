package com.teamani.moamoa.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

// @Async 사용 (비밀번호 재설정 메일을 응답과 분리해 발송)
@Configuration
@EnableAsync
class AsyncConfig

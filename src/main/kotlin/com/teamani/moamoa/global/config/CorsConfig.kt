package com.teamani.moamoa.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// 웹 프론트엔드(다른 도메인)의 브라우저 요청 허용 (앱은 CORS 대상 아님)
@Configuration
class CorsConfig(
    @Value("\${moamoa.cors.allowed-origins}")
    private val allowedOrigins: List<String>
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(*allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600)
    }
}

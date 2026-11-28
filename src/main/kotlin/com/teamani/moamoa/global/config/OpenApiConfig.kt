package com.teamani.moamoa.global.config

import com.teamani.moamoa.global.auth.CurrentUserId
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.models.security.SecurityRequirement
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// Swagger UI의 Authorize 버튼으로 액세스 토큰을 넣어 인증 API를 호출할 수 있도록 Bearer 인증 방식을 문서에 등록
// OpenAPI Bean을 직접 만들지 않아, 요청 헤더로 계산되는 servers(https://moamoa.aandiclub.com/api)는 그대로 유지
@Configuration
@SecurityScheme(
    name = OpenApiConfig.BEARER_AUTH,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
class OpenApiConfig {

    // @CurrentUserId를 받는 API만 토큰이 필요하므로, 그 API에만 자물쇠 표시와 Authorization 헤더 전송을 적용
    // (회원가입·로그인·토큰 재발급처럼 토큰 없이 호출하는 API는 제외)
    // Controller 메서드가 아닌 엔드포인트는 handlerMethod가 null로 전달될 수 있어 안전하게 확인
    @Bean
    fun bearerAuthOperationCustomizer(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        val requiresAuth = handlerMethod?.methodParameters
            ?.any { it.hasParameterAnnotation(CurrentUserId::class.java) } == true
        if (requiresAuth) {
            operation.addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))
        }
        operation
    }

    companion object {
        const val BEARER_AUTH = "bearerAuth"
    }
}

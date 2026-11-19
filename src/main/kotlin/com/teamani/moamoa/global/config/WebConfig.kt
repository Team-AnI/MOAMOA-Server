package com.teamani.moamoa.global.config

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.auth.CurrentUserIdArgumentResolver
import com.teamani.moamoa.global.auth.JwtTokenProvider
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// @WebMvcTest는 WebMvcConfigurer를 불러오지만 JwtTokenProvider는 불러오지 않으므로,
// @CurrentUserId를 쓰지 않는 Controller 테스트가 이 Bean 없이도 실행되도록 @Lazy로 주입
@Configuration
class WebConfig(
    @Lazy private val jwtTokenProvider: JwtTokenProvider
) : WebMvcConfigurer {

    // @CurrentUserId 값은 토큰에서 꺼내 채우므로, Swagger 문서에 요청 파라미터로 나오지 않게 제외
    init {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId::class.java)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserIdArgumentResolver(jwtTokenProvider))
    }
}

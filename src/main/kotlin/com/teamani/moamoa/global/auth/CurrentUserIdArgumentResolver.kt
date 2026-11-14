package com.teamani.moamoa.global.auth

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

// Authorization: Bearer {accessToken} 헤더에서 요청자 userId를 꺼내 @CurrentUserId 파라미터에 주입
class CurrentUserIdArgumentResolver(
    private val jwtTokenProvider: JwtTokenProvider
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUserId::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): Long {
        val header = webRequest.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw ApiException(ErrorCode.UNAUTHORIZED)
        }
        return jwtTokenProvider.getUserId(header.removePrefix(BEARER_PREFIX))
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}

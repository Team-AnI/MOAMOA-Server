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
            ?: throw ApiException(ErrorCode.UNAUTHORIZED)

        // 인증 스킴은 대소문자를 구분하지 않고(bearer·BEARER 허용), 스킴과 토큰 사이 공백이 여러 개여도 허용
        val parts = header.trim().split(WHITESPACE, limit = 2)
        if (parts.size != 2 || !parts[0].equals(BEARER_SCHEME, ignoreCase = true)) {
            throw ApiException(ErrorCode.UNAUTHORIZED)
        }
        return jwtTokenProvider.getUserId(parts[1])
    }

    companion object {
        private const val BEARER_SCHEME = "Bearer"
        private val WHITESPACE = Regex("\\s+")
    }
}

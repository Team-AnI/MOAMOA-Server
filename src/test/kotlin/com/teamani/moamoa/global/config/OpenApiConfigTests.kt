package com.teamani.moamoa.global.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// Swagger 문서(OpenAPI JSON)의 Bearer 인증 방식 등록과 API별 적용 범위 검증
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiConfigTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    init {
        Given("OpenAPI 문서를 조회하면") {
            val result = mockMvc.get(API_DOCS)

            Then("Bearer(JWT) 인증 방식이 등록되어 있다") {
                result.andExpect {
                    status { isOk() }
                    jsonPath("$.components.securitySchemes.bearerAuth.type") { value("http") }
                    jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
                    jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat") { value("JWT") }
                }
            }

            Then("@CurrentUserId를 받는 API에는 Bearer 인증이 적용된다") {
                result.andExpect {
                    jsonPath("$.paths['/v1/me/meetings'].get.security[0].bearerAuth") { exists() }
                    jsonPath("$.paths['/v1/auth/logout'].post.security[0].bearerAuth") { exists() }
                }
            }

            Then("토큰 없이 호출하는 회원가입·로그인·토큰 재발급 API에는 Bearer 인증이 적용되지 않는다") {
                result.andExpect {
                    jsonPath("$.paths['/v1/users'].post") { exists() }
                    jsonPath("$.paths['/v1/auth/login'].post") { exists() }
                    jsonPath("$.paths['/v1/auth/refresh'].post") { exists() }
                    jsonPath("$.paths['/v1/users'].post.security") { doesNotExist() }
                    jsonPath("$.paths['/v1/auth/login'].post.security") { doesNotExist() }
                    jsonPath("$.paths['/v1/auth/refresh'].post.security") { doesNotExist() }
                }
            }
        }

        Given("Nginx가 전달한 X-Forwarded-* 헤더와 함께 OpenAPI 문서를 조회하면") {
            val result = mockMvc.get(API_DOCS) {
                header("X-Forwarded-Proto", "https")
                header("X-Forwarded-Host", "moamoa.aandiclub.com")
                header("X-Forwarded-Port", "443")
                header("X-Forwarded-Prefix", "/api")
            }

            Then("servers는 기존처럼 운영 API 주소로 계산된다") {
                result.andExpect {
                    status { isOk() }
                    jsonPath("$.servers[0].url") { value("https://moamoa.aandiclub.com/api") }
                }
            }
        }
    }

    companion object {
        private const val API_DOCS = "/v3/api-docs"
    }
}

package com.teamani.moamoa.global.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options

// 웹 프론트엔드 도메인의 브라우저 사전 요청(preflight) 허용·차단 검증
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun preflight(origin: String) = mockMvc.options("/v1/me/meetings") {
        header(HttpHeaders.ORIGIN, origin)
        header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
    }.andReturn().response

    init {
        Given("허용된 웹 프론트엔드 도메인") {
            When("사전 요청을 보내면") {
                val response = preflight(ALLOWED_ORIGIN)

                Then("200과 허용 Origin 헤더를 반환한다") {
                    response.status shouldBe 200
                    response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) shouldBe ALLOWED_ORIGIN
                }
            }
        }

        Given("허용되지 않은 도메인") {
            When("사전 요청을 보내면") {
                val response = preflight("https://evil.example.com")

                Then("403을 반환하고 허용 Origin 헤더가 없다") {
                    response.status shouldBe 403
                    response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) shouldBe null
                }
            }
        }
    }

    companion object {
        // application-test.yml의 moamoa.cors.allowed-origins 값
        private const val ALLOWED_ORIGIN = "https://allowed.example.com"
    }
}

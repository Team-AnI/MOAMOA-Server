package com.teamani.moamoa

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class MoamoaApplicationTests(
    private val applicationContext: ApplicationContext,
) : BehaviorSpec({
    given("test profile로 애플리케이션을 실행하면") {
        `when`("Spring Context가 로딩될 때") {
            then("ApplicationContext가 주입된다") {
                applicationContext.shouldNotBeNull()
            }
        }
    }
})

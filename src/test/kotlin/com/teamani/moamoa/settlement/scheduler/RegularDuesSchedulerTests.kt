package com.teamani.moamoa.settlement.scheduler

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

// 운영 기본값(스케줄러 켬)에서 스케줄러가 등록되는지 확인 (테스트 프로필은 기본으로 꺼 둠)
@SpringBootTest(properties = ["moamoa.scheduler.enabled=true"])
@ActiveProfiles("test")
class RegularDuesSchedulerTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var applicationContext: ApplicationContext

    init {
        Given("스케줄러를 켠 설정으로 앱을 띄웠을 때") {
            When("빈을 조회하면") {
                Then("정기 회비 스케줄러가 등록되어 있다") {
                    applicationContext.getBeanProvider(RegularDuesScheduler::class.java).ifAvailable.shouldNotBeNull()
                }
            }
        }
    }
}

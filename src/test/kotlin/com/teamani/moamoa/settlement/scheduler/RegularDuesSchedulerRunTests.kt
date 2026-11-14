package com.teamani.moamoa.settlement.scheduler

import com.teamani.moamoa.settlement.service.RegularDuesBillingService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.OffsetDateTime

// 스케줄러 한 번 실행 시 건별 실패 격리와 설정으로 끄기를 검증 (빈 등록은 RegularDuesSchedulerTests)
class RegularDuesSchedulerRunTests : BehaviorSpec({

    Given("청구 대상 정기 회비가 3건이고 첫 번째 청구가 실패할 때") {
        val billingService: RegularDuesBillingService = mock(RegularDuesBillingService::class.java)
        `when`(billingService.findDueRegularDuesIds(anyNow())).thenReturn(listOf(1L, 2L, 3L))
        `when`(billingService.bill(eq(1L), anyNow())).thenThrow(IllegalStateException("청구 실패"))
        val scheduler = RegularDuesScheduler(billingService)

        When("스케줄러가 실행되면") {
            scheduler.billRegularDues()

            Then("예외가 밖으로 나가지 않고 나머지 정기 회비도 순서대로 청구한다") {
                val order = inOrder(billingService)
                order.verify(billingService).bill(eq(1L), anyNow())
                order.verify(billingService).bill(eq(2L), anyNow())
                order.verify(billingService).bill(eq(3L), anyNow())
            }
        }
    }

    Given("스케줄러 설정을 끈 앱일 때") {
        val contextRunner = ApplicationContextRunner()
            .withBean(RegularDuesBillingService::class.java, { mock(RegularDuesBillingService::class.java) })
            .withUserConfiguration(RegularDuesScheduler::class.java)

        When("moamoa.scheduler.enabled=false로 띄우면") {
            Then("스케줄러가 등록되지 않는다") {
                contextRunner.withPropertyValues("moamoa.scheduler.enabled=false").run { context ->
                    context.getBeanNamesForType(RegularDuesScheduler::class.java).size shouldBe 0
                }
            }
        }

        When("설정이 없으면 (운영 기본값)") {
            Then("스케줄러가 등록된다") {
                contextRunner.run { context ->
                    context.getBeanNamesForType(RegularDuesScheduler::class.java).size shouldBe 1
                }
            }
        }
    }
})

// Kotlin non-null 파라미터에 null을 넘기지 않도록 matcher만 등록하고 임의의 시각을 반환
private fun anyNow(): OffsetDateTime = any(OffsetDateTime::class.java) ?: OffsetDateTime.MIN

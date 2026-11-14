package com.teamani.moamoa.settlement.service

import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.RegularDues
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class RegularDuesBillingRuleTests : BehaviorSpec({

    fun regularDues(cycleType: CycleType, cycleDay: String) = RegularDues(
        meetingId = 1L,
        createdBy = 1L,
        title = "회비",
        amount = 10000,
        cycleType = cycleType,
        cycleDay = cycleDay,
        dueDays = 7,
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아"
    )

    Given("매월 지정일 정기 회비가 있을 때") {
        val dues = regularDues(CycleType.MONTHLY, "10")

        When("청구일을 확인하면") {
            Then("지정일만 청구일이다") {
                isBillingDay(dues, LocalDate.parse("2026-11-10")) shouldBe true
                isBillingDay(dues, LocalDate.parse("2026-11-11")) shouldBe false
            }
        }

        When("회차 기준일을 계산하면") {
            Then("해당 월 1일이다") {
                billingPeriod(dues, LocalDate.parse("2026-11-10")) shouldBe LocalDate.parse("2026-11-01")
            }
        }
    }

    Given("매월 말일(LAST) 정기 회비가 있을 때") {
        val dues = regularDues(CycleType.MONTHLY, "LAST")

        When("월마다 청구일을 확인하면") {
            Then("해당 월의 실제 마지막 날만 청구일이다") {
                isBillingDay(dues, LocalDate.parse("2026-02-28")) shouldBe true
                isBillingDay(dues, LocalDate.parse("2028-02-28")) shouldBe false
                isBillingDay(dues, LocalDate.parse("2028-02-29")) shouldBe true
                isBillingDay(dues, LocalDate.parse("2026-04-30")) shouldBe true
                isBillingDay(dues, LocalDate.parse("2026-05-30")) shouldBe false
                isBillingDay(dues, LocalDate.parse("2026-05-31")) shouldBe true
            }
        }
    }

    Given("매주 수요일 정기 회비가 있을 때") {
        val dues = regularDues(CycleType.WEEKLY, "WED")

        When("청구일을 확인하면") {
            Then("수요일만 청구일이다") {
                isBillingDay(dues, LocalDate.parse("2026-11-04")) shouldBe true
                isBillingDay(dues, LocalDate.parse("2026-11-05")) shouldBe false
            }
        }

        When("회차 기준일을 계산하면") {
            Then("해당 주 월요일이다") {
                billingPeriod(dues, LocalDate.parse("2026-11-04")) shouldBe LocalDate.parse("2026-11-02")
            }
        }
    }

    Given("공지 제목에 넣을 청구일 표시를 만들 때") {
        When("매월 회비면") {
            Then("청구일만 표시한다") {
                billingLabel(CycleType.MONTHLY, LocalDate.parse("2026-09-25")) shouldBe "2026년 9월 25일"
            }
        }

        When("매주 회비면") {
            Then("청구일과 그 달의 몇 번째 청구인지 표시한다") {
                billingLabel(CycleType.WEEKLY, LocalDate.parse("2026-10-07")) shouldBe "2026년 10월 7일, 1주차"
                billingLabel(CycleType.WEEKLY, LocalDate.parse("2026-10-28")) shouldBe "2026년 10월 28일, 4주차"
                billingLabel(CycleType.WEEKLY, LocalDate.parse("2026-10-29")) shouldBe "2026년 10월 29일, 5주차"
                billingLabel(CycleType.WEEKLY, LocalDate.parse("2026-11-01")) shouldBe "2026년 11월 1일, 1주차"
            }
        }
    }

    Given("매주 일요일 정기 회비가 있을 때") {
        val dues = regularDues(CycleType.WEEKLY, "SUN")

        When("회차 기준일을 계산하면") {
            Then("일요일은 그 주(월요일 시작)의 마지막 날이라 6일 전 월요일이다") {
                isBillingDay(dues, LocalDate.parse("2026-11-08")) shouldBe true
                billingPeriod(dues, LocalDate.parse("2026-11-08")) shouldBe LocalDate.parse("2026-11-02")
            }
        }
    }
})

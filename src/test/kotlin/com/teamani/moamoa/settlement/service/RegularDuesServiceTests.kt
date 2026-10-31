package com.teamani.moamoa.settlement.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.settlement.dto.RegularDuesCreateRequest
import com.teamani.moamoa.settlement.dto.RegularDuesUpdateRequest
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.RegularDues
import com.teamani.moamoa.settlement.repository.RegularDuesRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.LocalTime
import java.util.Optional

class RegularDuesServiceTests : BehaviorSpec({

    val regularDuesRepository: RegularDuesRepository = mock(RegularDuesRepository::class.java)
    val settlementRepository: SettlementRepository = mock(SettlementRepository::class.java)
    val regularDuesService = RegularDuesService(regularDuesRepository, settlementRepository)

    afterEach { clearInvocations(regularDuesRepository, settlementRepository) }

    fun regularDues(
        id: Long = 7L,
        cycleType: CycleType = CycleType.MONTHLY,
        cycleDay: String = "10"
    ) = RegularDues(
        id = id,
        meetingId = 1L,
        createdBy = 1L,
        title = "월 회비",
        amount = 10000,
        cycleType = cycleType,
        cycleDay = cycleDay,
        dueDays = 7,
        bankName = "국민은행",
        accountNumber = "123456789012",
        accountHolder = "김모아"
    )

    fun createRequest(cycleType: String = "MONTHLY", cycleDay: String = "10", sendTime: String? = null) =
        RegularDuesCreateRequest(
            title = "월 회비",
            amount = 10000,
            cycleType = cycleType,
            cycleDay = cycleDay,
            sendTime = sendTime,
            dueDays = 7,
            bankName = "국민은행",
            accountNumber = "123456789012",
            accountHolder = "김모아"
        )

    Given("발송 시간과 공지 발행 여부 없이 정기 회비 등록 요청이 주어졌을 때") {
        `when`(regularDuesRepository.save(any(RegularDues::class.java))).thenReturn(regularDues())

        When("createRegularDues 메서드를 호출하면") {
            val response = regularDuesService.createRegularDues(1L, 1L, createRequest())

            Then("기본값(09:00, 공지 미발행, 활성)과 등록자로 저장하고 요약을 반환한다") {
                val captor = ArgumentCaptor.forClass(RegularDues::class.java)
                verify(regularDuesRepository).save(captor.capture())
                val saved = captor.value

                saved.createdBy shouldBe 1L
                saved.cycleType shouldBe CycleType.MONTHLY
                saved.sendTime shouldBe LocalTime.of(9, 0)
                saved.postNotice shouldBe false
                saved.isActive shouldBe true

                response.regularDuesId shouldBe 7L
                response.isActive shouldBe true
            }
        }
    }

    Given("반복 주기에 맞는 지정일로 등록 요청이 주어졌을 때") {
        `when`(regularDuesRepository.save(any(RegularDues::class.java))).thenReturn(regularDues())

        listOf("WEEKLY" to "MON", "WEEKLY" to "SUN", "MONTHLY" to "1", "MONTHLY" to "28", "MONTHLY" to "LAST")
            .forEach { (cycleType, cycleDay) ->
                When("$cycleType / $cycleDay 로 createRegularDues 메서드를 호출하면") {
                    regularDuesService.createRegularDues(1L, 1L, createRequest(cycleType, cycleDay, "18:30"))

                    Then("정기 회비가 저장된다") {
                        val captor = ArgumentCaptor.forClass(RegularDues::class.java)
                        verify(regularDuesRepository).save(captor.capture())
                        captor.value.cycleDay shouldBe cycleDay
                        captor.value.sendTime shouldBe LocalTime.of(18, 30)
                    }
                }
            }
    }

    Given("시를 한 자리로 쓴 발송 시간으로 등록 요청이 주어졌을 때") {
        `when`(regularDuesRepository.save(any(RegularDues::class.java))).thenReturn(regularDues())

        When("9:00으로 createRegularDues 메서드를 호출하면") {
            regularDuesService.createRegularDues(1L, 1L, createRequest(sendTime = "9:00"))

            Then("09:00으로 저장된다") {
                val captor = ArgumentCaptor.forClass(RegularDues::class.java)
                verify(regularDuesRepository).save(captor.capture())
                captor.value.sendTime shouldBe LocalTime.of(9, 0)
            }
        }
    }

    Given("반복 주기에 맞지 않는 지정일로 등록 요청이 주어졌을 때") {
        listOf("WEEKLY" to "10", "WEEKLY" to "mon", "MONTHLY" to "MON", "MONTHLY" to "0", "MONTHLY" to "29", "MONTHLY" to "01")
            .forEach { (cycleType, cycleDay) ->
                When("$cycleType / $cycleDay 로 createRegularDues 메서드를 호출하면") {
                    val exception = shouldThrow<ApiException> {
                        regularDuesService.createRegularDues(1L, 1L, createRequest(cycleType, cycleDay))
                    }

                    Then("VALIDATION_ERROR 예외가 발생하고 저장되지 않는다") {
                        exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                        verify(regularDuesRepository, never()).save(any(RegularDues::class.java))
                    }
                }
            }
    }

    Given("모임에 정기 회비가 있을 때") {
        `when`(regularDuesRepository.findAllByMeetingIdOrderByIdAsc(1L)).thenReturn(
            listOf(regularDues(7L), regularDues(8L, CycleType.WEEKLY, "FRI"))
        )

        When("getRegularDuesList 메서드를 호출하면") {
            val response = regularDuesService.getRegularDuesList(1L, 1L)

            Then("등록순으로 설정 값을 반환하고 발송 시간은 HH:mm 형식이다") {
                response.regularDues.map { it.regularDuesId } shouldBe listOf(7L, 8L)
                response.regularDues[0].sendTime shouldBe "09:00"
                response.regularDues[0].bankName shouldBe "국민은행"
                response.regularDues[0].accountNumber shouldBe "123456789012"
                response.regularDues[0].accountHolder shouldBe "김모아"
                response.regularDues[1].cycleType shouldBe CycleType.WEEKLY
                response.regularDues[1].cycleDay shouldBe "FRI"
            }
        }
    }

    Given("일부 필드만 담은 정기 회비 수정 요청이 주어졌을 때") {
        val entity = regularDues()
        `when`(regularDuesRepository.findByIdAndMeetingId(7L, 1L)).thenReturn(entity)

        When("금액과 활성화 여부만 보내면") {
            val response = regularDuesService.updateRegularDues(
                1L, 1L, 7L,
                RegularDuesUpdateRequest(amount = Optional.of(15000), isActive = Optional.of(false))
            )

            Then("보낸 필드만 바뀌고 나머지는 유지된다") {
                response.amount shouldBe 15000
                response.isActive shouldBe false
                response.title shouldBe "월 회비"
                response.cycleType shouldBe CycleType.MONTHLY
                response.cycleDay shouldBe "10"
                response.sendTime shouldBe "09:00"
            }
        }
    }

    Given("반복 주기를 바꾸는 수정 요청이 주어졌을 때") {
        `when`(regularDuesRepository.findByIdAndMeetingId(7L, 1L)).thenReturn(regularDues())

        When("반복 주기와 지정일을 함께 보내면") {
            val response = regularDuesService.updateRegularDues(
                1L, 1L, 7L,
                RegularDuesUpdateRequest(cycleType = Optional.of("WEEKLY"), cycleDay = Optional.of("MON"))
            )

            Then("반복 설정이 바뀐다") {
                response.cycleType shouldBe CycleType.WEEKLY
                response.cycleDay shouldBe "MON"
            }
        }

        When("반복 주기만 보내면") {
            val exception = shouldThrow<ApiException> {
                regularDuesService.updateRegularDues(
                    1L, 1L, 7L,
                    RegularDuesUpdateRequest(cycleType = Optional.of("WEEKLY"))
                )
            }

            Then("VALIDATION_ERROR 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
            }
        }
    }

    Given("지정일만 담은 수정 요청이 주어졌을 때") {
        `when`(regularDuesRepository.findByIdAndMeetingId(7L, 1L)).thenReturn(regularDues())

        When("기존 반복 주기(MONTHLY)에 맞는 지정일을 보내면") {
            val response = regularDuesService.updateRegularDues(
                1L, 1L, 7L,
                RegularDuesUpdateRequest(cycleDay = Optional.of("LAST"))
            )

            Then("지정일이 바뀐다") {
                response.cycleDay shouldBe "LAST"
            }
        }

        When("기존 반복 주기(MONTHLY)에 맞지 않는 지정일을 보내면") {
            val exception = shouldThrow<ApiException> {
                regularDuesService.updateRegularDues(
                    1L, 1L, 7L,
                    RegularDuesUpdateRequest(cycleDay = Optional.of("MON"))
                )
            }

            Then("VALIDATION_ERROR 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
            }
        }
    }

    Given("필수 항목을 null로 보낸 수정 요청이 주어졌을 때") {
        val entity = regularDues()
        `when`(regularDuesRepository.findByIdAndMeetingId(7L, 1L)).thenReturn(entity)

        val clearedRequests = mapOf(
            "title" to RegularDuesUpdateRequest(title = Optional.empty()),
            "amount" to RegularDuesUpdateRequest(amount = Optional.empty()),
            "cycleDay" to RegularDuesUpdateRequest(cycleDay = Optional.empty()),
            "sendTime" to RegularDuesUpdateRequest(sendTime = Optional.empty()),
            "isActive" to RegularDuesUpdateRequest(isActive = Optional.empty())
        )

        clearedRequests.forEach { (field, request) ->
            When("$field 를 null로 보내면") {
                val exception = shouldThrow<ApiException> {
                    regularDuesService.updateRegularDues(1L, 1L, 7L, request)
                }

                Then("VALIDATION_ERROR 예외가 발생하고 기존 값은 유지된다") {
                    exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                    entity.title shouldBe "월 회비"
                    entity.amount shouldBe 10000
                    entity.isActive shouldBe true
                }
            }
        }
    }

    Given("없거나 다른 모임에 속한 정기 회비가 주어졌을 때") {
        `when`(regularDuesRepository.findByIdAndMeetingId(999L, 1L)).thenReturn(null)

        When("updateRegularDues 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                regularDuesService.updateRegularDues(1L, 1L, 999L, RegularDuesUpdateRequest(amount = Optional.of(1000)))
            }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }

        When("deleteRegularDues 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                regularDuesService.deleteRegularDues(1L, 1L, 999L)
            }

            Then("NOT_FOUND 예외가 발생하고 정산 연결은 해제하지 않는다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
                verify(settlementRepository, never()).clearRegularDuesId(anyLong())
            }
        }
    }

    Given("삭제할 정기 회비가 있을 때") {
        val entity = regularDues()
        `when`(regularDuesRepository.findByIdAndMeetingId(7L, 1L)).thenReturn(entity)

        When("deleteRegularDues 메서드를 호출하면") {
            val response = regularDuesService.deleteRegularDues(1L, 1L, 7L)

            Then("기존 회차 정산과의 연결을 먼저 해제한 뒤 삭제한다") {
                val order = inOrder(settlementRepository, regularDuesRepository)
                order.verify(settlementRepository).clearRegularDuesId(7L)
                order.verify(regularDuesRepository).delete(entity)
                response.regularDuesId shouldBe 7L
            }
        }
    }
})

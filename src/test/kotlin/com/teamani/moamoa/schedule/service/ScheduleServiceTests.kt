package com.teamani.moamoa.schedule.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.schedule.dto.ScheduleCreateRequest
import com.teamani.moamoa.schedule.dto.ScheduleUpdateRequest
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.LocalDate
import java.time.OffsetDateTime

class ScheduleServiceTests : BehaviorSpec({

    val scheduleRepository: ScheduleRepository = mock(ScheduleRepository::class.java)
    val scheduleService = ScheduleService(scheduleRepository)

    afterEach { clearInvocations(scheduleRepository) }

    Given("모임 일정 생성 요청 데이터가 주어졌을 때") {
        val meetingId = 1L
        val userId = 1L
        val startAt = OffsetDateTime.parse("2026-10-11T07:00:00+09:00")
        val request = ScheduleCreateRequest(
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startAt = startAt,
            endAt = OffsetDateTime.parse("2026-10-11T09:00:00+09:00"),
            location = "반포 한강공원"
        )

        // DB에서 UTC로 읽힌 일시도 응답에서는 KST로 변환되는지 확인
        val savedEntity = Schedule(
            id = 20L,
            meetingId = meetingId,
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startsAt = OffsetDateTime.parse("2026-10-10T22:00:00Z"),
            endedAt = request.endAt,
            location = "반포 한강공원"
        )

        `when`(scheduleRepository.save(any(Schedule::class.java))).thenReturn(savedEntity)

        When("createSchedule 메서드를 호출하면") {
            val response = scheduleService.createSchedule(meetingId, userId, request)

            Then("Schedule 엔티티가 영속화되고 올바른 응답 DTO가 반환된다") {
                response.shouldNotBeNull()
                response.scheduleId shouldBe 20L
                response.title shouldBe "10월 정기 러닝"
                response.startAt.toString() shouldBe "2026-10-11T07:00+09:00"
                verify(scheduleRepository).save(any(Schedule::class.java))
            }
        }
    }

    Given("모임 일정 목록 조회 기간이 주어졌을 때") {
        val meetingId = 1L
        val userId = 1L
        val from = LocalDate.parse("2026-10-01")
        val to = LocalDate.parse("2026-10-31")
        val rangeStart = OffsetDateTime.parse("2026-10-01T00:00:00+09:00")
        val rangeEnd = OffsetDateTime.parse("2026-11-01T00:00:00+09:00")

        // DB에서 UTC로 읽힌 일시도 응답에서는 KST로 변환되는지 확인
        val schedule = Schedule(
            id = 20L,
            meetingId = meetingId,
            title = "10월 정기 러닝",
            startsAt = OffsetDateTime.parse("2026-10-10T22:00:00Z"),
            endedAt = OffsetDateTime.parse("2026-10-11T00:00:00Z"),
            location = "반포 한강공원"
        )

        `when`(
            scheduleRepository.findAllByMeetingIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAsc(
                meetingId, rangeStart, rangeEnd
            )
        ).thenReturn(listOf(schedule))

        When("getSchedules 메서드를 호출하면") {
            val response = scheduleService.getSchedules(meetingId, userId, from, to)

            Then("to 다음 날 0시 전까지의 일정을 조회하고 일시를 KST로 변환해 반환한다") {
                response.schedules.size shouldBe 1
                val summary = response.schedules[0]
                summary.scheduleId shouldBe 20L
                summary.title shouldBe "10월 정기 러닝"
                summary.startAt.toString() shouldBe "2026-10-11T07:00+09:00"
                summary.endAt.toString() shouldBe "2026-10-11T09:00+09:00"
                summary.location shouldBe "반포 한강공원"
            }
        }

        When("기간 내 일정이 없으면") {
            val response = scheduleService.getSchedules(meetingId, userId, from, from)

            Then("빈 목록을 반환한다") {
                response.schedules.shouldBeEmpty()
            }
        }
    }

    Given("조회 시작일이 종료일보다 이후인 기간이 주어졌을 때") {
        val from = LocalDate.parse("2026-10-31")
        val to = LocalDate.parse("2026-10-01")

        When("getSchedules 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                scheduleService.getSchedules(1L, 1L, from, to)
            }

            Then("VALIDATION_ERROR 예외가 발생하고 일정을 조회하지 않는다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verifyNoInteractions(scheduleRepository)
            }
        }
    }

    Given("모임에 속한 일정 ID가 주어졌을 때") {
        val meetingId = 1L
        val scheduleId = 20L

        // DB에서 UTC로 읽힌 일시도 응답에서는 KST로 변환되는지 확인
        val schedule = Schedule(
            id = scheduleId,
            meetingId = meetingId,
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startsAt = OffsetDateTime.parse("2026-10-10T22:00:00Z"),
            endedAt = OffsetDateTime.parse("2026-10-11T00:00:00Z"),
            location = "반포 한강공원"
        )

        `when`(scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)).thenReturn(schedule)

        When("getSchedule 메서드를 호출하면") {
            val response = scheduleService.getSchedule(meetingId, 1L, scheduleId)

            Then("일정 상세를 KST 일시로 반환한다") {
                response.scheduleId shouldBe 20L
                response.title shouldBe "10월 정기 러닝"
                response.description shouldBe "반포 한강공원 집합"
                response.startAt.toString() shouldBe "2026-10-11T07:00+09:00"
                response.endAt.toString() shouldBe "2026-10-11T09:00+09:00"
                response.location shouldBe "반포 한강공원"
            }
        }
    }

    Given("없거나 다른 모임에 속한 일정 ID가 주어졌을 때") {
        When("getSchedule 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                scheduleService.getSchedule(1L, 1L, 999L)
            }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    Given("수정할 일정이 모임에 존재할 때") {
        val meetingId = 1L
        val scheduleId = 20L

        // 각 When마다 원본 상태에서 시작하도록 새 엔티티를 만들어 조회 결과로 지정
        fun stubSchedule(): Schedule {
            val schedule = Schedule(
                id = scheduleId,
                meetingId = meetingId,
                title = "10월 정기 러닝",
                description = "반포 한강공원 집합",
                startsAt = OffsetDateTime.parse("2026-10-11T07:00:00+09:00"),
                endedAt = OffsetDateTime.parse("2026-10-11T09:00:00+09:00"),
                location = "반포 한강공원"
            )
            `when`(scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)).thenReturn(schedule)
            return schedule
        }

        When("시작·종료 일시만 보내면") {
            val schedule = stubSchedule()
            val request = ScheduleUpdateRequest(
                startAt = OffsetDateTime.parse("2026-10-10T23:00:00Z"),
                endAt = OffsetDateTime.parse("2026-10-11T01:00:00Z")
            )
            val response = scheduleService.updateSchedule(meetingId, 1L, scheduleId, request)

            Then("보낸 필드만 변경되고 나머지는 유지되며 일시는 KST로 반환된다") {
                response.title shouldBe "10월 정기 러닝"
                response.description shouldBe "반포 한강공원 집합"
                response.location shouldBe "반포 한강공원"
                response.startAt.toString() shouldBe "2026-10-11T08:00+09:00"
                response.endAt.toString() shouldBe "2026-10-11T10:00+09:00"
                schedule.startsAt shouldBe request.startAt
            }
        }

        When("제목·설명·장소만 보내면") {
            stubSchedule()
            val request = ScheduleUpdateRequest(title = "10월 번개 러닝", description = "잠수교 집합", location = "잠수교")
            val response = scheduleService.updateSchedule(meetingId, 1L, scheduleId, request)

            Then("해당 필드만 변경되고 일시는 유지된다") {
                response.title shouldBe "10월 번개 러닝"
                response.description shouldBe "잠수교 집합"
                response.location shouldBe "잠수교"
                response.startAt.toString() shouldBe "2026-10-11T07:00+09:00"
                response.endAt.toString() shouldBe "2026-10-11T09:00+09:00"
            }
        }

        When("기존 시작 일시보다 이전인 종료 일시만 보내면") {
            val schedule = stubSchedule()
            val request = ScheduleUpdateRequest(endAt = OffsetDateTime.parse("2026-10-11T06:00:00+09:00"))
            val exception = shouldThrow<ApiException> {
                scheduleService.updateSchedule(meetingId, 1L, scheduleId, request)
            }

            Then("VALIDATION_ERROR 예외가 발생하고 일정은 변경되지 않는다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                schedule.endedAt.toString() shouldBe "2026-10-11T09:00+09:00"
            }
        }

        When("기존 종료 일시보다 이후인 시작 일시만 보내면") {
            stubSchedule()
            val request = ScheduleUpdateRequest(startAt = OffsetDateTime.parse("2026-10-11T10:00:00+09:00"))
            val exception = shouldThrow<ApiException> {
                scheduleService.updateSchedule(meetingId, 1L, scheduleId, request)
            }

            Then("VALIDATION_ERROR 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.VALIDATION_ERROR
            }
        }
    }

    Given("없거나 다른 모임에 속한 일정을 수정하려 할 때") {
        When("updateSchedule 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                scheduleService.updateSchedule(1L, 1L, 999L, ScheduleUpdateRequest(title = "변경된 제목"))
            }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    Given("삭제할 일정이 모임에 존재할 때") {
        val meetingId = 1L
        val scheduleId = 20L
        val schedule = Schedule(
            id = scheduleId,
            meetingId = meetingId,
            title = "10월 정기 러닝",
            startsAt = OffsetDateTime.parse("2026-10-11T07:00:00+09:00")
        )

        `when`(scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)).thenReturn(schedule)

        When("deleteSchedule 메서드를 호출하면") {
            val response = scheduleService.deleteSchedule(meetingId, 1L, scheduleId)

            Then("일정이 삭제되고 삭제된 일정 ID가 반환된다") {
                response.scheduleId shouldBe 20L
                verify(scheduleRepository).delete(schedule)
            }
        }
    }

    Given("없거나 다른 모임에 속한 일정을 삭제하려 할 때") {
        When("deleteSchedule 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                scheduleService.deleteSchedule(1L, 1L, 999L)
            }

            Then("NOT_FOUND 예외가 발생하고 아무것도 삭제하지 않는다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
                verify(scheduleRepository, never()).delete(any(Schedule::class.java))
            }
        }
    }
})

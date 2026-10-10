package com.teamani.moamoa.schedule.service

import com.teamani.moamoa.schedule.dto.ScheduleCreateRequest
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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

        val savedEntity = Schedule(
            id = 20L,
            meetingId = meetingId,
            title = "10월 정기 러닝",
            description = "반포 한강공원 집합",
            startsAt = startAt,
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
                response.startAt shouldBe startAt
                verify(scheduleRepository).save(any(Schedule::class.java))
            }
        }
    }
})

package com.teamani.moamoa.attendance.service

import com.teamani.moamoa.attendance.dto.AttendanceUpdateItemRequest
import com.teamani.moamoa.attendance.dto.AttendanceUpdateRequest
import com.teamani.moamoa.attendance.dto.AttendanceViewStatus
import com.teamani.moamoa.attendance.entity.Attendance
import com.teamani.moamoa.attendance.entity.AttendanceStatus
import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.vote.repository.VoteRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.hibernate.exception.ConstraintViolationException
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.OffsetDateTime

class AttendanceServiceTests : BehaviorSpec({

    val meetingRepository: MeetingRepository = mock(MeetingRepository::class.java)
    val memberRepository: MemberRepository = mock(MemberRepository::class.java)
    val scheduleRepository: ScheduleRepository = mock(ScheduleRepository::class.java)
    val attendanceRepository: AttendanceRepository = mock(AttendanceRepository::class.java)
    val memberService = MemberService(meetingRepository, memberRepository, mock(VoteRepository::class.java), attendanceRepository)
    val attendanceService = AttendanceService(memberService, memberRepository, scheduleRepository, attendanceRepository)

    val meetingId = 1L
    val scheduleId = 5L
    val adminUserId = 10L
    val memberUserId = 20L
    val quietUserId = 30L
    val outsiderUserId = 50L
    val admin = Member(meetingId = meetingId, userId = adminUserId, role = MemberRole.ADMIN, id = 100L)
    val member = Member(meetingId = meetingId, userId = memberUserId, role = MemberRole.MEMBER, id = 200L)
    // 출석 기록이 없는 구성원
    val quietMember = Member(meetingId = meetingId, userId = quietUserId, role = MemberRole.MEMBER, id = 300L)
    // 다른 모임 구성원의 memberId (이 모임 구성원 목록에 없음)
    val outsiderMemberId = 900L

    val firstCheckedAt = OffsetDateTime.parse("2026-10-20T19:00:00+09:00")

    fun schedule(
        status: ScheduleStatus = ScheduleStatus.SCHEDULED,
        startsAt: OffsetDateTime = OffsetDateTime.now().plusDays(1)
    ) = Schedule(meetingId = meetingId, title = "10월 정기 모임", startsAt = startsAt, status = status, id = scheduleId)

    fun attendance(memberId: Long, status: AttendanceStatus) =
        Attendance(memberId = memberId, scheduleId = scheduleId, status = status, createdAt = firstCheckedAt, id = memberId + 1000L)

    fun request(vararg items: Pair<Long?, String?>) =
        AttendanceUpdateRequest(items.map { (memberId, status) -> AttendanceUpdateItemRequest(memberId, status) })

    fun stubSchedule(schedule: Schedule?, attendances: List<Attendance> = emptyList()) {
        `when`(scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)).thenReturn(schedule)
        `when`(attendanceRepository.findAllByScheduleId(scheduleId)).thenReturn(attendances)
    }

    @Suppress("UNCHECKED_CAST")
    fun savedAttendances(): List<Attendance> {
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Attendance>>
        verify(attendanceRepository).saveAllAndFlush(captor.capture())
        return captor.value
    }

    beforeTest {
        reset(meetingRepository, memberRepository, scheduleRepository, attendanceRepository)
        `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(true)
        listOf(admin, member, quietMember).forEach {
            `when`(memberRepository.findByMeetingIdAndUserId(meetingId, it.userId)).thenReturn(it)
        }
        `when`(memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId)).thenReturn(listOf(admin, member, quietMember))
    }

    Given("출석 처리 요청이 주어졌을 때") {
        When("ADMIN이 처음 체크하는 구성원과 이미 체크한 구성원을 함께 보내면") {
            Then("기존 기록은 상태와 변경 일시만 바꾸고 새 기록을 만들며, 보낸 항목을 요청 순서대로 반환한다") {
                val checked = attendance(200L, AttendanceStatus.PRESENT)
                // 요청에 포함하지 않은 구성원의 기존 기록
                val notRequested = attendance(100L, AttendanceStatus.ABSENT)
                stubSchedule(schedule(), listOf(checked, notRequested))

                val response = attendanceService.updateAttendances(
                    meetingId, adminUserId, scheduleId, request(300L to "ABSENT", 200L to "LATE")
                )

                val saved = savedAttendances()
                saved shouldHaveSize 1
                saved[0].memberId shouldBe 300L
                saved[0].scheduleId shouldBe scheduleId
                saved[0].status shouldBe AttendanceStatus.ABSENT
                saved[0].updatedAt shouldBe saved[0].createdAt

                checked.status shouldBe AttendanceStatus.LATE
                checked.createdAt shouldBe firstCheckedAt
                checked.updatedAt shouldNotBe firstCheckedAt
                checked.updatedAt shouldBe saved[0].createdAt

                notRequested.status shouldBe AttendanceStatus.ABSENT
                notRequested.updatedAt shouldBe firstCheckedAt

                response.scheduleId shouldBe scheduleId
                response.attendances.map { it.memberId to it.status } shouldContainExactly
                    listOf(300L to AttendanceStatus.ABSENT, 200L to AttendanceStatus.LATE)
            }
        }
        When("이미 체크한 구성원에게 같은 상태를 보내면") {
            Then("변경 일시를 바꾸지 않고 새 기록도 만들지 않는다") {
                val checked = attendance(200L, AttendanceStatus.PRESENT)
                stubSchedule(schedule(), listOf(checked))

                attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))

                checked.status shouldBe AttendanceStatus.PRESENT
                checked.updatedAt shouldBe firstCheckedAt
                savedAttendances().shouldBeEmpty()
            }
        }
        When("투표로 확정된 일정의 시작 일시가 이미 지났으면") {
            Then("시작 전후와 관계없이 출석을 처리한다") {
                stubSchedule(schedule(ScheduleStatus.CONFIRMED, startsAt = OffsetDateTime.now().minusDays(1)))

                attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))

                savedAttendances().map { it.memberId to it.status } shouldContainExactly
                    listOf(200L to AttendanceStatus.PRESENT)
            }
        }
        When("MEMBER가 요청하면") {
            Then("403 FORBIDDEN 예외가 발생하고 출석 기록을 조회·변경하지 않는다") {
                stubSchedule(schedule())

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(meetingId, memberUserId, scheduleId, request(200L to "PRESENT"))
                }.errorCode shouldBe ErrorCode.FORBIDDEN
                verify(attendanceRepository, never()).findAllByScheduleId(anyLong())
                verify(attendanceRepository, never()).saveAllAndFlush(anyList<Attendance>())
            }
        }
        When("모임 구성원이 아니면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                stubSchedule(schedule())

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(meetingId, outsiderUserId, scheduleId, request(200L to "PRESENT"))
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
        When("모임이 없으면") {
            Then("404 NOT_FOUND 예외가 발생하고 일정을 조회하지 않는다") {
                `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(false)

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))
                }.errorCode shouldBe ErrorCode.NOT_FOUND
                verify(scheduleRepository, never()).findByIdAndMeetingId(anyLong(), anyLong())
            }
        }
        When("일정이 없거나 다른 모임의 일정이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                stubSchedule(null)

                val exception = shouldThrow<ApiException> {
                    attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))
                }
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
                exception.message shouldBe "일정을 찾을 수 없습니다."
            }
        }
        When("같은 memberId가 두 번 있으면") {
            Then("400 VALIDATION_ERROR 예외가 발생하고 저장하지 않는다") {
                stubSchedule(schedule())

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(
                        meetingId, adminUserId, scheduleId, request(200L to "PRESENT", 200L to "LATE")
                    )
                }.errorCode shouldBe ErrorCode.VALIDATION_ERROR
                verify(attendanceRepository, never()).saveAllAndFlush(anyList<Attendance>())
            }
        }
        When("취소된 일정에 같은 memberId를 두 번 보내면") {
            Then("상태 검사가 먼저라 422 UNPROCESSABLE_ENTITY 예외가 발생한다") {
                stubSchedule(schedule(ScheduleStatus.CANCELED))

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(
                        meetingId, adminUserId, scheduleId, request(200L to "PRESENT", 200L to "LATE")
                    )
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
            }
        }
        When("취소된 일정이면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생하고 출석 기록을 조회·변경하지 않는다") {
                stubSchedule(schedule(ScheduleStatus.CANCELED))

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                verify(attendanceRepository, never()).findAllByScheduleId(anyLong())
                verify(attendanceRepository, never()).saveAllAndFlush(anyList<Attendance>())
            }
        }
        When("투표 중인 일정이면") {
            Then("422 UNPROCESSABLE_ENTITY 예외가 발생한다") {
                stubSchedule(schedule(ScheduleStatus.VOTING))

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))
                }.errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                verify(attendanceRepository, never()).saveAllAndFlush(anyList<Attendance>())
            }
        }
        When("취소된 일정에 모임 구성원이 아닌 memberId를 보내면") {
            Then("구성원 검사가 먼저라 404 NOT_FOUND 예외가 발생한다") {
                stubSchedule(schedule(ScheduleStatus.CANCELED))

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(
                        meetingId, adminUserId, scheduleId, request(outsiderMemberId to "PRESENT")
                    )
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("모임 구성원이 아닌 memberId가 하나라도 포함되어 있으면") {
            Then("404 NOT_FOUND 예외가 발생하고 다른 항목도 저장·변경하지 않는다") {
                val checked = attendance(200L, AttendanceStatus.PRESENT)
                stubSchedule(schedule(), listOf(checked))

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(
                        meetingId, adminUserId, scheduleId,
                        request(200L to "LATE", 300L to "ABSENT", outsiderMemberId to "PRESENT")
                    )
                }.errorCode shouldBe ErrorCode.NOT_FOUND
                checked.status shouldBe AttendanceStatus.PRESENT
                checked.updatedAt shouldBe firstCheckedAt
                verify(attendanceRepository, never()).saveAllAndFlush(anyList<Attendance>())
            }
        }
        When("조회 직후 동시 요청이 같은 구성원의 첫 출석을 먼저 저장했으면") {
            Then("출석 unique 제약 위반을 409 CONFLICT 예외로 변환한다") {
                stubSchedule(schedule())
                `when`(attendanceRepository.saveAllAndFlush(anyList<Attendance>()))
                    .thenThrow(integrityViolation(Attendance.UK_MEMBER_ID_SCHEDULE_ID))

                shouldThrow<ApiException> {
                    attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))
                }.errorCode shouldBe ErrorCode.CONFLICT
            }
        }
        When("출석 unique 제약이 아닌 무결성 오류가 발생하면") {
            Then("409로 변환하지 않고 예외를 그대로 전달한다") {
                stubSchedule(schedule())
                `when`(attendanceRepository.saveAllAndFlush(anyList<Attendance>()))
                    .thenThrow(integrityViolation("fk_attendance_member_id"))

                shouldThrow<DataIntegrityViolationException> {
                    attendanceService.updateAttendances(meetingId, adminUserId, scheduleId, request(200L to "PRESENT"))
                }
            }
        }
    }

    Given("출석 현황을 조회할 때") {
        When("MEMBER가 요청하면") {
            Then("다른 구성원을 포함한 구성원 전체를 memberId 오름차순으로 반환하고, 기록이 없는 구성원은 UNCHECKED로 반환한다") {
                stubSchedule(
                    schedule(),
                    listOf(attendance(200L, AttendanceStatus.LATE), attendance(100L, AttendanceStatus.PRESENT))
                )

                val response = attendanceService.getAttendances(meetingId, memberUserId, scheduleId)

                response.scheduleId shouldBe scheduleId
                response.attendances.map { it.memberId } shouldContainExactly listOf(100L, 200L, 300L)
                response.attendances.map { it.status } shouldContainExactly
                    listOf(AttendanceViewStatus.PRESENT, AttendanceViewStatus.LATE, AttendanceViewStatus.UNCHECKED)
                response.attendances.map { it.nickname } shouldContainExactly listOf(null, null, null)
            }
        }
        When("결석으로 체크된 구성원이 있으면") {
            Then("ABSENT로 반환한다") {
                stubSchedule(schedule(), listOf(attendance(300L, AttendanceStatus.ABSENT)))

                val response = attendanceService.getAttendances(meetingId, adminUserId, scheduleId)

                response.attendances.map { it.status } shouldContainExactly
                    listOf(AttendanceViewStatus.UNCHECKED, AttendanceViewStatus.UNCHECKED, AttendanceViewStatus.ABSENT)
            }
        }
        When("취소된 일정이면") {
            Then("422 없이 현황을 반환한다") {
                stubSchedule(schedule(ScheduleStatus.CANCELED))

                val response = attendanceService.getAttendances(meetingId, memberUserId, scheduleId)

                response.attendances.map { it.status }.distinct() shouldContainExactly listOf(AttendanceViewStatus.UNCHECKED)
            }
        }
        When("투표 중인 일정이면") {
            Then("422 없이 현황을 반환한다") {
                stubSchedule(schedule(ScheduleStatus.VOTING))

                val response = attendanceService.getAttendances(meetingId, memberUserId, scheduleId)

                response.attendances shouldHaveSize 3
            }
        }
        When("모임 구성원이 아니면") {
            Then("403 FORBIDDEN 예외가 발생한다") {
                stubSchedule(schedule())

                shouldThrow<ApiException> {
                    attendanceService.getAttendances(meetingId, outsiderUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.FORBIDDEN
            }
        }
        When("모임이 없으면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                `when`(meetingRepository.existsByIdAndEndedAtIsNull(meetingId)).thenReturn(false)

                shouldThrow<ApiException> {
                    attendanceService.getAttendances(meetingId, memberUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
        When("일정이 없거나 다른 모임의 일정이면") {
            Then("404 NOT_FOUND 예외가 발생한다") {
                stubSchedule(null)

                shouldThrow<ApiException> {
                    attendanceService.getAttendances(meetingId, memberUserId, scheduleId)
                }.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }
})

// Spring이 Hibernate 제약 위반 예외를 감싸는 형태 그대로 생성
private fun integrityViolation(constraintName: String) = DataIntegrityViolationException(
    "could not execute statement",
    ConstraintViolationException("could not execute statement", SQLException(), constraintName)
)

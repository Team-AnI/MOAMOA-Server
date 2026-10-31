package com.teamani.moamoa.attendance

import com.teamani.moamoa.attendance.entity.Attendance
import com.teamani.moamoa.attendance.entity.AttendanceStatus
import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import java.time.OffsetDateTime

// 실제 트랜잭션·DB 제약을 거친 출석 처리·조회 흐름과 모임 탈퇴 연동 검증 (Controller·Service 테스트는 mock 사용)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var scheduleRepository: ScheduleRepository

    @MockitoSpyBean
    lateinit var attendanceRepository: AttendanceRepository

    private val startsAt = OffsetDateTime.parse("2026-11-01T19:00:00+09:00")

    private fun saveMeeting(inviteCode: String): Long =
        checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = inviteCode)).id)

    private fun saveMember(meetingId: Long, userId: Long, role: MemberRole): Long =
        checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = userId, role = role)).id)

    private fun saveSchedule(meetingId: Long, status: ScheduleStatus = ScheduleStatus.SCHEDULED): Long =
        checkNotNull(
            scheduleRepository.save(
                Schedule(meetingId = meetingId, title = "11월 정기 모임", startsAt = startsAt, status = status)
            ).id
        )

    // 조회 메서드를 stub한 테스트에서도 DB 값을 그대로 읽도록 findAll로 조회
    private fun attendancesOf(scheduleId: Long): List<Attendance> =
        attendanceRepository.findAll().filter { it.scheduleId == scheduleId }

    init {
        afterTest {
            attendanceRepository.deleteAll()
            scheduleRepository.deleteAll()
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
        }

        Given("ADMIN이 일정의 출석을 처리하고 수정할 때") {
            When("처리 → 조회 → 수정 → 조회 순서로 요청하면") {
                Then("처음 체크한 구성원은 생성, 이미 체크한 구성원은 변경되고 구성원 전체 현황이 memberId 순으로 조회된다") {
                    val meetingId = saveMeeting("ATTD0001")
                    val adminMemberId = saveMember(meetingId, USER_ID, MemberRole.ADMIN)
                    val firstMemberId = saveMember(meetingId, 2L, MemberRole.MEMBER)
                    val secondMemberId = saveMember(meetingId, 3L, MemberRole.MEMBER)
                    val scheduleId = saveSchedule(meetingId)

                    // 6-1 첫 처리
                    mockMvc.patch("/v1/meetings/$meetingId/schedules/$scheduleId/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":$firstMemberId,"status":"PRESENT"}]}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.scheduleId") { value(scheduleId.toInt()) }
                        jsonPath("$.data.attendances[0].memberId") { value(firstMemberId.toInt()) }
                        jsonPath("$.data.attendances[0].status") { value("PRESENT") }
                    }

                    // 6-2 조회: 체크하지 않은 구성원은 UNCHECKED
                    mockMvc.get("/v1/meetings/$meetingId/schedules/$scheduleId/attendances").andExpect {
                        status { isOk() }
                        jsonPath("$.data.attendances.length()") { value(3) }
                        jsonPath("$.data.attendances[0].memberId") { value(adminMemberId.toInt()) }
                        jsonPath("$.data.attendances[0].status") { value("UNCHECKED") }
                        jsonPath("$.data.attendances[1].memberId") { value(firstMemberId.toInt()) }
                        jsonPath("$.data.attendances[1].status") { value("PRESENT") }
                        jsonPath("$.data.attendances[1].nickname") { value(null as Any?) }
                        jsonPath("$.data.attendances[2].memberId") { value(secondMemberId.toInt()) }
                        jsonPath("$.data.attendances[2].status") { value("UNCHECKED") }
                    }
                    val firstCheck = attendancesOf(scheduleId).single()

                    // 6-1 수정: 기존 구성원 변경 + 새 구성원 추가
                    mockMvc.patch("/v1/meetings/$meetingId/schedules/$scheduleId/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":$firstMemberId,"status":"LATE"},""" +
                            """{"memberId":$secondMemberId,"status":"ABSENT"}]}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.attendances[0].status") { value("LATE") }
                        jsonPath("$.data.attendances[1].status") { value("ABSENT") }
                    }

                    mockMvc.get("/v1/meetings/$meetingId/schedules/$scheduleId/attendances").andExpect {
                        status { isOk() }
                        jsonPath("$.data.attendances[0].status") { value("UNCHECKED") }
                        jsonPath("$.data.attendances[1].status") { value("LATE") }
                        jsonPath("$.data.attendances[2].status") { value("ABSENT") }
                    }

                    // 기존 행을 수정해 같은 구성원 기록이 중복되지 않고, 처음 체크한 일시는 유지
                    val attendances = attendancesOf(scheduleId).associateBy { it.memberId }
                    attendances.keys shouldContainExactlyInAnyOrder listOf(firstMemberId, secondMemberId)
                    val changed = attendances.getValue(firstMemberId)
                    changed.id shouldBe firstCheck.id
                    changed.createdAt.toInstant() shouldBe firstCheck.createdAt.toInstant()
                    changed.updatedAt.toInstant() shouldNotBe firstCheck.updatedAt.toInstant()
                    val created = attendances.getValue(secondMemberId)
                    created.updatedAt.toInstant() shouldBe created.createdAt.toInstant()
                }
            }
        }

        Given("목록 중 하나라도 처리할 수 없는 항목이 있을 때") {
            When("다른 모임 구성원의 memberId를 함께 보내면") {
                Then("404 NOT_FOUND를 응답하고 다른 항목도 저장·변경되지 않는다") {
                    val meetingId = saveMeeting("ATTD0002")
                    saveMember(meetingId, USER_ID, MemberRole.ADMIN)
                    val checkedMemberId = saveMember(meetingId, 2L, MemberRole.MEMBER)
                    val newMemberId = saveMember(meetingId, 3L, MemberRole.MEMBER)
                    val otherMeetingMemberId = saveMember(saveMeeting("ATTD0003"), 4L, MemberRole.MEMBER)
                    val scheduleId = saveSchedule(meetingId)
                    attendanceRepository.save(Attendance(checkedMemberId, scheduleId, AttendanceStatus.PRESENT))

                    mockMvc.patch("/v1/meetings/$meetingId/schedules/$scheduleId/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":$checkedMemberId,"status":"LATE"},""" +
                            """{"memberId":$newMemberId,"status":"PRESENT"},""" +
                            """{"memberId":$otherMeetingMemberId,"status":"PRESENT"}]}"""
                    }.andExpect {
                        status { isNotFound() }
                        jsonPath("$.error.code") { value("NOT_FOUND") }
                    }

                    attendancesOf(scheduleId).map { it.memberId to it.status } shouldContainExactly
                        listOf(checkedMemberId to AttendanceStatus.PRESENT)
                }
            }
            When("조회 직후 동시 요청이 같은 구성원의 첫 출석을 먼저 저장하면") {
                Then("unique 제약 위반이 409 CONFLICT로 응답되고 함께 보낸 기존 구성원의 변경도 반영되지 않는다") {
                    val meetingId = saveMeeting("ATTD0004")
                    saveMember(meetingId, USER_ID, MemberRole.ADMIN)
                    val checkedMemberId = saveMember(meetingId, 2L, MemberRole.MEMBER)
                    val racedMemberId = saveMember(meetingId, 3L, MemberRole.MEMBER)
                    val scheduleId = saveSchedule(meetingId)
                    attendanceRepository.save(Attendance(checkedMemberId, scheduleId, AttendanceStatus.PRESENT))
                    // 동시 요청 상황 재현: 기존 기록 조회 직후 다른 요청이 같은 구성원의 첫 출석을 먼저 저장
                    doAnswer { invocation ->
                        val current = attendancesOf(invocation.getArgument(0))
                        jdbcTemplate.update(
                            "insert into attendance (member_id, schedule_id, status, created_at, updated_at) " +
                                "values (?, ?, ?, ?, ?)",
                            racedMemberId, scheduleId, "LATE", OffsetDateTime.now(), OffsetDateTime.now()
                        )
                        current
                    }.`when`(attendanceRepository).findAllByScheduleId(anyLong())

                    mockMvc.patch("/v1/meetings/$meetingId/schedules/$scheduleId/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":$checkedMemberId,"status":"ABSENT"},""" +
                            """{"memberId":$racedMemberId,"status":"PRESENT"}]}"""
                    }.andExpect {
                        status { isConflict() }
                        jsonPath("$.error.code") { value("CONFLICT") }
                    }

                    // 같은 트랜잭션에서 재현한 다른 요청의 저장까지 함께 롤백
                    attendancesOf(scheduleId).map { it.memberId to it.status } shouldContainExactly
                        listOf(checkedMemberId to AttendanceStatus.PRESENT)
                }
            }
        }

        Given("출석 처리를 할 수 없는 요청이 주어졌을 때") {
            When("MEMBER가 출석 처리를 요청하면") {
                Then("403 FORBIDDEN을 응답하고 출석 기록이 바뀌지 않는다") {
                    val meetingId = saveMeeting("ATTD0005")
                    saveMember(meetingId, 99L, MemberRole.ADMIN)
                    saveMember(meetingId, USER_ID, MemberRole.MEMBER)
                    val checkedMemberId = saveMember(meetingId, 2L, MemberRole.MEMBER)
                    val scheduleId = saveSchedule(meetingId)
                    attendanceRepository.save(Attendance(checkedMemberId, scheduleId, AttendanceStatus.PRESENT))

                    mockMvc.patch("/v1/meetings/$meetingId/schedules/$scheduleId/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":$checkedMemberId,"status":"ABSENT"}]}"""
                    }.andExpect {
                        status { isForbidden() }
                        jsonPath("$.error.code") { value("FORBIDDEN") }
                    }

                    attendancesOf(scheduleId).map { it.memberId to it.status } shouldContainExactly
                        listOf(checkedMemberId to AttendanceStatus.PRESENT)

                    // 일반 구성원도 다른 구성원의 출석 현황은 조회 가능
                    mockMvc.get("/v1/meetings/$meetingId/schedules/$scheduleId/attendances").andExpect {
                        status { isOk() }
                        jsonPath("$.data.attendances[2].memberId") { value(checkedMemberId.toInt()) }
                        jsonPath("$.data.attendances[2].status") { value("PRESENT") }
                    }
                }
            }
            When("취소된 일정에 출석 처리를 요청하면") {
                Then("422 UNPROCESSABLE_ENTITY를 응답하고 저장하지 않지만 현황 조회는 가능하다") {
                    val meetingId = saveMeeting("ATTD0006")
                    val adminMemberId = saveMember(meetingId, USER_ID, MemberRole.ADMIN)
                    val scheduleId = saveSchedule(meetingId, ScheduleStatus.CANCELED)

                    mockMvc.patch("/v1/meetings/$meetingId/schedules/$scheduleId/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":$adminMemberId,"status":"PRESENT"}]}"""
                    }.andExpect {
                        status { isEqualTo(422) }
                        jsonPath("$.error.code") { value("UNPROCESSABLE_ENTITY") }
                    }

                    attendancesOf(scheduleId).map { it.memberId } shouldContainExactly emptyList()
                    mockMvc.get("/v1/meetings/$meetingId/schedules/$scheduleId/attendances").andExpect {
                        status { isOk() }
                        jsonPath("$.data.attendances[0].status") { value("UNCHECKED") }
                    }
                }
            }
            When("다른 모임의 일정이면") {
                Then("404 NOT_FOUND를 응답한다") {
                    val meetingId = saveMeeting("ATTD0007")
                    val adminMemberId = saveMember(meetingId, USER_ID, MemberRole.ADMIN)
                    val otherScheduleId = saveSchedule(saveMeeting("ATTD0008"))

                    mockMvc.patch("/v1/meetings/$meetingId/schedules/$otherScheduleId/attendances") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"attendances":[{"memberId":$adminMemberId,"status":"PRESENT"}]}"""
                    }.andExpect {
                        status { isNotFound() }
                        jsonPath("$.error.code") { value("NOT_FOUND") }
                    }
                    mockMvc.get("/v1/meetings/$meetingId/schedules/$otherScheduleId/attendances").andExpect {
                        status { isNotFound() }
                        jsonPath("$.error.code") { value("NOT_FOUND") }
                    }
                }
            }
        }

        Given("출석 기록이 있는 MEMBER가 있을 때") {
            When("그 구성원이 모임을 탈퇴하면") {
                Then("오류 없이 탈퇴되고 그 구성원의 출석 기록만 모든 일정에서 삭제된다") {
                    val meetingId = saveMeeting("ATTD0009")
                    saveMember(meetingId, 99L, MemberRole.ADMIN)
                    val leavingMemberId = saveMember(meetingId, USER_ID, MemberRole.MEMBER)
                    val stayingMemberId = saveMember(meetingId, 2L, MemberRole.MEMBER)
                    val firstScheduleId = saveSchedule(meetingId)
                    val secondScheduleId = saveSchedule(meetingId, ScheduleStatus.CONFIRMED)
                    attendanceRepository.saveAll(
                        listOf(
                            Attendance(leavingMemberId, firstScheduleId, AttendanceStatus.PRESENT),
                            Attendance(leavingMemberId, secondScheduleId, AttendanceStatus.LATE),
                            Attendance(stayingMemberId, firstScheduleId, AttendanceStatus.ABSENT),
                        )
                    )

                    mockMvc.delete("/v1/meetings/$meetingId/members/me").andExpect {
                        status { isOk() }
                        jsonPath("$.data.meetingId") { value(meetingId.toInt()) }
                    }

                    attendanceRepository.findAll().map { it.memberId to it.scheduleId } shouldContainExactly
                        listOf(stayingMemberId to firstScheduleId)
                    memberRepository.countByMeetingId(meetingId) shouldBe 2L
                }
            }
        }
    }

    companion object {
        // Controller 임시 로그인 사용자 (currentUserId = 1L)
        private const val USER_ID = 1L
    }
}

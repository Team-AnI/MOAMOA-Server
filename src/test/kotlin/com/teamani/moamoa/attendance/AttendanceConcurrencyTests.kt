package com.teamani.moamoa.attendance

import com.teamani.moamoa.attendance.dto.AttendanceUpdateItemRequest
import com.teamani.moamoa.attendance.dto.AttendanceUpdateRequest
import com.teamani.moamoa.attendance.entity.AttendanceStatus
import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.attendance.service.AttendanceService
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.schedule.service.ScheduleService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// 실제 DB에서 출석 처리가 일정 삭제·모임 나가기·다른 출석 처리와 동시에 들어와도 잠금으로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class AttendanceConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var attendanceService: AttendanceService

    @Autowired
    lateinit var scheduleService: ScheduleService

    @Autowired
    lateinit var memberService: MemberService

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var scheduleRepository: ScheduleRepository

    // 요청을 원하는 시점에 멈추거나 서로 만나게 해 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean
    lateinit var attendanceRepository: AttendanceRepository

    @MockitoSpyBean
    lateinit var memberRepository: MemberRepository

    init {
        afterTest {
            reset(attendanceRepository, memberRepository)
            attendanceRepository.deleteAll()
            scheduleRepository.deleteAll()
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
        }

        Given("출석을 처리할 수 있는 일정이 있을 때") {
            When("출석 처리가 검증을 통과한 직후 일정 삭제가 들어오면") {
                Then("삭제는 출석 처리가 끝날 때까지 기다린 뒤 처리되고, 저장한 출석 기록까지 함께 삭제되어 남지 않는다") {
                    val saved = savedSchedule("RACE0201")
                    // 출석 처리가 일정을 잠그고 기존 출석 기록을 읽기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(attendanceRepository, pause::hold)).`when`(attendanceRepository).findAllByScheduleId(anyLong())
                    val request = AttendanceUpdateRequest(listOf(AttendanceUpdateItemRequest(saved.memberId, "PRESENT")))
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val update = executor.start {
                            attendanceService.updateAttendances(saved.meetingId, ADMIN_USER_ID, saved.scheduleId, request)
                        }
                        pause.awaitPaused()
                        val delete = executor.start {
                            scheduleService.deleteSchedule(saved.meetingId, ADMIN_USER_ID, saved.scheduleId)
                        }

                        shouldThrow<TimeoutException> { delete.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        update.get(10, TimeUnit.SECONDS).shouldBeNull()
                        delete.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(attendanceRepository)
                    scheduleRepository.findById(saved.scheduleId).isPresent shouldBe false
                    attendanceRepository.findAll().filter { it.scheduleId == saved.scheduleId } shouldBe emptyList()
                }
            }

            When("출석 처리가 대상 구성원과 일정을 잠근 직후 그 구성원의 모임 나가기가 들어오면") {
                Then("나가기는 출석 처리가 끝날 때까지 기다린 뒤 처리되고, 나간 구성원의 출석 기록이 남지 않는다") {
                    val saved = savedSchedule("RACE0202")
                    // 출석 처리가 구성원·일정을 잠그고 기존 출석 기록을 읽기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(attendanceRepository, pause::hold)).`when`(attendanceRepository).findAllByScheduleId(anyLong())
                    val request = AttendanceUpdateRequest(listOf(AttendanceUpdateItemRequest(saved.memberId, "PRESENT")))
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val update = executor.start {
                            attendanceService.updateAttendances(saved.meetingId, ADMIN_USER_ID, saved.scheduleId, request)
                        }
                        pause.awaitPaused()
                        val leave = executor.start {
                            memberService.leaveMeeting(saved.meetingId, MEMBER_USER_ID)
                        }

                        shouldThrow<TimeoutException> { leave.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        update.get(10, TimeUnit.SECONDS).shouldBeNull()
                        leave.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(attendanceRepository)
                    memberRepository.findById(saved.memberId).isPresent shouldBe false
                    attendanceRepository.findAll().filter { it.memberId == saved.memberId } shouldBe emptyList()
                }
            }

            When("모임 나가기가 구성원을 잠근 직후 그 구성원의 출석 처리가 들어오면") {
                Then("출석 처리는 나가기가 끝날 때까지 기다린 뒤 404이고, 나간 구성원의 출석 기록은 생기지 않는다") {
                    val saved = savedSchedule("RACE0203")
                    // 나가기가 구성원을 잠그고 출석 기록을 지우기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(attendanceRepository, pause::hold)).`when`(attendanceRepository).deleteAllByMemberId(anyLong())
                    val request = AttendanceUpdateRequest(listOf(AttendanceUpdateItemRequest(saved.memberId, "PRESENT")))
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val leave = executor.start {
                            memberService.leaveMeeting(saved.meetingId, MEMBER_USER_ID)
                        }
                        pause.awaitPaused()
                        val update = executor.start {
                            attendanceService.updateAttendances(saved.meetingId, ADMIN_USER_ID, saved.scheduleId, request)
                        }

                        shouldThrow<TimeoutException> { update.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        leave.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (update.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.NOT_FOUND
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(attendanceRepository)
                    memberRepository.findById(saved.memberId).isPresent shouldBe false
                    attendanceRepository.findAll().filter { it.memberId == saved.memberId } shouldBe emptyList()
                }
            }
        }

        Given("구성원 A·B가 있는 모임에 출석을 처리할 수 있는 일정 2개가 있을 때") {
            When("두 일정의 출석 처리가 A·B와 B·A처럼 반대 순서로 동시에 구성원 잠금을 시작하면") {
                Then("구성원을 id 순서로 잠가 교착 없이 순서대로 처리되고, 두 요청의 출석 기록이 모두 저장된다") {
                    val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = "RACE0204")).id)
                    memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN))
                    val memberA = checkNotNull(
                        memberRepository.save(Member(meetingId = meetingId, userId = MEMBER_USER_ID, role = MemberRole.MEMBER)).id
                    )
                    val memberB = checkNotNull(
                        memberRepository.save(Member(meetingId = meetingId, userId = OTHER_MEMBER_USER_ID, role = MemberRole.MEMBER)).id
                    )
                    val firstScheduleId = savedScheduleOf(meetingId)
                    val secondScheduleId = savedScheduleOf(meetingId)
                    // 두 요청을 구성원 잠금 직전에 만나게 함 (요청 순서대로 한 명씩 잠그면 여기서 교착)
                    val meet = meetingPoint(2)
                    doAnswer(beforeReal(memberRepository, meet)).`when`(memberRepository)
                        .findAllByMeetingIdAndIdInForUpdate(anyLong(), anyCollection())

                    val results = runConcurrently(
                        listOf(
                            {
                                attendanceService.updateAttendances(
                                    meetingId, ADMIN_USER_ID, firstScheduleId,
                                    updateRequest(memberA to "PRESENT", memberB to "LATE")
                                )
                            },
                            {
                                attendanceService.updateAttendances(
                                    meetingId, ADMIN_USER_ID, secondScheduleId,
                                    updateRequest(memberB to "ABSENT", memberA to "PRESENT")
                                )
                            },
                        )
                    )

                    results shouldContainExactly listOf(null, null)
                    reset(attendanceRepository, memberRepository)
                    statusesOf(firstScheduleId) shouldBe
                        mapOf(memberA to AttendanceStatus.PRESENT, memberB to AttendanceStatus.LATE)
                    statusesOf(secondScheduleId) shouldBe
                        mapOf(memberA to AttendanceStatus.PRESENT, memberB to AttendanceStatus.ABSENT)
                }
            }
        }
    }

    private data class SavedSchedule(val meetingId: Long, val memberId: Long, val scheduleId: Long)

    // 관리자와 출석 대상 구성원이 있는 모임의 출석 처리 가능한 일정 저장
    private fun savedSchedule(inviteCode: String): SavedSchedule {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = inviteCode)).id)
        memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN))
        val memberId = checkNotNull(
            memberRepository.save(Member(meetingId = meetingId, userId = MEMBER_USER_ID, role = MemberRole.MEMBER)).id
        )
        return SavedSchedule(meetingId, memberId, savedScheduleOf(meetingId))
    }

    // 모임에 출석 처리 가능한 일정 저장
    private fun savedScheduleOf(meetingId: Long): Long =
        checkNotNull(
            scheduleRepository.save(
                Schedule(meetingId = meetingId, title = "11월 정기 모임", startsAt = OffsetDateTime.parse("2099-11-01T19:00:00+09:00"))
            ).id
        )

    private fun updateRequest(vararg items: Pair<Long, String>) =
        AttendanceUpdateRequest(items.map { (memberId, status) -> AttendanceUpdateItemRequest(memberId, status) })

    private fun statusesOf(scheduleId: Long): Map<Long, AttendanceStatus> =
        attendanceRepository.findAll().filter { it.scheduleId == scheduleId }.associate { it.memberId to it.status }

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun beforeReal(spy: Any, action: () -> Unit) = Answer { invocation ->
        action()
        mockingDetails(spy).mockCreationSettings.defaultAnswer.answer(invocation)
    }

    // 정해진 수의 요청이 모두 도착하면 함께 진행, 잠금 대기로 다른 요청이 오지 못하면 대기 시간 후 진행
    private fun meetingPoint(parties: Int): () -> Unit {
        val arrived = CountDownLatch(parties)
        return {
            arrived.countDown()
            arrived.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기 (일정 잠금을 쥔 채 멈추므로 H2 잠금 대기 2초 안에 해제)
    private class Pause {
        private val paused = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        fun hold() {
            if (first.getAndSet(false)) {
                paused.countDown()
                released.await(5, TimeUnit.SECONDS)
            }
        }

        fun awaitPaused() {
            paused.await(5, TimeUnit.SECONDS) shouldBe true
        }

        fun release() = released.countDown()
    }

    // 작업을 비동기로 실행하고 작업에서 발생한 예외(성공이면 null) 반환
    private fun ExecutorService.start(task: () -> Any?): Future<Throwable?> =
        submit(Callable { runCatching { task() }.exceptionOrNull() })

    // 모든 작업을 같은 시점에 시작하고, 작업별 예외(성공이면 null)를 순서대로 반환
    private fun runConcurrently(tasks: List<() -> Any?>): List<Throwable?> {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tasks.size)

        try {
            val futures = tasks.map { task ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        start.await()
                        runCatching { task() }.exceptionOrNull()
                    }
                )
            }
            ready.await()
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val ADMIN_USER_ID = 99L
        private const val MEMBER_USER_ID = 1L
        private const val OTHER_MEMBER_USER_ID = 2L

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

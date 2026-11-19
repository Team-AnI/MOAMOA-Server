package com.teamani.moamoa.attendance

import com.teamani.moamoa.attendance.dto.AttendanceUpdateItemRequest
import com.teamani.moamoa.attendance.dto.AttendanceUpdateRequest
import com.teamani.moamoa.attendance.repository.AttendanceRepository
import com.teamani.moamoa.attendance.service.AttendanceService
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.schedule.service.ScheduleService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

// 실제 트랜잭션·DB에서 같은 일정의 출석 처리와 일정 삭제가 동시에 들어올 때 일정 단위로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class AttendanceConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var attendanceService: AttendanceService

    @Autowired
    lateinit var scheduleService: ScheduleService

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var scheduleRepository: ScheduleRepository

    // 요청을 원하는 시점에 멈춰 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean
    lateinit var attendanceRepository: AttendanceRepository

    init {
        afterTest {
            reset(attendanceRepository)
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
                    doAnswer(beforeReal(pause::hold)).`when`(attendanceRepository).findAllByScheduleId(anyLong())
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
        val scheduleId = checkNotNull(
            scheduleRepository.save(
                Schedule(meetingId = meetingId, title = "11월 정기 모임", startsAt = OffsetDateTime.parse("2099-11-01T19:00:00+09:00"))
            ).id
        )
        return SavedSchedule(meetingId, memberId, scheduleId)
    }

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun beforeReal(action: () -> Unit) = Answer { invocation ->
        action()
        mockingDetails(attendanceRepository).mockCreationSettings.defaultAnswer.answer(invocation)
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

    companion object {
        private const val ADMIN_USER_ID = 99L
        private const val MEMBER_USER_ID = 1L

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

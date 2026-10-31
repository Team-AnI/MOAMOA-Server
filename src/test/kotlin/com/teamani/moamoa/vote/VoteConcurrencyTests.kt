package com.teamani.moamoa.vote

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.vote.dto.MyVoteSubmitRequest
import com.teamani.moamoa.vote.dto.VoteConfirmRequest
import com.teamani.moamoa.vote.entity.Vote
import com.teamani.moamoa.vote.entity.VoteStatus
import com.teamani.moamoa.vote.repository.VoteRepository
import com.teamani.moamoa.vote.service.VoteService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
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

// 실제 트랜잭션·DB에서 같은 일정의 투표 제출·확정이 동시에 들어올 때 일정 단위로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class VoteConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var voteService: VoteService

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var scheduleRepository: ScheduleRepository

    // 요청을 원하는 시점에 멈추거나 서로 만나게 해 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean
    lateinit var voteRepository: VoteRepository

    private val firstTime = OffsetDateTime.parse("2026-11-01T19:00:00+09:00")
    private val secondTime = OffsetDateTime.parse("2026-11-02T19:00:00+09:00")

    init {
        afterTest {
            reset(voteRepository)
            voteRepository.deleteAll()
            scheduleRepository.deleteAll()
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
        }

        Given("후보가 2개인 투표가 진행 중일 때") {
            When("같은 구성원의 제출 2건이 동시에 들어오면") {
                Then("일정 잠금으로 순서대로 처리되어 둘 다 성공하고 응답은 후보 수만큼만 남는다") {
                    val vote = savedVote("RACE0101")
                    // 잠금이 없으면 두 요청이 모두 기존 응답 삭제 단계에서 만나 같은 후보 행을 함께 저장
                    val meet = meetingPoint(2)
                    doAnswer(beforeReal(meet)).`when`(voteRepository).deleteAllByScheduleIdAndMemberId(anyLong(), anyLong())
                    val request = MyVoteSubmitRequest(listOf(firstTime))

                    val results = runConcurrently(
                        List(2) { { voteService.submitMyVote(vote.meetingId, MEMBER_USER_ID, vote.scheduleId, request) } }
                    )

                    results shouldContainExactly listOf(null, null)
                    respondedRows(vote).size shouldBe 2
                }
            }

            When("투표 제출이 상태 검사를 통과한 직후 일정 확정이 들어오면") {
                Then("확정은 제출이 끝날 때까지 기다린 뒤 처리되고, 제출 응답은 확정 전에 저장된다") {
                    val vote = savedVote("RACE0102")
                    // 제출이 상태 검사를 마치고 기존 응답을 지우기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(pause::hold)).`when`(voteRepository).deleteAllByScheduleIdAndMemberId(anyLong(), anyLong())
                    val submitRequest = MyVoteSubmitRequest(listOf(firstTime))
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val submit = executor.start {
                            voteService.submitMyVote(vote.meetingId, MEMBER_USER_ID, vote.scheduleId, submitRequest)
                        }
                        pause.awaitPaused()
                        val confirm = executor.start {
                            voteService.confirmVote(vote.meetingId, ADMIN_USER_ID, vote.scheduleId, VoteConfirmRequest(secondTime))
                        }

                        shouldThrow<TimeoutException> { confirm.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        submit.get(10, TimeUnit.SECONDS).shouldBeNull()
                        confirm.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    scheduleOf(vote).status shouldBe ScheduleStatus.CONFIRMED
                    respondedRows(vote).size shouldBe 2
                }
            }

            When("일정 확정이 상태 검사를 통과한 직후 투표 제출이 들어오면") {
                Then("제출은 확정이 끝날 때까지 기다린 뒤 422로 거절되고 응답은 저장되지 않는다") {
                    val vote = savedVote("RACE0103")
                    // 확정이 일정을 잠그고 후보 목록을 읽기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(pause::hold)).`when`(voteRepository).findAllByScheduleId(anyLong())
                    val submitRequest = MyVoteSubmitRequest(listOf(firstTime))
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val confirm = executor.start {
                            voteService.confirmVote(vote.meetingId, ADMIN_USER_ID, vote.scheduleId, VoteConfirmRequest(secondTime))
                        }
                        pause.awaitPaused()
                        val submit = executor.start {
                            voteService.submitMyVote(vote.meetingId, MEMBER_USER_ID, vote.scheduleId, submitRequest)
                        }

                        shouldThrow<TimeoutException> { submit.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        confirm.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (submit.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(voteRepository)
                    scheduleOf(vote).status shouldBe ScheduleStatus.CONFIRMED
                    respondedRows(vote).size shouldBe 0
                }
            }

            When("서로 다른 후보 시간으로 일정 확정 2건이 동시에 들어오면") {
                Then("한 건만 성공하고 나머지는 422이며 일정 시작 일시는 성공한 요청의 후보 시간이다") {
                    val vote = savedVote("RACE0104")
                    // 잠금이 없으면 두 요청이 모두 VOTING 상태를 읽은 뒤 후보 목록 조회에서 만남
                    val meet = meetingPoint(2)
                    doAnswer(beforeReal(meet)).`when`(voteRepository).findAllByScheduleId(anyLong())
                    val times = listOf(firstTime, secondTime)

                    val results = runConcurrently(
                        times.map { time ->
                            { voteService.confirmVote(vote.meetingId, ADMIN_USER_ID, vote.scheduleId, VoteConfirmRequest(time)) }
                        }
                    )

                    results.count { it == null } shouldBe 1
                    (results.filterNotNull().single() as ApiException).errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                    scheduleOf(vote).startsAt.toInstant() shouldBe times[results.indexOf(null)].toInstant()
                }
            }
        }
    }

    private data class SavedVote(val meetingId: Long, val memberId: Long, val scheduleId: Long)

    // 관리자 PENDING 행 2개로 후보를 만든 투표 중 일정과 아직 응답하지 않은 구성원 저장
    private fun savedVote(inviteCode: String): SavedVote {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = inviteCode)).id)
        val adminMemberId = checkNotNull(
            memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN)).id
        )
        val memberId = checkNotNull(
            memberRepository.save(Member(meetingId = meetingId, userId = MEMBER_USER_ID, role = MemberRole.MEMBER)).id
        )
        val scheduleId = checkNotNull(
            scheduleRepository.save(
                Schedule(meetingId = meetingId, title = "11월 정기 모임", startsAt = firstTime, status = ScheduleStatus.VOTING)
            ).id
        )
        val deadline = OffsetDateTime.now().plusDays(1)
        voteRepository.saveAll(
            listOf(firstTime, secondTime).map { Vote(adminMemberId, scheduleId, it, VoteStatus.PENDING, endedAt = deadline) }
        )
        return SavedVote(meetingId, memberId, scheduleId)
    }

    private fun scheduleOf(vote: SavedVote): Schedule = scheduleRepository.findById(vote.scheduleId).orElseThrow()

    private fun respondedRows(vote: SavedVote): List<Vote> =
        voteRepository.findAllByScheduleId(vote.scheduleId).filter { it.memberId == vote.memberId }

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun beforeReal(action: () -> Unit) = Answer { invocation ->
        action()
        mockingDetails(voteRepository).mockCreationSettings.defaultAnswer.answer(invocation)
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

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}

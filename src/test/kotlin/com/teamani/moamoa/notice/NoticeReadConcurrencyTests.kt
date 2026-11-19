package com.teamani.moamoa.notice

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeReadRepository
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.notice.service.NoticeService
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// 실제 트랜잭션·DB에서 공지 읽음 처리와 공지 삭제·모임 나가기가 동시에 들어올 때 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class NoticeReadConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var noticeService: NoticeService

    @Autowired
    lateinit var memberService: MemberService

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var noticeRepository: NoticeRepository

    // 요청을 원하는 시점에 멈춰 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean
    lateinit var noticeReadRepository: NoticeReadRepository

    init {
        afterTest {
            reset(noticeReadRepository)
            noticeReadRepository.deleteAll()
            noticeRepository.deleteAll()
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
        }

        Given("구성원이 아직 읽지 않은 공지가 있을 때") {
            When("읽음 처리가 공지를 잠근 직후 공지 삭제가 들어오면") {
                Then("삭제는 읽음 처리가 끝날 때까지 기다린 뒤 처리되고, 저장한 읽음 기록까지 함께 삭제되어 남지 않는다") {
                    val saved = savedNotice("RACE0301")
                    // 읽음 처리가 공지를 잠그고 기존 읽음 기록을 찾기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(pause::hold)).`when`(noticeReadRepository).findByNoticeIdAndMemberId(anyLong(), anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val read = executor.start {
                            noticeService.readNotice(saved.meetingId, saved.noticeId, MEMBER_USER_ID)
                        }
                        pause.awaitPaused()
                        val delete = executor.start {
                            noticeService.deleteNotice(saved.meetingId, saved.noticeId, ADMIN_USER_ID)
                        }

                        shouldThrow<TimeoutException> { delete.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        read.get(10, TimeUnit.SECONDS).shouldBeNull()
                        delete.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(noticeReadRepository)
                    noticeRepository.findById(saved.noticeId).isPresent shouldBe false
                    noticeReadRepository.findAllByNoticeId(saved.noticeId) shouldBe emptyList()
                }
            }

            When("읽음 처리가 구성원과 공지를 잠근 직후 그 구성원의 모임 나가기가 들어오면") {
                Then("나가기는 읽음 처리가 끝날 때까지 기다린 뒤 처리되고, 나간 구성원의 읽음 기록이 남지 않는다") {
                    val saved = savedNotice("RACE0302")
                    val memberId = checkNotNull(memberRepository.findByMeetingIdAndUserId(saved.meetingId, MEMBER_USER_ID)?.id)
                    // 읽음 처리가 구성원·공지를 잠그고 기존 읽음 기록을 찾기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(pause::hold)).`when`(noticeReadRepository).findByNoticeIdAndMemberId(anyLong(), anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val read = executor.start {
                            noticeService.readNotice(saved.meetingId, saved.noticeId, MEMBER_USER_ID)
                        }
                        pause.awaitPaused()
                        val leave = executor.start {
                            memberService.leaveMeeting(saved.meetingId, MEMBER_USER_ID)
                        }

                        shouldThrow<TimeoutException> { leave.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        read.get(10, TimeUnit.SECONDS).shouldBeNull()
                        leave.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(noticeReadRepository)
                    memberRepository.findById(memberId).isPresent shouldBe false
                    noticeReadRepository.findAll().filter { it.memberId == memberId } shouldBe emptyList()
                }
            }

            When("모임 나가기가 구성원을 잠근 직후 그 구성원의 읽음 처리가 들어오면") {
                Then("읽음 처리는 나가기가 끝날 때까지 기다린 뒤 403이고, 나간 구성원의 읽음 기록은 생기지 않는다") {
                    val saved = savedNotice("RACE0303")
                    val memberId = checkNotNull(memberRepository.findByMeetingIdAndUserId(saved.meetingId, MEMBER_USER_ID)?.id)
                    // 나가기가 구성원을 잠그고 읽음 기록을 지우기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(pause::hold)).`when`(noticeReadRepository).deleteAllByMemberId(anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val leave = executor.start {
                            memberService.leaveMeeting(saved.meetingId, MEMBER_USER_ID)
                        }
                        pause.awaitPaused()
                        val read = executor.start {
                            noticeService.readNotice(saved.meetingId, saved.noticeId, MEMBER_USER_ID)
                        }

                        shouldThrow<TimeoutException> { read.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        leave.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (read.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.FORBIDDEN
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    reset(noticeReadRepository)
                    memberRepository.findById(memberId).isPresent shouldBe false
                    noticeReadRepository.findAll().filter { it.memberId == memberId } shouldBe emptyList()
                }
            }
        }
    }

    private data class SavedNotice(val meetingId: Long, val noticeId: Long)

    // 관리자와 구성원이 있는 모임에 아직 아무도 읽지 않은 공지 저장
    private fun savedNotice(inviteCode: String): SavedNotice {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = inviteCode)).id)
        val adminMemberId = checkNotNull(
            memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN)).id
        )
        memberRepository.save(Member(meetingId = meetingId, userId = MEMBER_USER_ID, role = MemberRole.MEMBER))
        val noticeId = checkNotNull(
            noticeRepository.save(Notice(meetingId = meetingId, membersId = adminMemberId, title = "공지", content = "내용")).id
        )
        return SavedNotice(meetingId, noticeId)
    }

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun beforeReal(action: () -> Unit) = Answer { invocation ->
        action()
        mockingDetails(noticeReadRepository).mockCreationSettings.defaultAnswer.answer(invocation)
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기 (공지 잠금을 쥔 채 멈추므로 H2 잠금 대기 2초 안에 해제)
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

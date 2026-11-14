package com.teamani.moamoa.notice

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.repository.NoticeReadRepository
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.entity.ProfileColor
import com.teamani.moamoa.user.repository.AppUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실제 트랜잭션·DB에서 공지 읽음 기록의 저장, 집계, 삭제와 동시 읽음 처리를 검증
@SpringBootTest
@ActiveProfiles("test")
class NoticeReadIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var noticeService: NoticeService

    @Autowired
    lateinit var memberService: MemberService

    @Autowired
    lateinit var noticeRepository: NoticeRepository

    @Autowired
    lateinit var noticeReadRepository: NoticeReadRepository

    @Autowired
    lateinit var meetingRepository: MeetingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    @Autowired
    lateinit var settlementRepository: SettlementRepository

    private val userIds = mutableListOf<Long>()
    private val noticeIds = mutableListOf<Long>()
    private val settlementIds = mutableListOf<Long>()
    private var sequence = 0

    init {
        // 다른 테스트가 구성원·모임 수를 확인하므로 이 테스트에서 만든 데이터를 모두 정리
        afterSpec {
            noticeReadRepository.deleteAll()
            settlementRepository.deleteAllById(settlementIds)
            noticeRepository.deleteAllById(noticeIds.filter { noticeRepository.existsById(it) })
            memberRepository.deleteAll()
            meetingRepository.deleteAll()
            appUserRepository.deleteAllById(userIds)
        }

        Given("관리자 1명과 구성원 2명이 있는 모임에서 관리자가 공지를 작성했을 때") {
            val group = savedGroup()
            val noticeId = createdNotice(group)

            When("작성 직후 읽음 현황을 조회하면") {
                val response = noticeService.getNoticeReads(group.meetingId, noticeId, group.memberUserId)

                Then("작성자만 읽음이고 나머지는 가입 순으로 안 읽음이며, 이름과 프로필 색은 사용자 정보로 채워진다") {
                    response.readCount shouldBe 1
                    response.totalCount shouldBe 3
                    response.readMembers.map { it.memberId } shouldContainExactly listOf(group.adminMemberId)
                    response.readMembers[0].userName shouldBe "관리자"
                    response.readMembers[0].profileColor shouldBe ProfileColor.LIGHT_BLUE
                    response.unreadMembers.map { it.memberId } shouldContainExactly listOf(group.memberId, group.otherMemberId)
                    response.unreadMembers.map { it.userName } shouldContainExactly listOf("구성원", "다른구성원")
                    response.unreadMembers.map { it.profileColor } shouldContainExactly listOf(ProfileColor.BLUE, ProfileColor.GRAY)
                }
            }

            When("구성원이 같은 공지를 두 번 읽으면") {
                val first = noticeService.readNotice(group.meetingId, noticeId, group.memberUserId)
                val second = noticeService.readNotice(group.meetingId, noticeId, group.memberUserId)

                Then("기록은 하나만 남고 두 번째 응답도 처음 읽은 시각이다") {
                    val stored = checkNotNull(noticeReadRepository.findByNoticeIdAndMemberId(noticeId, group.memberId))
                    second.readAt.isEqual(stored.readAt) shouldBe true
                    (Duration.between(first.readAt, second.readAt).abs() < Duration.ofMillis(1)) shouldBe true
                    noticeReadRepository.findAllByNoticeId(noticeId).size shouldBe 2
                }
            }

            When("공지 목록을 조회하면") {
                val response = noticeService.getNotices(group.meetingId, 0, 20, group.memberUserId)

                Then("읽은 현재 구성원 수와 전체 구성원 수가 함께 반환된다") {
                    response.notices.single().readCount shouldBe 2
                    response.notices.single().totalCount shouldBe 3
                }
            }

            When("읽은 구성원이 모임을 나가고 새 구성원이 가입하면") {
                memberService.leaveMeeting(group.meetingId, group.memberUserId)
                val newUserId = savedUser("새구성원", ProfileColor.DARK)
                val newMemberId = checkNotNull(
                    memberRepository.save(Member(meetingId = group.meetingId, userId = newUserId, role = MemberRole.MEMBER)).id
                )
                val response = noticeService.getNoticeReads(group.meetingId, noticeId, group.adminUserId)

                Then("나간 구성원의 읽음 기록은 삭제되고, 새 구성원은 안 읽음에 포함된다") {
                    noticeReadRepository.findByNoticeIdAndMemberId(noticeId, group.memberId).shouldBeNull()
                    response.readCount shouldBe 1
                    response.totalCount shouldBe 3
                    response.unreadMembers.map { it.memberId } shouldContainExactly listOf(group.otherMemberId, newMemberId)
                }
            }
        }

        Given("구성원이 아직 읽지 않은 공지가 있을 때") {
            val group = savedGroup()
            val noticeId = createdNotice(group)

            When("같은 구성원이 읽음 처리를 동시에 여러 번 요청하면") {
                val results = runConcurrently(
                    List(4) { { noticeService.readNotice(group.meetingId, noticeId, group.memberUserId) } }
                )

                Then("모든 요청이 성공하고 읽음 기록은 하나만 남는다") {
                    results.filterNotNull() shouldBe emptyList()
                    noticeReadRepository.findAllByNoticeId(noticeId).count { it.memberId == group.memberId } shouldBe 1
                }
            }
        }

        Given("다른 모임의 공지나 모임 밖 사용자의 요청이 주어졌을 때") {
            val group = savedGroup()
            val otherGroup = savedGroup()
            val otherNoticeId = createdNotice(otherGroup)
            val outsiderUserId = savedUser("외부인", ProfileColor.LIGHT_BLUE)

            When("다른 모임의 공지를 읽음 처리하거나 읽음 현황을 조회하면") {
                val readError = shouldThrow<ApiException> {
                    noticeService.readNotice(group.meetingId, otherNoticeId, group.memberUserId)
                }
                val statusError = shouldThrow<ApiException> {
                    noticeService.getNoticeReads(group.meetingId, otherNoticeId, group.memberUserId)
                }

                Then("404 NOT_FOUND이고 읽음 기록은 생기지 않는다") {
                    readError.errorCode shouldBe ErrorCode.NOT_FOUND
                    statusError.errorCode shouldBe ErrorCode.NOT_FOUND
                    noticeReadRepository.findAllByNoticeId(otherNoticeId).size shouldBe 1
                }
            }

            When("모임 구성원이 아닌 사용자가 읽음 처리하면") {
                val error = shouldThrow<ApiException> {
                    noticeService.readNotice(otherGroup.meetingId, otherNoticeId, outsiderUserId)
                }

                Then("403 FORBIDDEN이다") {
                    error.errorCode shouldBe ErrorCode.FORBIDDEN
                }
            }
        }

        Given("정산이 연결된 안내 공지와 읽음 기록이 있을 때") {
            val group = savedGroup()
            val noticeId = createdNotice(group)
            noticeService.readNotice(group.meetingId, noticeId, group.memberUserId)
            val settlementId = checkNotNull(
                settlementRepository.save(
                    Settlement(
                        meetingId = group.meetingId,
                        noticeId = noticeId,
                        title = "정산",
                        totalAmount = 30000,
                        bankName = "국민은행",
                        accountNumber = "123456789012",
                        accountHolder = "김모아",
                        dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00")
                    )
                ).id
            ).also { settlementIds += it }

            When("공지 상세를 조회하면") {
                val response = noticeService.getNoticeDetail(group.meetingId, noticeId, group.memberUserId)

                Then("연결된 정산 ID가 반환된다") {
                    response.settlementId shouldBe settlementId
                }
            }

            When("공지를 삭제하면") {
                noticeService.deleteNotice(group.meetingId, noticeId, group.adminUserId)

                Then("해당 공지의 읽음 기록도 함께 삭제된다") {
                    noticeReadRepository.findAllByNoticeId(noticeId) shouldBe emptyList()
                }
            }
        }
    }

    // 관리자·구성원·다른 구성원과 각 사용자 정보를 저장
    private fun savedGroup(): Group {
        sequence++
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모임$sequence", inviteCode = "READ%04d".format(sequence))).id)
        val adminUserId = savedUser("관리자", ProfileColor.LIGHT_BLUE)
        val memberUserId = savedUser("구성원", ProfileColor.BLUE)
        val otherUserId = savedUser("다른구성원", ProfileColor.GRAY)
        return Group(
            meetingId = meetingId,
            adminUserId = adminUserId,
            memberUserId = memberUserId,
            adminMemberId = savedMember(meetingId, adminUserId, MemberRole.ADMIN),
            memberId = savedMember(meetingId, memberUserId, MemberRole.MEMBER),
            otherMemberId = savedMember(meetingId, otherUserId, MemberRole.MEMBER)
        )
    }

    // 정산 안내 공지 경로처럼 구성원 ID를 넘겨 작성 (3-1 경로는 #24 전까지 작성자 칸에 사용자 ID가 들어가 작성자 읽음이 집계되지 않음, #24에서 검증)
    private fun createdNotice(group: Group): Long =
        noticeService.createNotice(group.meetingId, group.adminMemberId, NoticeCreateRequest(title = "공지", content = "내용")).noticeId
            .also { noticeIds += it }

    private fun savedUser(name: String, profileColor: ProfileColor): Long {
        sequence++
        val user = AppUser(
            name = name,
            email = "notice-read-$sequence@moamoa.test",
            password = "password",
            profileColor = profileColor,
            termsAgreedAt = OffsetDateTime.now()
        )
        return checkNotNull(appUserRepository.save(user).id).also { userIds += it }
    }

    private fun savedMember(meetingId: Long, userId: Long, role: MemberRole): Long =
        checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = userId, role = role)).id)

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

    private data class Group(
        val meetingId: Long,
        val adminUserId: Long,
        val memberUserId: Long,
        val adminMemberId: Long,
        val memberId: Long,
        val otherMemberId: Long
    )
}

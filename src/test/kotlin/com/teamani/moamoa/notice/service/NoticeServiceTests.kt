package com.teamani.moamoa.notice.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeUpdateRequest
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.OffsetDateTime
import java.util.Optional

class NoticeServiceTests : BehaviorSpec({

    val noticeRepository: NoticeRepository = mock(NoticeRepository::class.java)
    val settlementRepository: SettlementRepository = mock(SettlementRepository::class.java)
    val noticeService = NoticeService(noticeRepository, settlementRepository)

    afterEach { clearInvocations(noticeRepository, settlementRepository) }

    Given("모임 공지사항 작성 요청 데이터가 주어졌을 때") {
        val meetingId = 1L
        val userId = 1L
        val request = NoticeCreateRequest(
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다."
        )

        val savedEntity = Notice(
            id = 30L,
            meetingId = meetingId,
            membersId = userId,
            title = requireNotNull(request.title),
            content = requireNotNull(request.content)
        )

        `when`(noticeRepository.save(any(Notice::class.java))).thenReturn(savedEntity)

        When("createNotice 메서드를 호출하면") {
            val response = noticeService.createNotice(meetingId, userId, request)

            Then("Notice 엔티티가 영속화되고 올바른 응답 DTO가 반환된다") {
                response.shouldNotBeNull()
                response.noticeId shouldBe 30L
                response.title shouldBe "10월 회비 안내"
                verify(noticeRepository).save(any(Notice::class.java))
            }
        }
    }

    Given("삭제할 공지사항 식별자가 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val userId = 1L
        val targetNotice = Notice(
            id = noticeId,
            meetingId = meetingId,
            membersId = userId,
            title = "삭제할 공지",
            content = "삭제할 공지 내용"
        )

        `when`(noticeRepository.findById(noticeId)).thenReturn(Optional.of(targetNotice))

        When("deleteNotice 메서드를 호출하면") {
            val response = noticeService.deleteNotice(meetingId, noticeId, userId)

            Then("연결된 정산의 공지 연결을 먼저 해제한 뒤 공지를 삭제하고 noticeId를 반환한다") {
                response.shouldNotBeNull()
                response.noticeId shouldBe 30L
                val order = inOrder(settlementRepository, noticeRepository)
                order.verify(settlementRepository).clearNoticeId(noticeId)
                order.verify(noticeRepository).delete(targetNotice)
            }
        }
    }

    Given("공지사항 상세 조회 요청이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val userId = 1L
        // 서버 시간대가 UTC여도 응답은 KST(+09:00)로 나가는지 확인
        val testCreatedAt = OffsetDateTime.parse("2026-10-07T01:00:00Z")

        // 테스트용 Notice 엔티티 생성 (createdAt 명시 주입)
        val notice = Notice(
            meetingId = meetingId,
            membersId = userId,
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다.",
            createdAt = testCreatedAt,
            id = noticeId
        )

        // findById 모킹: Optional.of(notice) 반환
        `when`(noticeRepository.findById(noticeId)).thenReturn(java.util.Optional.of(notice))

        When("getNoticeDetail을 호출하면") {
            val response = noticeService.getNoticeDetail(meetingId, noticeId, userId)

            Then("상세 정보(식별자, 제목, 내용, KST 생성일시)가 정상 반환된다") {
                response.noticeId shouldBe noticeId
                response.title shouldBe "10월 회비 안내"
                response.content shouldBe "10월 회비는 10일까지 납부 부탁드립니다."
                response.createdAt shouldBe OffsetDateTime.parse("2026-10-07T10:00:00+09:00")
                verify(noticeRepository).findById(noticeId)
            }
        }
    }

    Given("공지사항 목록 조회 요청이 주어졌을 때") {
        val meetingId = 1L
        val userId = 1L
        val page = 0
        val size = 20
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        val now = OffsetDateTime.parse("2026-10-07T01:00:00Z")

        val noticeList = listOf(
            Notice(
                id = 30L,
                meetingId = meetingId,
                membersId = userId,
                title = "10월 회비 안내",
                content = "내용",
                createdAt = now
            )
        )
        val pageResult = PageImpl(noticeList, pageable, 1L)

        `when`(noticeRepository.findAllByMeetingId(meetingId, pageable)).thenReturn(pageResult)

        When("getNotices를 호출하면") {
            val response = noticeService.getNotices(meetingId, page, size, userId)

            Then("페이징 메타데이터와 공지 요약 목록(KST 생성일시)이 반환된다") {
                response.notices.size shouldBe 1
                response.notices[0].noticeId shouldBe 30L
                response.notices[0].title shouldBe "10월 회비 안내"
                response.notices[0].createdAt shouldBe OffsetDateTime.parse("2026-10-07T10:00:00+09:00")
                response.page shouldBe 0
                response.size shouldBe 20
                response.hasNext shouldBe false
                verify(noticeRepository).findAllByMeetingId(meetingId, pageable)
            }
        }
    }

    Given("공지 목록 조회의 페이징 경계 상황이 주어졌을 때") {
        val meetingId = 1L
        val sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))

        fun notices(vararg ids: Long) = ids.map { id ->
            Notice(id = id, meetingId = meetingId, membersId = 1L, title = "공지 $id", content = "내용")
        }

        When("공지가 하나도 없으면") {
            val pageable = PageRequest.of(0, 20, sort)
            `when`(noticeRepository.findAllByMeetingId(meetingId, pageable))
                .thenReturn(PageImpl(emptyList(), pageable, 0L))
            val response = noticeService.getNotices(meetingId, 0, 20, 1L)

            Then("빈 목록과 hasNext=false를 반환한다") {
                response.notices shouldBe emptyList()
                response.hasNext shouldBe false
            }
        }

        When("다음 페이지가 남아 있으면 (전체 5개, page=0, size=2)") {
            val pageable = PageRequest.of(0, 2, sort)
            `when`(noticeRepository.findAllByMeetingId(meetingId, pageable))
                .thenReturn(PageImpl(notices(5L, 4L), pageable, 5L))
            val response = noticeService.getNotices(meetingId, 0, 2, 1L)

            Then("요청한 크기만큼 반환하고 hasNext=true를 반환한다") {
                response.notices.map { it.noticeId } shouldBe listOf(5L, 4L)
                response.page shouldBe 0
                response.size shouldBe 2
                response.hasNext shouldBe true
            }
        }

        When("마지막 페이지를 조회하면 (전체 5개, page=2, size=2)") {
            val pageable = PageRequest.of(2, 2, sort)
            `when`(noticeRepository.findAllByMeetingId(meetingId, pageable))
                .thenReturn(PageImpl(notices(1L), pageable, 5L))
            val response = noticeService.getNotices(meetingId, 2, 2, 1L)

            Then("남은 공지만 반환하고 hasNext=false를 반환한다") {
                response.notices.map { it.noticeId } shouldBe listOf(1L)
                response.page shouldBe 2
                response.hasNext shouldBe false
            }
        }
    }

    Given("존재하지 않는 공지사항 식별자가 주어졌을 때") {
        val repository: NoticeRepository = mock(NoticeRepository::class.java)
        val service = NoticeService(repository, settlementRepository)
        val meetingId = 1L
        val noticeId = 999L

        `when`(repository.findById(noticeId)).thenReturn(Optional.empty())

        When("deleteNotice 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                service.deleteNotice(meetingId, noticeId, 1L)
            }

            Then("NOT_FOUND 예외가 발생하고 삭제는 호출되지 않는다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
                verify(repository, never()).delete(any(Notice::class.java))
                verify(settlementRepository, never()).clearNoticeId(anyLong())
            }
        }

        When("getNoticeDetail 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                service.getNoticeDetail(meetingId, noticeId, 1L)
            }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }
    Given("다른 모임에 속한 공지사항 식별자가 주어졌을 때") {
        val repository: NoticeRepository = mock(NoticeRepository::class.java)
        val service = NoticeService(repository, settlementRepository)
        val noticeId = 30L
        val otherMeetingNotice = Notice(
            id = noticeId,
            meetingId = 2L,
            membersId = 1L,
            title = "다른 모임 공지",
            content = "다른 모임 공지 내용"
        )

        `when`(repository.findById(noticeId)).thenReturn(Optional.of(otherMeetingNotice))

        When("deleteNotice 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                service.deleteNotice(1L, noticeId, 1L)
            }

            Then("NOT_FOUND 예외가 발생하고 삭제는 호출되지 않는다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
                verify(repository, never()).delete(any(Notice::class.java))
                verify(settlementRepository, never()).clearNoticeId(anyLong())
            }
        }

        When("getNoticeDetail 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> {
                service.getNoticeDetail(1L, noticeId, 1L)
            }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    Given("공지사항 수정 요청이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 40L

        fun notice() = Notice(id = noticeId, meetingId = meetingId, membersId = 1L, title = "기존 제목", content = "기존 내용")

        When("제목만 보내면") {
            val notice = notice()
            `when`(noticeRepository.findById(noticeId)).thenReturn(Optional.of(notice))
            val response = noticeService.updateNotice(meetingId, noticeId, 1L, NoticeUpdateRequest(title = Optional.of("수정된 제목")))

            Then("제목만 변경되고 내용은 기존 값이 유지된다") {
                response.title shouldBe "수정된 제목"
                response.content shouldBe "기존 내용"
                notice.title shouldBe "수정된 제목"
                notice.content shouldBe "기존 내용"
            }
        }

        When("내용만 보내면") {
            val notice = notice()
            `when`(noticeRepository.findById(noticeId)).thenReturn(Optional.of(notice))
            val response = noticeService.updateNotice(meetingId, noticeId, 1L, NoticeUpdateRequest(content = Optional.of("수정된 내용")))

            Then("내용만 변경되고 제목은 기존 값이 유지된다") {
                response.title shouldBe "기존 제목"
                response.content shouldBe "수정된 내용"
                notice.title shouldBe "기존 제목"
            }
        }

        When("아무 필드도 보내지 않으면") {
            val notice = notice()
            `when`(noticeRepository.findById(noticeId)).thenReturn(Optional.of(notice))
            val response = noticeService.updateNotice(meetingId, noticeId, 1L, NoticeUpdateRequest())

            Then("변경 없이 기존 값을 반환한다") {
                response.noticeId shouldBe noticeId
                response.title shouldBe "기존 제목"
                response.content shouldBe "기존 내용"
            }
        }
    }

    Given("없거나 다른 모임에 속한 공지사항의 수정 요청이 주어졌을 때") {
        val otherMeetingNotice = Notice(id = 42L, meetingId = 2L, membersId = 1L, title = "다른 모임 공지", content = "다른 모임 공지 내용")
        val request = NoticeUpdateRequest(title = Optional.of("수정된 제목"))

        When("존재하지 않는 공지 ID로 updateNotice를 호출하면") {
            `when`(noticeRepository.findById(41L)).thenReturn(Optional.empty())
            val exception = shouldThrow<ApiException> {
                noticeService.updateNotice(1L, 41L, 1L, request)
            }

            Then("NOT_FOUND 예외가 발생한다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
            }
        }

        When("다른 모임의 공지 ID로 updateNotice를 호출하면") {
            `when`(noticeRepository.findById(42L)).thenReturn(Optional.of(otherMeetingNotice))
            val exception = shouldThrow<ApiException> {
                noticeService.updateNotice(1L, 42L, 1L, request)
            }

            Then("NOT_FOUND 예외가 발생하고 공지는 변경되지 않는다") {
                exception.errorCode shouldBe ErrorCode.NOT_FOUND
                otherMeetingNotice.title shouldBe "다른 모임 공지"
            }
        }
    }
})

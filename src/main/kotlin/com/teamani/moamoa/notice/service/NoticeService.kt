package com.teamani.moamoa.notice.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.dto.NoticeListResponse
import com.teamani.moamoa.notice.dto.NoticeSummaryResponse
import com.teamani.moamoa.notice.dto.NoticeUpdateRequest
import com.teamani.moamoa.notice.dto.NoticeUpdateResponse
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import kotlin.jvm.optionals.getOrNull

@Service
class NoticeService(
    private val noticeRepository: NoticeRepository,
    private val settlementRepository: SettlementRepository
) {

    @Transactional
    fun createNotice(meetingId: Long, userId: Long, request: NoticeCreateRequest): NoticeCreateResponse {
        // 관리자(ADMIN) 권한 검증 (추후 Member 도메인 연동)
        validateAdmin(meetingId, userId)

        // 요청 DTO 값으로 실제 Entity 객체 생성
        val notice = Notice(
            meetingId = meetingId,
            membersId = userId,
            title = requireNotNull(request.title) { "제목은 필수입니다." },
            content = requireNotNull(request.content) { "내용은 필수입니다." }
        )

        // DB 영구 저장
        val savedNotice = noticeRepository.save(notice)

        // 성공 응답 DTO 반환
        return NoticeCreateResponse(
            noticeId = checkNotNull(savedNotice.id) { "공지 식별자가 생성되지 않았습니다." },
            title = savedNotice.title
        )
    }

    // 관리자 권한 검증 함수 (추후 Member 도메인 연동 자리)
    private fun validateAdmin(meetingId: Long, userId: Long) {
        // TODO: memberService 또는 memberRepository를 조회하여 해당 사용자가 모임의 ADMIN인지 확인
        // 만약 ADMIN이 아니면 403 FORBIDDEN 성격의 예외(CustomException)를 throw하도록 연결.
    }

    @Transactional
    fun deleteNotice(meetingId: Long, noticeId: Long, userId: Long): NoticeDeleteResponse {
        // ADMIN 권한 검증 (Member 도메인 연동 후속 이슈 처리 예정)
        validateAdmin(meetingId, userId)

        // 공지 조회 (존재하지 않거나 다른 모임의 공지면 404)
        val notice = noticeRepository.findById(noticeId)
            .filter { it.meetingId == meetingId }
            .orElseThrow { ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.") }

        // 안내 공지가 연결된 정산은 유지하고 연결만 해제한 뒤 삭제
        settlementRepository.clearNoticeId(noticeId)
        noticeRepository.delete(notice)

        return NoticeDeleteResponse(noticeId = noticeId)
    }
    @Transactional(readOnly = true)
    fun getNoticeDetail(meetingId: Long, noticeId: Long, currentUserId: Long): NoticeDetailResponse {
        val notice = noticeRepository.findById(noticeId)
            .filter { it.meetingId == meetingId }
            .orElseThrow { ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.") }

        return NoticeDetailResponse(
            noticeId = checkNotNull(notice.id) { "공지사항 ID가 null입니다." },
            title = notice.title,
            content = notice.content,
            createdAt = notice.createdAt.withOffsetSameInstant(KST)
        )
    }

    @Transactional(readOnly = true)
    fun getNotices(
        meetingId: Long,
        page: Int,
        size: Int,
        currentUserId: Long
    ): NoticeListResponse {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        val noticePage = noticeRepository.findAllByMeetingId(meetingId, pageable)

        val summaries = noticePage.content.map { notice ->
            NoticeSummaryResponse(
                noticeId = checkNotNull(notice.id) { "공지사항 ID가 null입니다." },
                title = notice.title,
                createdAt = notice.createdAt.withOffsetSameInstant(KST)
            )
        }

        return NoticeListResponse(
            notices = summaries,
            page = noticePage.number,
            size = noticePage.size,
            hasNext = noticePage.hasNext()
        )
    }

    @Transactional
    fun updateNotice(
        meetingId: Long,
        noticeId: Long,
        userId: Long,
        request: NoticeUpdateRequest
    ): NoticeUpdateResponse {
        // ADMIN 권한 검증 (Member 도메인 연동 후속 이슈 처리 예정)
        validateAdmin(meetingId, userId)

        // 공지가 없거나 다른 모임의 공지면 404
        val notice = noticeRepository.findById(noticeId)
            .filter { it.meetingId == meetingId }
            .orElseThrow { ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.") }

        // 변경 감지(Dirty Checking)로 트랜잭션 종료 시 반영
        notice.update(request.title?.getOrNull(), request.content?.getOrNull())

        return NoticeUpdateResponse(
            noticeId = checkNotNull(notice.id) { "공지사항 ID가 null입니다." },
            title = notice.title,
            content = notice.content
        )
    }

    companion object {
        // API 명세상 응답 일시는 KST(+09:00)
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
    }
}

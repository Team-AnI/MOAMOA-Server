package com.teamani.moamoa.notice.service

import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.repository.NoticeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NoticeService(
    private val noticeRepository: NoticeRepository
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

        // 공지 조회 (존재하지 않으면 예외 발생)
        val notice = noticeRepository.findById(noticeId)
            .orElseThrow { IllegalArgumentException("공지사항을 찾을 수 없습니다. id: $noticeId") }

        // 해당 모임에 속한 공지인지 검증
        require(notice.meetingId == meetingId) { "해당 모임의 공지사항이 아닙니다." }

        // DB 삭제
        noticeRepository.delete(notice)

        return NoticeDeleteResponse(noticeId = noticeId)
    }
    @Transactional(readOnly = true)
    fun getNoticeDetail(meetingId: Long, noticeId: Long, currentUserId: Long): NoticeDetailResponse {
        val notice = noticeRepository.findById(noticeId)
            .orElseThrow { IllegalArgumentException("공지사항을 찾을 수 없습니다. (ID: $noticeId)") }

        require(notice.meetingId == meetingId) { "해당 모임의 공지사항이 아닙니다." }

        return NoticeDetailResponse(
            noticeId = checkNotNull(notice.id) { "공지사항 ID가 null입니다." },
            title = notice.title,
            content = notice.content,
            createdAt = notice.createdAt
        )
    }
}

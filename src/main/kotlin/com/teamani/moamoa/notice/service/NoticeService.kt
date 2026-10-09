package com.teamani.moamoa.notice.service

import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
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
            authorId = userId,
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
}
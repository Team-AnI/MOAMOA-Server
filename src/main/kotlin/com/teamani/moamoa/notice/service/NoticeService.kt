package com.teamani.moamoa.notice.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.dto.NoticeListResponse
import com.teamani.moamoa.notice.dto.NoticeReadMemberResponse
import com.teamani.moamoa.notice.dto.NoticeReadResponse
import com.teamani.moamoa.notice.dto.NoticeReadStatusResponse
import com.teamani.moamoa.notice.dto.NoticeSummaryResponse
import com.teamani.moamoa.notice.dto.NoticeUnreadMemberResponse
import com.teamani.moamoa.notice.dto.NoticeUpdateRequest
import com.teamani.moamoa.notice.dto.NoticeUpdateResponse
import com.teamani.moamoa.notice.entity.Notice
import com.teamani.moamoa.notice.entity.NoticeRead
import com.teamani.moamoa.notice.repository.NoticeReadRepository
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.user.entity.ProfileColor
import com.teamani.moamoa.user.repository.AppUserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import kotlin.jvm.optionals.getOrNull

@Service
class NoticeService(
    private val noticeRepository: NoticeRepository,
    private val settlementRepository: SettlementRepository,
    private val noticeReadRepository: NoticeReadRepository,
    private val memberService: MemberService,
    private val memberRepository: MemberRepository,
    private val appUserRepository: AppUserRepository
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
        val noticeId = checkNotNull(savedNotice.id) { "공지 식별자가 생성되지 않았습니다." }

        // 작성자는 작성할 때 읽음으로 기록 (정산·정기 회비 안내 공지 포함)
        noticeReadRepository.save(NoticeRead(noticeId = noticeId, memberId = savedNotice.membersId, readAt = savedNotice.createdAt))

        // 성공 응답 DTO 반환
        return NoticeCreateResponse(
            noticeId = noticeId,
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

        // 안내 공지가 연결된 정산은 유지하고 연결만 해제, 읽음 기록은 함께 삭제한 뒤 공지 삭제
        settlementRepository.clearNoticeId(noticeId)
        noticeReadRepository.deleteAllByNoticeId(noticeId)
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
            settlementId = settlementRepository.findFirstByNoticeId(noticeId)?.id,
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

        // 공지마다 집계 쿼리가 반복되지 않도록 페이지의 공지 읽음 수를 한 번에 조회
        val noticeIds = noticePage.content.mapNotNull { it.id }
        val readCounts = if (noticeIds.isEmpty()) {
            emptyMap()
        } else {
            noticeReadRepository.countGroupByNoticeIdIn(meetingId, noticeIds).associate { it.noticeId to it.readCount.toInt() }
        }
        val totalCount = memberRepository.countByMeetingId(meetingId).toInt()

        val summaries = noticePage.content.map { notice ->
            val noticeId = checkNotNull(notice.id) { "공지사항 ID가 null입니다." }
            NoticeSummaryResponse(
                noticeId = noticeId,
                title = notice.title,
                readCount = readCounts[noticeId] ?: 0,
                totalCount = totalCount,
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

    // 내 공지 읽음 처리: 여러 번 호출해도 처음 읽은 시각을 유지
    @Transactional
    fun readNotice(meetingId: Long, noticeId: Long, userId: Long): NoticeReadResponse {
        val memberId = checkNotNull(memberService.validateMember(meetingId, userId).id) { "구성원 식별자가 없습니다." }

        // 같은 공지를 잠금 조회해 동시에 처음 읽어도 기록이 하나만 남게 함 (unique 제약 위반 방지)
        noticeRepository.findByIdAndMeetingIdForUpdate(noticeId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.")

        val noticeRead = noticeReadRepository.findByNoticeIdAndMemberId(noticeId, memberId)
            ?: noticeReadRepository.save(NoticeRead(noticeId = noticeId, memberId = memberId))

        return NoticeReadResponse(noticeId = noticeId, readAt = noticeRead.readAt.withOffsetSameInstant(KST))
    }

    // 공지 읽음 현황: 현재 모임 구성원 기준 (나중에 가입한 구성원은 미열람, 나간 구성원은 제외)
    @Transactional(readOnly = true)
    fun getNoticeReads(meetingId: Long, noticeId: Long, userId: Long): NoticeReadStatusResponse {
        memberService.validateMember(meetingId, userId)
        noticeRepository.findById(noticeId)
            .filter { it.meetingId == meetingId }
            .orElseThrow { ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.") }

        val members = memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId)
        val readAtByMemberId = noticeReadRepository.findAllByNoticeId(noticeId).associate { it.memberId to it.readAt }
        val usersById = appUserRepository.findAllById(members.map { it.userId }).associateBy { it.id }

        val readMembers = mutableListOf<NoticeReadMemberResponse>()
        val unreadMembers = mutableListOf<NoticeUnreadMemberResponse>()
        members.forEach { member ->
            val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }
            val user = usersById[member.userId]
            val profileColor = user?.profileColor ?: ProfileColor.LIGHT_BLUE
            val readAt = readAtByMemberId[memberId]
            if (readAt == null) {
                unreadMembers += NoticeUnreadMemberResponse(memberId, user?.name, profileColor)
            } else {
                readMembers += NoticeReadMemberResponse(memberId, user?.name, profileColor, readAt.withOffsetSameInstant(KST))
            }
        }

        return NoticeReadStatusResponse(
            noticeId = noticeId,
            readCount = readMembers.size,
            totalCount = members.size,
            readMembers = readMembers.sortedWith(compareBy({ it.readAt }, { it.memberId })),
            unreadMembers = unreadMembers
        )
    }

    companion object {
        // API 명세상 응답 일시는 KST(+09:00)
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
    }
}

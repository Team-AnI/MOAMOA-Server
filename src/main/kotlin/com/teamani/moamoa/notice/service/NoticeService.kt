package com.teamani.moamoa.notice.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.notice.dto.NoticeAuthorResponse
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

    // 공지 작성 API(3-1): 요청한 사용자의 구성원 ID를 작성자로 저장
    @Transactional
    fun createNotice(meetingId: Long, userId: Long, request: NoticeCreateRequest): NoticeCreateResponse {
        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403
        val authorMemberId = checkNotNull(memberService.validateAdmin(meetingId, userId).id) { "구성원 식별자가 없습니다." }

        return createNoticeByMember(meetingId, authorMemberId, request)
    }

    // 정산·정기 회비 안내 공지처럼 작성자 구성원 ID가 정해진 내부 호출용 (권한 검증은 호출하는 쪽에서 처리)
    @Transactional
    fun createNoticeByMember(meetingId: Long, authorMemberId: Long, request: NoticeCreateRequest): NoticeCreateResponse {
        val savedNotice = noticeRepository.save(
            Notice(
                meetingId = meetingId,
                membersId = authorMemberId,
                title = requireNotNull(request.title) { "제목은 필수입니다." },
                content = requireNotNull(request.content) { "내용은 필수입니다." }
            )
        )
        val noticeId = checkNotNull(savedNotice.id) { "공지 식별자가 생성되지 않았습니다." }

        // 작성자는 작성할 때 읽음으로 기록
        noticeReadRepository.save(NoticeRead(noticeId = noticeId, memberId = authorMemberId, readAt = savedNotice.createdAt))

        return NoticeCreateResponse(
            noticeId = noticeId,
            title = savedNotice.title
        )
    }

    // 정산 취소가 공지 삭제와 같은 순서(공지 → 정산)로 잠그도록 공지를 먼저 잠금 (반대 순서면 동시 요청 시 교착)
    @Transactional
    fun lockNotice(meetingId: Long, noticeId: Long) {
        noticeRepository.findByIdAndMeetingIdForUpdate(noticeId, meetingId)
    }

    // 정산 취소 시 기존 안내 공지 표시: 제목 앞에 [취소](최대 50자), 본문 맨 앞에 취소 안내와 구분선 (명세 4-12)
    @Transactional
    fun markCanceled(meetingId: Long, noticeId: Long, cancelMessage: String) {
        val notice = noticeRepository.findById(noticeId)
            .filter { it.meetingId == meetingId }
            .orElseThrow { ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.") }
        notice.update(
            title = "[취소] ${notice.title}".take(TITLE_MAX_LENGTH),
            content = "$cancelMessage\n──── 기존 내용 ────\n${notice.content}"
        )
    }

    @Transactional
    fun deleteNotice(meetingId: Long, noticeId: Long, userId: Long): NoticeDeleteResponse {
        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403
        memberService.validateAdmin(meetingId, userId)

        // 공지 잠금 조회 (읽음 처리와 직렬화해 삭제 후 읽음 기록이 남지 않게 함, 없거나 다른 모임 공지면 404)
        val notice = noticeRepository.findByIdAndMeetingIdForUpdate(noticeId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.")

        // 안내 공지가 연결된 정산은 유지하고 연결만 해제, 읽음 기록은 함께 삭제한 뒤 공지 삭제
        settlementRepository.clearNoticeId(noticeId)
        noticeReadRepository.deleteAllByNoticeId(noticeId)
        noticeRepository.delete(notice)

        return NoticeDeleteResponse(noticeId = noticeId)
    }
    @Transactional(readOnly = true)
    fun getNoticeDetail(meetingId: Long, noticeId: Long, currentUserId: Long): NoticeDetailResponse {
        // 모임 없음·종료 404 → 구성원 아님 403
        memberService.validateMember(meetingId, currentUserId)

        val notice = noticeRepository.findById(noticeId)
            .filter { it.meetingId == meetingId }
            .orElseThrow { ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.") }

        return NoticeDetailResponse(
            noticeId = checkNotNull(notice.id) { "공지사항 ID가 null입니다." },
            title = notice.title,
            content = notice.content,
            author = authorsOf(meetingId, listOf(notice.membersId)).getValue(notice.membersId),
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
        // 모임 없음·종료 404 → 구성원 아님 403
        memberService.validateMember(meetingId, currentUserId)

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
        // 공지마다 작성자를 조회하지 않도록 페이지의 작성자를 한 번에 조회
        val authors = authorsOf(meetingId, noticePage.content.map { it.membersId })

        val summaries = noticePage.content.map { notice ->
            val noticeId = checkNotNull(notice.id) { "공지사항 ID가 null입니다." }
            NoticeSummaryResponse(
                noticeId = noticeId,
                title = notice.title,
                author = authors.getValue(notice.membersId),
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
        // 모임 없음·종료 404 → 구성원 아님·ADMIN 아님 403
        memberService.validateAdmin(meetingId, userId)

        // 공지가 없거나 다른 모임의 공지면 404
        // 잠금 조회로 정산 취소의 [취소] 표시 등 먼저 끝난 변경을 읽은 뒤 수정 (안 바꾼 항목이 이전 값으로 덮어써지지 않게 함)
        val notice = noticeRepository.findByIdAndMeetingIdForUpdate(noticeId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다.")

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
        // 모임 나가기와 같은 구성원 잠금으로 확인해, 나간 구성원의 읽음 기록이 남지 않게 함 (잠금 순서: 구성원 → 공지)
        val memberId = checkNotNull(memberService.validateMemberForUpdate(meetingId, userId).id) { "구성원 식별자가 없습니다." }

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

    // 작성자 구성원 ID별 표시 정보 (구성원이 나갔거나 사용자가 없으면 "탈퇴한 사용자"와 기본 프로필 색)
    // 잘못 저장된 ID가 다른 모임 구성원을 가리켜도 그 사람 정보가 노출되지 않도록 공지와 같은 모임 구성원만 사용
    private fun authorsOf(meetingId: Long, authorMemberIds: Collection<Long>): Map<Long, NoticeAuthorResponse> {
        val ids = authorMemberIds.toSet()
        val membersById = memberRepository.findAllById(ids).filter { it.meetingId == meetingId }.associateBy { it.id }
        val usersById = appUserRepository.findAllById(membersById.values.map { it.userId }).associateBy { it.id }

        return ids.associateWith { memberId ->
            val user = membersById[memberId]?.let { usersById[it.userId] }
            NoticeAuthorResponse(
                memberId = memberId,
                userName = user?.name ?: WITHDRAWN_USER_NAME,
                profileColor = user?.profileColor ?: ProfileColor.LIGHT_BLUE
            )
        }
    }

    companion object {
        // API 명세상 응답 일시는 KST(+09:00)
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)

        // 명세 3-2·3-3: 작성자가 모임을 나갔거나 탈퇴했을 때 표시 이름
        private const val WITHDRAWN_USER_NAME = "탈퇴한 사용자"

        private const val TITLE_MAX_LENGTH = 50
    }
}

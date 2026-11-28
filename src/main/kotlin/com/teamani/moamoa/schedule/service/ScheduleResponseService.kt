package com.teamani.moamoa.schedule.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.dto.ScheduleMyResponseRequest
import com.teamani.moamoa.schedule.dto.ScheduleMyResponseResponse
import com.teamani.moamoa.schedule.dto.ScheduleNoResponseMemberResponse
import com.teamani.moamoa.schedule.dto.ScheduleRespondedMemberResponse
import com.teamani.moamoa.schedule.dto.ScheduleResponseSummaryResponse
import com.teamani.moamoa.schedule.dto.ScheduleResponsesResponse
import com.teamani.moamoa.schedule.entity.ScheduleResponse
import com.teamani.moamoa.schedule.entity.ScheduleResponseStatus
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.schedule.repository.ScheduleResponseRepository
import com.teamani.moamoa.user.entity.ProfileColor
import com.teamani.moamoa.user.repository.AppUserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

// 일정 참석 응답 (2-6 내 참석 응답, 2-7 참석 현황)
@Service
class ScheduleResponseService(
    private val scheduleRepository: ScheduleRepository,
    private val scheduleResponseRepository: ScheduleResponseRepository,
    private val memberService: MemberService,
    private val memberRepository: MemberRepository,
    private val appUserRepository: AppUserRepository
) {

    // 처음이면 저장, 이미 응답했으면 변경
    @Transactional
    fun respond(meetingId: Long, userId: Long, scheduleId: Long, request: ScheduleMyResponseRequest): ScheduleMyResponseResponse {
        val status = ScheduleResponseStatus.valueOf(requireNotNull(request.status) { "참석 응답은 필수입니다." })

        // 모임 나가기와 같은 구성원 잠금으로 확인해, 같은 구성원의 동시 응답이 중복 저장되거나 나간 구성원의 응답이 남지 않게 함
        // 잠금 순서는 출석·투표와 같이 구성원 → 일정
        val memberId = checkNotNull(memberService.validateMemberForUpdate(meetingId, userId).id) { "구성원 식별자가 없습니다." }
        // 일정 삭제와 같은 일정 잠금으로 조회해, 삭제된 일정에 응답이 남지 않게 함
        val schedule = scheduleRepository.findByIdAndMeetingIdForUpdate(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다.")

        if (schedule.status !in RESPONDABLE_STATUSES) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "투표 중이거나 취소된 일정에는 응답할 수 없습니다.")
        }
        val now = OffsetDateTime.now()
        if (now.isAfter(schedule.effectiveResponseDeadline())) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "참석 응답 마감이 지났습니다.")
        }

        val response = scheduleResponseRepository.findByScheduleIdAndMemberId(scheduleId, memberId)
            ?.also { it.change(status, now) }
            ?: scheduleResponseRepository.save(ScheduleResponse(scheduleId = scheduleId, memberId = memberId, status = status, respondedAt = now))

        return ScheduleMyResponseResponse(
            scheduleId = scheduleId,
            status = response.status,
            respondedAt = response.respondedAt.withOffsetSameInstant(KST)
        )
    }

    // 현재 구성원 기준 참석 현황 (응답 후 가입한 구성원은 미응답, 나간 구성원은 제외, 목록은 가입 순)
    @Transactional(readOnly = true)
    fun getResponses(meetingId: Long, userId: Long, scheduleId: Long): ScheduleResponsesResponse {
        // 모임 없음·종료 404 → 구성원 아님 403
        val myMemberId = checkNotNull(memberService.validateMember(meetingId, userId).id) { "구성원 식별자가 없습니다." }
        val schedule = scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "일정을 찾을 수 없습니다.")

        val members = memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId)
        val responsesByMemberId = scheduleResponseRepository.findAllByScheduleId(scheduleId).associateBy { it.memberId }
        val usersById = appUserRepository.findAllById(members.map { it.userId }).associateBy { it.id }

        val responded = mutableMapOf<ScheduleResponseStatus, MutableList<ScheduleRespondedMemberResponse>>()
        val noResponse = mutableListOf<ScheduleNoResponseMemberResponse>()
        members.forEach { member ->
            val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }
            val user = usersById[member.userId]
            val userName = user?.name ?: WITHDRAWN_USER_NAME
            val profileColor = user?.profileColor ?: ProfileColor.LIGHT_BLUE
            val response = responsesByMemberId[memberId]
            if (response == null) {
                noResponse += ScheduleNoResponseMemberResponse(memberId, userName, profileColor)
            } else {
                responded.getOrPut(response.status) { mutableListOf() } +=
                    ScheduleRespondedMemberResponse(memberId, userName, profileColor, response.respondedAt.withOffsetSameInstant(KST))
            }
        }
        val attending = responded[ScheduleResponseStatus.ATTENDING].orEmpty()
        val notAttending = responded[ScheduleResponseStatus.NOT_ATTENDING].orEmpty()
        val undecided = responded[ScheduleResponseStatus.UNDECIDED].orEmpty()

        return ScheduleResponsesResponse(
            scheduleId = scheduleId,
            // 투표 중 일정은 시작 일시가 후보 중 임시 값이라 마감을 알려주지 않음
            responseDeadlineAt = if (schedule.status == ScheduleStatus.VOTING) null
            else schedule.effectiveResponseDeadline().withOffsetSameInstant(KST),
            myStatus = responsesByMemberId[myMemberId]?.status,
            summary = ScheduleResponseSummaryResponse(
                attendingCount = attending.size,
                notAttendingCount = notAttending.size,
                undecidedCount = undecided.size,
                noResponseCount = noResponse.size,
                totalCount = members.size
            ),
            attendingMembers = attending,
            notAttendingMembers = notAttending,
            undecidedMembers = undecided,
            noResponseMembers = noResponse
        )
    }

    companion object {
        // API 명세상 응답 일시는 KST(+09:00)
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)

        // 예정·확정 일정만 응답 가능 (투표 중은 시간 미정, 취소는 응답할 이유 없음)
        private val RESPONDABLE_STATUSES = setOf(ScheduleStatus.SCHEDULED, ScheduleStatus.CONFIRMED)

        private const val WITHDRAWN_USER_NAME = "탈퇴한 사용자"
    }
}

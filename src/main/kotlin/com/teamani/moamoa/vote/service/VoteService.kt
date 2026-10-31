package com.teamani.moamoa.vote.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.entity.ScheduleStatus
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.vote.dto.MyVoteSubmitRequest
import com.teamani.moamoa.vote.dto.MyVoteSubmitResponse
import com.teamani.moamoa.vote.dto.VoteCandidateResponse
import com.teamani.moamoa.vote.dto.VoteConfirmRequest
import com.teamani.moamoa.vote.dto.VoteConfirmResponse
import com.teamani.moamoa.vote.dto.VoteCreateRequest
import com.teamani.moamoa.vote.dto.VoteCreateResponse
import com.teamani.moamoa.vote.dto.VoteDetailResponse
import com.teamani.moamoa.vote.dto.VoteMemberResponse
import com.teamani.moamoa.vote.dto.VoteParticipationResponse
import com.teamani.moamoa.vote.entity.Vote
import com.teamani.moamoa.vote.entity.VoteStatus
import com.teamani.moamoa.vote.repository.VoteRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class VoteService(
    private val memberService: MemberService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val voteRepository: VoteRepository
) {

    @Transactional
    fun createVote(meetingId: Long, userId: Long, request: VoteCreateRequest): VoteCreateResponse {
        val admin = memberService.validateAdmin(meetingId, userId)
        val adminMemberId = checkNotNull(admin.id) { "구성원 식별자가 없습니다." }

        val meetingTimes = requireNotNull(request.meetingTimes) { "후보 시간은 1개 이상이어야 합니다." }
            .map { requireNotNull(it) { "후보 시간은 null일 수 없습니다." } }
            .sortedBy { it.toInstant() }
        validateNoDuplicate(meetingTimes)
        val voteDeadlineAt = requireNotNull(request.voteDeadlineAt) { "투표 마감 일시는 필수입니다." }

        // 일정 시작 일시가 NOT NULL이라 가장 이른 후보 시간을 임시 저장 (null 허용 반영 후 null로 변경)
        val schedule = scheduleRepository.save(
            Schedule(
                meetingId = meetingId,
                title = requireNotNull(request.title) { "투표 제목은 필수입니다." },
                startsAt = meetingTimes.first(),
                status = ScheduleStatus.VOTING
            )
        )
        val scheduleId = checkNotNull(schedule.id) { "일정 식별자가 생성되지 않았습니다." }

        // 투표를 만든 관리자 행을 후보 수만큼 PENDING으로 저장해 후보 목록 기준으로 사용
        voteRepository.saveAll(
            meetingTimes.map {
                Vote(
                    memberId = adminMemberId,
                    scheduleId = scheduleId,
                    meetingTime = it,
                    status = VoteStatus.PENDING,
                    endedAt = voteDeadlineAt
                )
            }
        )

        return VoteCreateResponse(
            scheduleId = scheduleId,
            title = schedule.title,
            voteDeadlineAt = voteDeadlineAt.withOffsetSameInstant(KST),
            meetingTimes = meetingTimes.map { it.withOffsetSameInstant(KST) }
        )
    }

    @Transactional(readOnly = true)
    fun getVote(meetingId: Long, userId: Long, scheduleId: Long): VoteDetailResponse {
        val me = memberService.validateMember(meetingId, userId)
        val myMemberId = checkNotNull(me.id) { "구성원 식별자가 없습니다." }
        val schedule = findSchedule(meetingId, scheduleId)
        val votes = findVotes(scheduleId)
        val voteDeadlineAt = votes.first().endedAt

        val availableVotes = votes.filter { it.status == VoteStatus.AVAILABLE }
        val availableCounts = availableVotes.groupingBy { it.meetingTime.toInstant() }.eachCount()
        val mySelectedTimes = availableVotes.filter { it.memberId == myMemberId }.map { it.meetingTime.toInstant() }.toSet()
        val respondedMemberIds = respondedMemberIds(votes)

        return VoteDetailResponse(
            scheduleId = scheduleId,
            title = schedule.title,
            status = schedule.status,
            voteDeadlineAt = voteDeadlineAt.withOffsetSameInstant(KST),
            votingOpen = isVotingOpen(schedule, voteDeadlineAt),
            memberCount = memberRepository.countByMeetingId(meetingId),
            respondedCount = respondedMemberIds.size.toLong(),
            myResponded = myMemberId in respondedMemberIds,
            // 아무도 고르지 않은 후보도 0명으로 포함
            candidates = candidateTimes(votes).map { candidate ->
                VoteCandidateResponse(
                    meetingTime = candidate.atOffset(KST),
                    availableCount = (availableCounts[candidate] ?: 0).toLong(),
                    selectedByMe = candidate in mySelectedTimes
                )
            }
        )
    }

    @Transactional
    fun submitMyVote(
        meetingId: Long,
        userId: Long,
        scheduleId: Long,
        request: MyVoteSubmitRequest
    ): MyVoteSubmitResponse {
        val me = memberService.validateMember(meetingId, userId)
        val myMemberId = checkNotNull(me.id) { "구성원 식별자가 없습니다." }
        val schedule = findScheduleForUpdate(meetingId, scheduleId)
        val votes = findVotes(scheduleId)
        val voteDeadlineAt = votes.first().endedAt

        // 마감 이후이거나 VOTING이 아니면 기존 선택 유지
        if (schedule.status != ScheduleStatus.VOTING) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "진행 중인 투표가 아닙니다.")
        }
        if (!OffsetDateTime.now().isBefore(voteDeadlineAt)) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "투표가 마감되었습니다.")
        }

        val meetingTimes = requireNotNull(request.meetingTimes) { "선택한 후보 시간 목록은 필수입니다." }
            .map { requireNotNull(it) { "후보 시간은 null일 수 없습니다." } }
        validateNoDuplicate(meetingTimes)
        val candidates = candidateTimes(votes)
        val selectedTimes = meetingTimes.map { it.toInstant() }.toSet()
        if (!candidates.containsAll(selectedTimes)) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "후보에 없는 시간이 포함되어 있습니다.")
        }

        // 기존 응답을 지우고 모든 후보를 다시 저장해 created_at을 마지막 제출 시각으로 유지
        val submittedAt = OffsetDateTime.now()
        voteRepository.deleteAllByScheduleIdAndMemberId(scheduleId, myMemberId)
        try {
            voteRepository.saveAllAndFlush(
                candidates.map { candidate ->
                    Vote(
                        memberId = myMemberId,
                        scheduleId = scheduleId,
                        meetingTime = candidate.atOffset(KST),
                        status = if (candidate in selectedTimes) VoteStatus.AVAILABLE else VoteStatus.UNAVAILABLE,
                        createdAt = submittedAt,
                        endedAt = voteDeadlineAt
                    )
                }
            )
        } catch (ex: DataIntegrityViolationException) {
            // 같은 일정의 제출은 일정 잠금으로 순서대로 처리되며, unique 제약 위반은 안전장치로 409 변환
            // 그 외 무결성 오류는 그대로 전달
            if (!ex.violates(Vote.UK_SCHEDULE_ID_MEMBER_ID_MEETING_TIME)) {
                throw ex
            }
            throw ApiException(ErrorCode.CONFLICT, "투표 제출 중 충돌이 발생했습니다. 다시 시도해 주세요.")
        }

        return MyVoteSubmitResponse(
            scheduleId = scheduleId,
            meetingTimes = candidates.filter { it in selectedTimes }.map { it.atOffset(KST) },
            submittedAt = submittedAt.withOffsetSameInstant(KST)
        )
    }

    @Transactional(readOnly = true)
    fun getVoteParticipation(meetingId: Long, userId: Long, scheduleId: Long): VoteParticipationResponse {
        memberService.validateAdmin(meetingId, userId)
        findSchedule(meetingId, scheduleId)
        val votes = findVotes(scheduleId)

        // 응답한 구성원별 마지막 제출 시각 (한 번에 저장한 행은 created_at이 같음)
        val submittedAtByMemberId = votes.filter { it.status != VoteStatus.PENDING }
            .groupBy { it.memberId }
            .mapValues { (_, memberVotes) -> memberVotes.maxOf { it.createdAt } }

        // 조회 시점의 구성원 전체 대상 (투표 생성 후 가입자 포함, 1-7과 같은 memberId 오름차순)
        val members = memberRepository.findAllByMeetingIdOrderByIdAsc(meetingId).map { member ->
            val memberId = checkNotNull(member.id) { "구성원 식별자가 없습니다." }
            val submittedAt = submittedAtByMemberId[memberId]
            VoteMemberResponse(
                memberId = memberId,
                nickname = null,
                responded = submittedAt != null,
                submittedAt = submittedAt?.withOffsetSameInstant(KST)
            )
        }

        return VoteParticipationResponse(
            memberCount = members.size.toLong(),
            respondedCount = members.count { it.responded }.toLong(),
            members = members
        )
    }

    @Transactional
    fun confirmVote(
        meetingId: Long,
        userId: Long,
        scheduleId: Long,
        request: VoteConfirmRequest
    ): VoteConfirmResponse {
        memberService.validateAdmin(meetingId, userId)
        val schedule = findScheduleForUpdate(meetingId, scheduleId)
        val votes = findVotes(scheduleId)

        // 마감 전에도 확정 가능, 이미 확정·취소된 일정은 변경 불가
        if (schedule.status != ScheduleStatus.VOTING) {
            throw ApiException(ErrorCode.UNPROCESSABLE_ENTITY, "진행 중인 투표만 확정할 수 있습니다.")
        }

        val meetingTime = requireNotNull(request.meetingTime) { "확정할 후보 시간은 필수입니다." }
        if (meetingTime.toInstant() !in candidateTimes(votes)) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "후보에 없는 시간입니다.")
        }

        // 변경 감지로 확정 시간과 상태 반영 (vote 행은 확정 후 조회를 위해 유지)
        schedule.startsAt = meetingTime
        schedule.status = ScheduleStatus.CONFIRMED

        return VoteConfirmResponse(
            scheduleId = scheduleId,
            title = schedule.title,
            startAt = schedule.startsAt.withOffsetSameInstant(KST),
            status = schedule.status
        )
    }

    // 다른 모임의 일정도 존재하지 않는 투표로 처리
    private fun findSchedule(meetingId: Long, scheduleId: Long): Schedule =
        scheduleRepository.findByIdAndMeetingId(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, VOTE_NOT_FOUND_MESSAGE)

    // 제출·확정은 일정 행을 잠근 뒤 읽어, 같은 일정의 앞선 요청이 끝난 뒤의 상태로 검사
    private fun findScheduleForUpdate(meetingId: Long, scheduleId: Long): Schedule =
        scheduleRepository.findByIdAndMeetingIdForUpdate(scheduleId, meetingId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, VOTE_NOT_FOUND_MESSAGE)

    // 투표 행이 없는 일정(직접 등록한 일정)은 투표가 없는 것으로 판단
    private fun findVotes(scheduleId: Long): List<Vote> =
        voteRepository.findAllByScheduleId(scheduleId)
            .ifEmpty { throw ApiException(ErrorCode.NOT_FOUND, VOTE_NOT_FOUND_MESSAGE) }

    // 일정의 vote 행 meeting_time을 같은 순간 기준으로 중복 제거 후 오름차순 정렬한 후보 목록
    private fun candidateTimes(votes: List<Vote>): List<Instant> =
        votes.map { it.meetingTime.toInstant() }.distinct().sorted()

    // PENDING이 아닌 행이 있는 구성원을 응답한 구성원으로 판단
    private fun respondedMemberIds(votes: List<Vote>): Set<Long> =
        votes.filter { it.status != VoteStatus.PENDING }.map { it.memberId }.toSet()

    // VOTING 상태이고 마감 전이면 투표 진행 중
    private fun isVotingOpen(schedule: Schedule, voteDeadlineAt: OffsetDateTime): Boolean =
        schedule.status == ScheduleStatus.VOTING && OffsetDateTime.now().isBefore(voteDeadlineAt)

    // 표기(+09:00, Z)가 달라도 같은 순간이면 같은 시간으로 판단
    private fun validateNoDuplicate(meetingTimes: List<OffsetDateTime>) {
        if (meetingTimes.map { it.toInstant() }.toSet().size != meetingTimes.size) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "후보 시간은 중복될 수 없습니다.")
        }
    }

    // 위반한 제약 이름 확인 (PostgreSQL은 제약 이름 그대로, H2는 "public.<이름> INDEX ..." 형태라 포함 여부로 비교)
    private fun DataIntegrityViolationException.violates(constraintName: String): Boolean {
        val violation = cause as? ConstraintViolationException ?: return false
        return violation.constraintName?.contains(constraintName, ignoreCase = true) == true
    }

    companion object {
        private const val VOTE_NOT_FOUND_MESSAGE = "투표를 찾을 수 없습니다."

        // 응답 일시는 KST(+09:00) 기준
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
    }
}

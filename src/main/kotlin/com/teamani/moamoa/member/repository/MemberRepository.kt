package com.teamani.moamoa.member.repository

import com.teamani.moamoa.member.entity.Member
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberRepository : JpaRepository<Member, Long> {

    fun findByMeetingIdAndUserId(meetingId: Long, userId: Long): Member?

    // 모임 나가기와 구성원 기록 저장(공지 읽음 처리)을 구성원 단위로 직렬화 (트랜잭션이 끝날 때까지 같은 구성원의 다른 요청은 대기)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.meetingId = :meetingId and m.userId = :userId")
    fun findByMeetingIdAndUserIdForUpdate(@Param("meetingId") meetingId: Long, @Param("userId") userId: Long): Member?

    fun existsByMeetingIdAndUserId(meetingId: Long, userId: Long): Boolean

    fun findAllByUserIdOrderByIdAsc(userId: Long): List<Member>

    fun findAllByMeetingIdOrderByIdAsc(meetingId: Long): List<Member>

    fun countByMeetingId(meetingId: Long): Long

    // 모임별 구성원 수를 한 번의 집계 쿼리로 조회 (모임 수만큼 count 쿼리가 반복되는 N+1 방지)
    @Query(
        "select m.meetingId as meetingId, count(m) as memberCount " +
            "from Member m where m.meetingId in :meetingIds group by m.meetingId",
    )
    fun countGroupByMeetingIdIn(@Param("meetingIds") meetingIds: Collection<Long>): List<MeetingMemberCount>
}

// 모임별 구성원 수 집계 결과
interface MeetingMemberCount {
    val meetingId: Long
    val memberCount: Long
}

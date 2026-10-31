package com.teamani.moamoa.vote.repository

import com.teamani.moamoa.vote.entity.Vote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface VoteRepository : JpaRepository<Vote, Long> {

    fun findAllByScheduleId(scheduleId: Long): List<Vote>

    // 재제출 시 기존 응답 일괄 삭제 (INSERT보다 먼저 실행되도록 JPQL로 즉시 삭제)
    // 실행 전 변경을 반영하고 실행 후 비워 삭제된 엔티티가 남지 않게 함
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Vote v where v.scheduleId = :scheduleId and v.memberId = :memberId")
    fun deleteAllByScheduleIdAndMemberId(
        @Param("scheduleId") scheduleId: Long,
        @Param("memberId") memberId: Long
    ): Int

    // 모임 탈퇴 시 구성원의 모든 투표 응답 일괄 삭제 (영속성 컨텍스트 동기화는 위와 같음)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Vote v where v.memberId = :memberId")
    fun deleteAllByMemberId(@Param("memberId") memberId: Long): Int
}

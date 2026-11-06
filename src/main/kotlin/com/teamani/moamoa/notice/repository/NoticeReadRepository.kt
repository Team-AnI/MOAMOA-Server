package com.teamani.moamoa.notice.repository

import com.teamani.moamoa.notice.entity.NoticeRead
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NoticeReadRepository : JpaRepository<NoticeRead, Long> {

    fun findByNoticeIdAndMemberId(noticeId: Long, memberId: Long): NoticeRead?

    fun findAllByNoticeId(noticeId: Long): List<NoticeRead>

    // 공지 목록의 공지별 읽은 인원을 한 번의 집계 쿼리로 조회 (현재 모임 구성원의 기록만 집계)
    @Query(
        "select r.noticeId as noticeId, count(r) as readCount from NoticeRead r " +
            "where r.noticeId in :noticeIds " +
            "and r.memberId in (select m.id from Member m where m.meetingId = :meetingId) " +
            "group by r.noticeId",
    )
    fun countGroupByNoticeIdIn(
        @Param("meetingId") meetingId: Long,
        @Param("noticeIds") noticeIds: Collection<Long>
    ): List<NoticeReadCount>

    // 공지 삭제 시 해당 공지의 읽음 기록 일괄 삭제
    // 실행 전 변경을 반영하고 실행 후 비워 삭제된 엔티티가 남지 않게 함
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NoticeRead r where r.noticeId = :noticeId")
    fun deleteAllByNoticeId(@Param("noticeId") noticeId: Long): Int

    // 모임 탈퇴 시 구성원의 모든 읽음 기록 일괄 삭제
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NoticeRead r where r.memberId = :memberId")
    fun deleteAllByMemberId(@Param("memberId") memberId: Long): Int
}

// 공지별 읽은 인원 집계 결과
interface NoticeReadCount {
    val noticeId: Long
    val readCount: Long
}

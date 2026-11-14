package com.teamani.moamoa.user.repository

import com.teamani.moamoa.user.entity.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    // 발급 순서대로 조회 (발급 시각이 같으면 먼저 만든 토큰 먼저)
    fun findAllByUserIdOrderByCreatedAtAscIdAsc(userId: Long): List<RefreshToken>

    // 동시 요청 중 한 요청만 삭제에 성공하도록 삭제 건수를 반환
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken r where r.tokenHash = :tokenHash")
    fun deleteByTokenHash(tokenHash: String): Int

    // 로그아웃: 요청한 사용자의 토큰일 때만 삭제 (다른 사용자의 토큰이면 삭제하지 않음)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken r where r.tokenHash = :tokenHash and r.userId = :userId")
    fun deleteByTokenHashAndUserId(tokenHash: String, userId: Long): Int
}

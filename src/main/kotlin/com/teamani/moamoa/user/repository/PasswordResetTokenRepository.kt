package com.teamani.moamoa.user.repository

import com.teamani.moamoa.user.entity.PasswordResetToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, Long> {

    fun findByTokenHash(tokenHash: String): PasswordResetToken?

    // 새로 요청하면 이전 링크는 쓸 수 없음
    @Modifying(flushAutomatically = true)
    @Query("delete from PasswordResetToken t where t.userId = :userId")
    fun deleteAllByUserId(userId: Long): Int

    // 사용 전이고 만료되지 않은 토큰만 사용 처리. 같은 토큰의 동시 요청 중 한 요청만 1을 받음
    // (잠근 사용자 엔티티가 계속 관리되도록 영속성 컨텍스트는 비우지 않음)
    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now where t.tokenHash = :tokenHash and t.usedAt is null and t.expiresAt > :now")
    fun markUsed(tokenHash: String, now: OffsetDateTime): Int
}

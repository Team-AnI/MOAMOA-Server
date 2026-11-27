package com.teamani.moamoa.user.repository

import com.teamani.moamoa.user.entity.AppUser
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

interface AppUserRepository : JpaRepository<AppUser, Long> {

    fun existsByEmail(email: String): Boolean

    fun findByEmail(email: String): AppUser?

    // 토큰 발급·재발급을 사용자 단위로 직렬화 (트랜잭션이 끝날 때까지 같은 사용자의 다른 요청은 대기)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    fun findByIdForUpdate(id: Long): AppUser?

    // 로그인: 비밀번호 재설정과 같은 사용자 잠금으로 최신 비밀번호를 읽어 확인 (확인 직후 변경된 비밀번호로 발급하지 않음)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.email = :email")
    fun findByEmailForUpdate(email: String): AppUser?

    // 기준 시각 이후에 탈퇴한 같은 이메일 해시가 있는지 확인 (재가입 제한)
    fun existsByWithdrawnEmailHashAndDeletedAtAfter(withdrawnEmailHash: String, deletedAt: OffsetDateTime): Boolean

    // 재가입 제한 기간(7일)이 지난 탈퇴 이메일 해시 삭제 (기준 시각 이전에 탈퇴한 계정)
    @Modifying
    @Query("update AppUser u set u.withdrawnEmailHash = null where u.withdrawnEmailHash is not null and u.deletedAt <= :before")
    fun clearWithdrawnEmailHashes(before: OffsetDateTime): Int
}

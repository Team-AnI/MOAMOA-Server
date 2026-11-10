package com.teamani.moamoa.user.repository

import com.teamani.moamoa.user.entity.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

interface AppUserRepository : JpaRepository<AppUser, Long> {

    fun existsByEmail(email: String): Boolean

    // 기준 시각 이후에 탈퇴한 같은 이메일 해시가 있는지 확인 (재가입 제한)
    fun existsByWithdrawnEmailHashAndDeletedAtAfter(withdrawnEmailHash: String, deletedAt: OffsetDateTime): Boolean
}

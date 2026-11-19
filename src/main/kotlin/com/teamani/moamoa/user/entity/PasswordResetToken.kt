package com.teamani.moamoa.user.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

// 비밀번호 재설정 링크 토큰 (원문은 메일 링크에만 담고 해시를 저장)
@Entity
@Table(
    name = "password_reset_token",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_password_reset_token_token_hash", columnNames = ["token_hash"]),
    ],
    // 재설정 요청마다 실행되는 사용자별 이전 토큰 삭제를 전체 조회 없이 처리
    indexes = [Index(name = "idx_password_reset_token_user_id", columnList = "user_id")],
)
class PasswordResetToken(
    // 재설정 대상 사용자 ID
    @Column(nullable = false)
    val userId: Long,

    // 재설정 토큰 해시
    @Column(nullable = false, length = 255)
    val tokenHash: String,

    // 만료 일시 (발급 후 10분)
    @Column(nullable = false)
    val expiresAt: OffsetDateTime,

    // 사용 일시 (한 번만 사용 가능, 사용 전이면 NULL)
    @Column(nullable = true)
    var usedAt: OffsetDateTime? = null,

    // 발급 일시
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
)

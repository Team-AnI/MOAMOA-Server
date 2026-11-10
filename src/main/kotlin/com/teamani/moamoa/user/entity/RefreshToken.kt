package com.teamani.moamoa.user.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

// 기기마다 한 행 (토큰 원문이 아니라 해시를 저장)
@Entity
@Table(
    name = "refresh_token",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_refresh_token_token_hash", columnNames = ["token_hash"]),
    ],
)
class RefreshToken(
    // 토큰 소유자 ID
    @Column(nullable = false)
    val userId: Long,

    // 리프레시 토큰 해시
    @Column(nullable = false, length = 255)
    val tokenHash: String,

    // 만료 일시 (발급 후 30일)
    @Column(nullable = false)
    val expiresAt: OffsetDateTime,

    // 발급 일시
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
)

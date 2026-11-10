package com.teamani.moamoa.user.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

@Entity
@Table(
    name = "app_user",
    uniqueConstraints = [
        // 이메일은 가입 ID라 중복 불가
        UniqueConstraint(name = AppUser.UK_EMAIL, columnNames = ["email"]),
    ],
)
class AppUser(
    @Column(nullable = false, length = 20)
    var name: String,

    @Column(nullable = false, length = 255)
    var email: String,

    // BCrypt 해시
    @Column(nullable = false, length = 255)
    var password: String,

    // 휴대폰 번호 (선택 입력)
    @Column(nullable = true, length = 20)
    var phone: String? = null,

    // 기본 프로필 색
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var profileColor: ProfileColor = ProfileColor.LIGHT_BLUE,

    // 이용약관·개인정보 처리방침 동의 일시
    @Column(nullable = false)
    val termsAgreedAt: OffsetDateTime,

    // 가입 일시
    @Column(nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // 탈퇴 일시 (NULL이면 사용 중인 계정)
    @Column(nullable = true)
    var deletedAt: OffsetDateTime? = null,

    // 탈퇴한 이메일의 SHA-256 해시 (7일 재가입 제한 비교용)
    @Column(nullable = true, length = 64)
    var withdrawnEmailHash: String? = null,

    // 기본 키 (PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {

    companion object {
        // 동시 가입 충돌 판별에 사용하는 unique 제약 이름
        const val UK_EMAIL = "uk_app_user_email"
    }
}

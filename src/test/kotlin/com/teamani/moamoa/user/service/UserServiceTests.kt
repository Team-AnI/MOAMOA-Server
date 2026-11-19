package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.entity.AppUser
import com.teamani.moamoa.user.entity.ProfileColor
import com.teamani.moamoa.user.repository.AppUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.OffsetDateTime

class UserServiceTests : BehaviorSpec({

    val appUserRepository: AppUserRepository = mock(AppUserRepository::class.java)
    val tokenService: TokenService = mock(TokenService::class.java)
    val passwordEncoder = BCryptPasswordEncoder(4)
    val userService = UserService(appUserRepository, passwordEncoder, tokenService)

    afterEach { clearInvocations(appUserRepository, tokenService) }

    fun request(phone: String? = null) = UserSignUpRequest(
        name = "김모아",
        email = "moa@example.com",
        password = "moamoa1234",
        phone = phone,
        agreedToTerms = true
    )

    fun savedUser(id: Long = 7L) = AppUser(
        id = id,
        name = "김모아",
        email = "moa@example.com",
        password = "hashed",
        termsAgreedAt = OffsetDateTime.parse("2026-11-01T10:00:00+09:00")
    )

    Given("가입 가능한 이메일로 회원가입 요청이 주어졌을 때") {
        `when`(appUserRepository.saveAndFlush(any(AppUser::class.java))).thenReturn(savedUser())
        `when`(tokenService.issueTokens(7L)).thenReturn(IssuedTokens("access", "refresh"))

        When("signUp 메서드를 호출하면") {
            val response = userService.signUp(request(phone = "01012345678"))

            Then("비밀번호를 해시로 저장하고 기본 프로필 색과 약관 동의 일시를 넣은 뒤 토큰을 반환한다") {
                val captor = ArgumentCaptor.forClass(AppUser::class.java)
                verify(appUserRepository).saveAndFlush(captor.capture())
                val saved = captor.value

                saved.password shouldNotBe "moamoa1234"
                passwordEncoder.matches("moamoa1234", saved.password) shouldBe true
                saved.phone shouldBe "01012345678"
                saved.profileColor shouldBe ProfileColor.LIGHT_BLUE
                saved.termsAgreedAt shouldBe saved.createdAt

                response.userId shouldBe 7L
                response.accessToken shouldBe "access"
                response.refreshToken shouldBe "refresh"
            }
        }
    }

    Given("이미 가입된 이메일로 회원가입 요청이 주어졌을 때") {
        `when`(appUserRepository.existsByEmail("moa@example.com")).thenReturn(true)

        When("signUp 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> { userService.signUp(request()) }

            Then("CONFLICT 예외가 발생하고 저장과 토큰 발급은 호출되지 않는다") {
                exception.errorCode shouldBe ErrorCode.CONFLICT
                verify(appUserRepository, never()).saveAndFlush(any(AppUser::class.java))
                verify(tokenService, never()).issueTokens(anyLong())
            }
        }

        reset(appUserRepository)
    }

    Given("탈퇴 후 재가입 제한 기간의 이메일로 회원가입 요청이 주어졌을 때") {
        `when`(
            appUserRepository.existsByWithdrawnEmailHashAndDeletedAtAfter(
                anyString(), any(OffsetDateTime::class.java) ?: OffsetDateTime.now()
            )
        ).thenReturn(true)

        When("signUp 메서드를 호출하면") {
            val exception = shouldThrow<ApiException> { userService.signUp(request()) }

            Then("CONFLICT 예외가 발생하고 저장되지 않는다") {
                exception.errorCode shouldBe ErrorCode.CONFLICT
                exception.message shouldBe "탈퇴 후 7일 동안 같은 이메일로 가입할 수 없습니다."
                verify(appUserRepository, never()).saveAndFlush(any(AppUser::class.java))
            }
        }
    }
})

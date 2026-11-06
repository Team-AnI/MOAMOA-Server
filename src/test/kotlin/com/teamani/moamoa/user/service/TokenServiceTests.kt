package com.teamani.moamoa.user.service

import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.user.entity.RefreshToken
import com.teamani.moamoa.user.repository.RefreshTokenRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import java.time.Duration
import java.time.OffsetDateTime

class TokenServiceTests : BehaviorSpec({

    val jwtTokenProvider: JwtTokenProvider = mock(JwtTokenProvider::class.java)
    val refreshTokenRepository: RefreshTokenRepository = mock(RefreshTokenRepository::class.java)
    val tokenService = TokenService(jwtTokenProvider, refreshTokenRepository)

    val userId = 7L

    fun token(id: Long, expiresAt: OffsetDateTime = OffsetDateTime.now().plusDays(1)) =
        RefreshToken(id = id, userId = userId, tokenHash = "hash-$id", expiresAt = expiresAt)

    beforeEach {
        reset(jwtTokenProvider, refreshTokenRepository)
        `when`(jwtTokenProvider.createAccessToken(userId)).thenReturn("access")
    }

    Given("토큰을 발급할 때") {
        When("기존 토큰이 최대 기기 수보다 적으면") {
            Then("리프레시 토큰은 해시만 30일 만료로 저장하고, 원문과 액세스 토큰을 반환한다") {
                `when`(refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId)).thenReturn(listOf(token(1L), token(2L)))

                val tokens = tokenService.issueTokens(userId)

                val captor = ArgumentCaptor.forClass(RefreshToken::class.java)
                verify(refreshTokenRepository).save(captor.capture())
                val saved = captor.value
                saved.userId shouldBe userId
                saved.tokenHash shouldBe sha256(tokens.refreshToken)
                (Duration.between(OffsetDateTime.now().plusDays(30), saved.expiresAt).abs() < Duration.ofMinutes(1)) shouldBe true
                tokens.accessToken shouldBe "access"
                verify(refreshTokenRepository, never()).deleteAllByIdInBatch(anyList())
            }
        }

        When("이미 최대 기기 수(3개)만큼 토큰이 있으면") {
            Then("가장 오래된 토큰 1개를 삭제한 뒤 새 토큰을 저장한다") {
                `when`(refreshTokenRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId))
                    .thenReturn(listOf(token(1L), token(2L), token(3L)))

                tokenService.issueTokens(userId)

                val order = inOrder(refreshTokenRepository)
                order.verify(refreshTokenRepository).deleteAllByIdInBatch(listOf(1L))
                order.verify(refreshTokenRepository).save(any(RefreshToken::class.java))
            }
        }

        When("매번 발급하면") {
            Then("리프레시 토큰 원문이 서로 다르다") {
                val first = tokenService.issueTokens(userId).refreshToken
                val second = tokenService.issueTokens(userId).refreshToken

                (first == second) shouldBe false
            }
        }
    }

    Given("리프레시 토큰으로 재발급할 때") {
        val refreshToken = "old-refresh"
        val tokenHash = sha256(refreshToken)

        When("저장된 토큰이 없으면") {
            Then("401 UNAUTHORIZED이고 새 토큰을 저장하지 않는다") {
                val exception = shouldThrow<ApiException> { tokenService.reissueTokens(refreshToken) }

                exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                verify(refreshTokenRepository, never()).save(any(RefreshToken::class.java))
            }
        }

        When("토큰이 만료되었으면") {
            Then("401 UNAUTHORIZED이고 토큰을 삭제하거나 새로 저장하지 않는다") {
                `when`(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(token(1L, expiresAt = OffsetDateTime.now().minusMinutes(1)))

                val exception = shouldThrow<ApiException> { tokenService.reissueTokens(refreshToken) }

                exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                verify(refreshTokenRepository, never()).deleteByTokenHash(anyString())
                verify(refreshTokenRepository, never()).save(any(RefreshToken::class.java))
            }
        }

        When("다른 요청이 같은 토큰을 먼저 사용해 삭제할 토큰이 없으면") {
            Then("401 UNAUTHORIZED이고 새 토큰을 저장하지 않는다") {
                `when`(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(token(1L))
                `when`(refreshTokenRepository.deleteByTokenHash(tokenHash)).thenReturn(0)

                val exception = shouldThrow<ApiException> { tokenService.reissueTokens(refreshToken) }

                exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
                verify(refreshTokenRepository, never()).save(any(RefreshToken::class.java))
            }
        }

        When("유효한 토큰이면") {
            Then("기존 토큰을 삭제하고 같은 사용자에게 새 토큰 쌍을 발급한다") {
                `when`(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(token(1L))
                `when`(refreshTokenRepository.deleteByTokenHash(tokenHash)).thenReturn(1)

                val tokens = tokenService.reissueTokens(refreshToken)

                tokens.accessToken shouldBe "access"
                val order = inOrder(refreshTokenRepository)
                order.verify(refreshTokenRepository).deleteByTokenHash(tokenHash)
                order.verify(refreshTokenRepository).save(any(RefreshToken::class.java))
            }
        }
    }

    Given("로그아웃으로 토큰을 폐기할 때") {
        When("이미 삭제된 토큰이어도") {
            Then("예외 없이 토큰 해시로 삭제를 요청한다") {
                `when`(refreshTokenRepository.deleteByTokenHash(anyString())).thenReturn(0)

                tokenService.revokeToken("old-refresh")

                verify(refreshTokenRepository).deleteByTokenHash(sha256("old-refresh"))
            }
        }
    }
})

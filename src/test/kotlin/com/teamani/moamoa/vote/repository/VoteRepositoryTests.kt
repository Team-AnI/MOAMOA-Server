package com.teamani.moamoa.vote.repository

import com.teamani.moamoa.vote.entity.Vote
import com.teamani.moamoa.vote.entity.VoteStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class VoteRepositoryTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var voteRepository: VoteRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val deadline = OffsetDateTime.parse("2026-10-31T23:00:00+09:00")
    private val firstTime = OffsetDateTime.parse("2026-11-01T19:00:00+09:00")
    private val secondTime = OffsetDateTime.parse("2026-11-02T19:00:00+09:00")

    private fun vote(memberId: Long, scheduleId: Long, meetingTime: OffsetDateTime, status: VoteStatus) =
        Vote(memberId = memberId, scheduleId = scheduleId, meetingTime = meetingTime, status = status, endedAt = deadline)

    init {
        Given("같은 일정·구성원·후보 시간의 응답이 이미 있을 때") {
            When("같은 후보 시간으로 다시 저장하면") {
                Then("unique 제약 위반 예외가 발생한다") {
                    voteRepository.saveAndFlush(vote(500L, 8L, firstTime, VoteStatus.AVAILABLE))

                    shouldThrow<DataIntegrityViolationException> {
                        voteRepository.saveAndFlush(vote(500L, 8L, firstTime, VoteStatus.UNAVAILABLE))
                    }
                }
            }
            When("표기만 다르고 같은 순간인 후보 시간으로 저장하면") {
                Then("같은 후보 시간으로 보고 unique 제약 위반 예외가 발생한다") {
                    voteRepository.saveAndFlush(vote(510L, 8L, firstTime, VoteStatus.AVAILABLE))
                    val sameInstant = firstTime.withOffsetSameInstant(ZoneOffset.UTC)

                    shouldThrow<DataIntegrityViolationException> {
                        voteRepository.saveAndFlush(vote(510L, 8L, sameInstant, VoteStatus.UNAVAILABLE))
                    }
                }
            }
            When("다른 구성원이나 다른 후보 시간으로 저장하면") {
                Then("정상 저장된다") {
                    voteRepository.saveAndFlush(vote(520L, 8L, firstTime, VoteStatus.AVAILABLE))
                    voteRepository.saveAndFlush(vote(521L, 8L, firstTime, VoteStatus.AVAILABLE))
                    voteRepository.saveAndFlush(vote(520L, 8L, secondTime, VoteStatus.UNAVAILABLE))

                    voteRepository.findAllByScheduleId(8L).filter { it.memberId in listOf(520L, 521L) }.size shouldBe 3
                }
            }
        }

        Given("여러 일정에 투표 응답이 있을 때") {
            When("일정 ID로 조회하면") {
                Then("해당 일정의 행만 반환한다") {
                    voteRepository.saveAll(
                        listOf(
                            vote(100L, 1L, firstTime, VoteStatus.PENDING),
                            vote(100L, 1L, secondTime, VoteStatus.PENDING),
                            vote(100L, 2L, firstTime, VoteStatus.PENDING),
                        )
                    )

                    voteRepository.findAllByScheduleId(1L).map { it.meetingTime.toInstant() } shouldContainExactlyInAnyOrder
                        listOf(firstTime.toInstant(), secondTime.toInstant())
                }
            }
        }

        Given("한 일정에 여러 구성원의 응답이 있을 때") {
            When("일정·구성원으로 일괄 삭제하면") {
                Then("해당 구성원의 그 일정 응답만 삭제된다") {
                    voteRepository.saveAll(
                        listOf(
                            vote(100L, 3L, firstTime, VoteStatus.PENDING),
                            vote(200L, 3L, firstTime, VoteStatus.AVAILABLE),
                            vote(200L, 3L, secondTime, VoteStatus.UNAVAILABLE),
                            vote(200L, 4L, firstTime, VoteStatus.AVAILABLE),
                        )
                    )

                    voteRepository.deleteAllByScheduleIdAndMemberId(3L, 200L) shouldBe 2

                    voteRepository.findAllByScheduleId(3L).map { it.memberId } shouldContainExactlyInAnyOrder listOf(100L)
                    voteRepository.findAllByScheduleId(4L).map { it.memberId } shouldContainExactlyInAnyOrder listOf(200L)
                }
            }
        }

        Given("한 구성원이 여러 일정에 응답했을 때") {
            When("구성원 ID로 일괄 삭제하면") {
                Then("모든 일정에서 그 구성원의 응답만 삭제된다") {
                    voteRepository.saveAll(
                        listOf(
                            vote(300L, 5L, firstTime, VoteStatus.AVAILABLE),
                            vote(300L, 6L, firstTime, VoteStatus.UNAVAILABLE),
                            vote(100L, 5L, firstTime, VoteStatus.PENDING),
                        )
                    )

                    voteRepository.deleteAllByMemberId(300L) shouldBe 2

                    voteRepository.findAllByScheduleId(5L).map { it.memberId } shouldContainExactlyInAnyOrder listOf(100L)
                    voteRepository.findAllByScheduleId(6L).map { it.memberId } shouldContainExactlyInAnyOrder emptyList()
                }
            }
        }

        Given("응답 상태와 일시를 저장할 때") {
            When("저장하고 다시 조회하면") {
                Then("상태 값과 같은 순간의 일시로 조회된다") {
                    voteRepository.saveAndFlush(vote(400L, 7L, firstTime, VoteStatus.AVAILABLE))
                    voteRepository.saveAndFlush(vote(400L, 7L, secondTime, VoteStatus.UNAVAILABLE))
                    // 영속성 컨텍스트를 비워 DB에서 다시 읽은 값으로 확인
                    entityManager.clear()

                    val votes = voteRepository.findAllByScheduleId(7L)
                    votes.map { it.status } shouldContainExactlyInAnyOrder listOf(VoteStatus.AVAILABLE, VoteStatus.UNAVAILABLE)
                    votes.map { it.endedAt.toInstant() }.distinct() shouldContainExactlyInAnyOrder listOf(deadline.toInstant())
                }
            }
        }
    }
}

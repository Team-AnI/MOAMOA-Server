package com.teamani.moamoa.attendance.repository

import com.teamani.moamoa.attendance.entity.Attendance
import com.teamani.moamoa.attendance.entity.AttendanceStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import jakarta.persistence.EntityManager
import org.hibernate.exception.ConstraintViolationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AttendanceRepositoryTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var attendanceRepository: AttendanceRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val firstCheckedAt = OffsetDateTime.parse("2026-10-20T19:00:00+09:00")
    private val changedAt = OffsetDateTime.parse("2026-10-20T19:30:00+09:00")

    private fun attendance(memberId: Long, scheduleId: Long, status: AttendanceStatus) =
        Attendance(memberId = memberId, scheduleId = scheduleId, status = status, createdAt = firstCheckedAt)

    init {
        Given("여러 일정에 출석 기록이 있을 때") {
            When("일정 ID로 조회하면") {
                Then("해당 일정의 기록만 반환한다") {
                    attendanceRepository.saveAll(
                        listOf(
                            attendance(100L, 1L, AttendanceStatus.PRESENT),
                            attendance(200L, 1L, AttendanceStatus.LATE),
                            attendance(100L, 2L, AttendanceStatus.ABSENT),
                        )
                    )

                    attendanceRepository.findAllByScheduleId(1L).map { it.memberId to it.status } shouldContainExactlyInAnyOrder
                        listOf(100L to AttendanceStatus.PRESENT, 200L to AttendanceStatus.LATE)
                }
            }
        }

        Given("한 구성원이 여러 일정에 출석 기록이 있을 때") {
            When("구성원 ID로 일괄 삭제하면") {
                Then("모든 일정에서 그 구성원의 기록만 삭제된다") {
                    attendanceRepository.saveAll(
                        listOf(
                            attendance(300L, 3L, AttendanceStatus.PRESENT),
                            attendance(300L, 4L, AttendanceStatus.ABSENT),
                            attendance(400L, 3L, AttendanceStatus.LATE),
                        )
                    )

                    attendanceRepository.deleteAllByMemberId(300L) shouldBe 2

                    attendanceRepository.findAllByScheduleId(3L).map { it.memberId } shouldContainExactlyInAnyOrder listOf(400L)
                    attendanceRepository.findAllByScheduleId(4L).map { it.memberId } shouldContainExactlyInAnyOrder emptyList()
                }
            }
            When("영속성 컨텍스트에 올라온 기록을 일괄 삭제한 뒤 id로 다시 조회하면") {
                Then("벌크 삭제 후 영속성 컨텍스트가 비워져 삭제된 기록이 조회되지 않는다") {
                    val saved = attendanceRepository.saveAndFlush(attendance(800L, 8L, AttendanceStatus.PRESENT))

                    attendanceRepository.deleteAllByMemberId(800L) shouldBe 1

                    attendanceRepository.findById(checkNotNull(saved.id)).isPresent shouldBe false
                }
            }
        }

        Given("출석 기록의 상태를 바꿀 때") {
            When("다른 상태로 바꿔 저장하고 다시 조회하면") {
                Then("상태와 변경 일시만 바뀌고 처음 체크한 일시는 유지된다") {
                    val saved = attendanceRepository.saveAndFlush(attendance(600L, 6L, AttendanceStatus.PRESENT))

                    saved.changeStatus(AttendanceStatus.LATE, changedAt)
                    attendanceRepository.saveAndFlush(saved)
                    // 영속성 컨텍스트를 비워 DB에서 다시 읽은 값으로 확인
                    entityManager.clear()

                    val reloaded = attendanceRepository.findAllByScheduleId(6L).single()
                    reloaded.status shouldBe AttendanceStatus.LATE
                    reloaded.createdAt.toInstant() shouldBe firstCheckedAt.toInstant()
                    reloaded.updatedAt.toInstant() shouldBe changedAt.toInstant()
                }
            }
            When("같은 상태로 바꿔 저장하면") {
                Then("변경 일시가 처음 체크한 일시 그대로 유지된다") {
                    val saved = attendanceRepository.saveAndFlush(attendance(700L, 7L, AttendanceStatus.ABSENT))

                    saved.changeStatus(AttendanceStatus.ABSENT, changedAt)
                    attendanceRepository.saveAndFlush(saved)
                    entityManager.clear()

                    val reloaded = attendanceRepository.findAllByScheduleId(7L).single()
                    reloaded.status shouldBe AttendanceStatus.ABSENT
                    reloaded.updatedAt.toInstant() shouldBe firstCheckedAt.toInstant()
                }
            }
        }

        Given("같은 일정에 같은 구성원의 출석 기록이 이미 있을 때") {
            When("같은 일정·구성원으로 기록을 저장하면") {
                Then("출석 unique 제약 이름이 담긴 무결성 위반 예외가 발생한다") {
                    attendanceRepository.saveAndFlush(attendance(500L, 5L, AttendanceStatus.PRESENT))

                    val exception = shouldThrow<DataIntegrityViolationException> {
                        attendanceRepository.saveAndFlush(attendance(500L, 5L, AttendanceStatus.LATE))
                    }

                    // Service의 409 변환은 이 제약 이름으로 판별
                    val violation = (exception.cause as? ConstraintViolationException).shouldNotBeNull()
                    violation.constraintName.shouldNotBeNull() shouldContainIgnoringCase Attendance.UK_MEMBER_ID_SCHEDULE_ID
                }
            }
        }
    }
}

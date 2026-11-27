package com.teamani.moamoa.user.scheduler

import com.teamani.moamoa.user.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

// 하루 한 번 재가입 제한 기간(7일)이 지난 탈퇴 이메일 해시 삭제 (PRD 8.6.7)
// 테스트에서는 moamoa.scheduler.enabled=false로 끔
@Component
@ConditionalOnProperty(name = ["moamoa.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class WithdrawnEmailHashScheduler(
    private val userService: UserService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    fun clearExpiredWithdrawnEmailHashes() {
        val cleared = userService.clearExpiredWithdrawnEmailHashes(OffsetDateTime.now())
        log.info("재가입 제한 기간이 지난 탈퇴 이메일 해시 삭제 count={}", cleared)
    }
}

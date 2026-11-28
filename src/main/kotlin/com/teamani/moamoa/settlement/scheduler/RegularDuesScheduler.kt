package com.teamani.moamoa.settlement.scheduler

import com.teamani.moamoa.settlement.service.RegularDuesBillingService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

// 1분마다 청구할 정기 회비를 찾아 회차 정산 생성 (sendTime이 분 단위라 1분 간격)
// 테스트에서는 moamoa.scheduler.enabled=false로 꺼서 테스트 데이터에 정산이 생기지 않게 함
@Component
@EnableScheduling
@ConditionalOnProperty(name = ["moamoa.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class RegularDuesScheduler(
    private val regularDuesBillingService: RegularDuesBillingService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    fun billRegularDues() {
        val now = OffsetDateTime.now()

        regularDuesBillingService.findDueRegularDuesIds(now).forEach { regularDuesId ->
            // 한 정기 회비의 실패가 다른 회비 청구를 막지 않도록 건별 처리
            runCatching { regularDuesBillingService.bill(regularDuesId, now) }
                .onFailure { log.error("정기 회비 자동 청구 실패 regularDuesId={}", regularDuesId, it) }
        }
    }
}

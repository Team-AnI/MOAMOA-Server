package com.teamani.moamoa.settlement.repository

import com.teamani.moamoa.settlement.entity.SettlementMember
import org.springframework.data.jpa.repository.JpaRepository

interface SettlementMemberRepository : JpaRepository<SettlementMember, Long> {

    // 정산 목록의 대상 인원·내 납부 정보를 정산마다 따로 조회하지 않도록 한 번에 조회
    fun findAllBySettlementIdIn(settlementIds: Collection<Long>): List<SettlementMember>

    fun findAllBySettlementIdOrderByIdAsc(settlementId: Long): List<SettlementMember>

    // 본인 납부 항목 (없으면 정산 대상이 아님)
    fun findBySettlementIdAndMemberId(settlementId: Long, memberId: Long): SettlementMember?

    // 다른 정산의 납부 항목은 조회되지 않도록 정산 ID까지 함께 조건으로 사용
    fun findByIdAndSettlementId(id: Long, settlementId: Long): SettlementMember?
}

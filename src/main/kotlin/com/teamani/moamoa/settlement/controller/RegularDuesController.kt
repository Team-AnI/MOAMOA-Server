package com.teamani.moamoa.settlement.controller

import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.settlement.dto.RegularDuesCreateRequest
import com.teamani.moamoa.settlement.dto.RegularDuesCreateResponse
import com.teamani.moamoa.settlement.dto.RegularDuesDeleteResponse
import com.teamani.moamoa.settlement.dto.RegularDuesListResponse
import com.teamani.moamoa.settlement.dto.RegularDuesResponse
import com.teamani.moamoa.settlement.dto.RegularDuesUpdateRequest
import com.teamani.moamoa.settlement.service.RegularDuesService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/meetings/{meetingId}/regular-dues")
class RegularDuesController(
    private val regularDuesService: RegularDuesService
) {

    @PostMapping
    fun createRegularDues(
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: RegularDuesCreateRequest
    ): ResponseEntity<CommonResponse<RegularDuesCreateResponse>> {
        val currentMemberId = 1L // TODO: 인증·구성원 연동 시 실제 구성원 ID로 교체 (#50)

        val response = regularDuesService.createRegularDues(meetingId, currentMemberId, request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }

    @GetMapping
    fun getRegularDuesList(@PathVariable meetingId: Long): ResponseEntity<CommonResponse<RegularDuesListResponse>> {
        val currentMemberId = 1L // TODO: 인증·구성원 연동 시 실제 구성원 ID로 교체 (#50)

        return ResponseEntity.ok(CommonResponse(data = regularDuesService.getRegularDuesList(meetingId, currentMemberId)))
    }

    @PatchMapping("/{regularDuesId}")
    fun updateRegularDues(
        @PathVariable meetingId: Long,
        @PathVariable regularDuesId: Long,
        @Valid @RequestBody request: RegularDuesUpdateRequest
    ): ResponseEntity<CommonResponse<RegularDuesResponse>> {
        val currentMemberId = 1L // TODO: 인증·구성원 연동 시 실제 구성원 ID로 교체 (#50)

        return ResponseEntity.ok(
            CommonResponse(data = regularDuesService.updateRegularDues(meetingId, currentMemberId, regularDuesId, request))
        )
    }

    @DeleteMapping("/{regularDuesId}")
    fun deleteRegularDues(
        @PathVariable meetingId: Long,
        @PathVariable regularDuesId: Long
    ): ResponseEntity<CommonResponse<RegularDuesDeleteResponse>> {
        val currentMemberId = 1L // TODO: 인증·구성원 연동 시 실제 구성원 ID로 교체 (#50)

        return ResponseEntity.ok(
            CommonResponse(data = regularDuesService.deleteRegularDues(meetingId, currentMemberId, regularDuesId))
        )
    }
}

package com.teamani.moamoa.settlement.controller

import com.teamani.moamoa.global.auth.CurrentUserId
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
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: RegularDuesCreateRequest
    ): ResponseEntity<CommonResponse<RegularDuesCreateResponse>> {
        val response = regularDuesService.createRegularDues(meetingId, currentUserId, request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }

    @GetMapping
    fun getRegularDuesList(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long
    ): ResponseEntity<CommonResponse<RegularDuesListResponse>> {
        return ResponseEntity.ok(CommonResponse(data = regularDuesService.getRegularDuesList(meetingId, currentUserId)))
    }

    @PatchMapping("/{regularDuesId}")
    fun updateRegularDues(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable regularDuesId: Long,
        @Valid @RequestBody request: RegularDuesUpdateRequest
    ): ResponseEntity<CommonResponse<RegularDuesResponse>> {
        return ResponseEntity.ok(
            CommonResponse(data = regularDuesService.updateRegularDues(meetingId, currentUserId, regularDuesId, request))
        )
    }

    @DeleteMapping("/{regularDuesId}")
    fun deleteRegularDues(
        @CurrentUserId currentUserId: Long,
        @PathVariable meetingId: Long,
        @PathVariable regularDuesId: Long
    ): ResponseEntity<CommonResponse<RegularDuesDeleteResponse>> {
        return ResponseEntity.ok(
            CommonResponse(data = regularDuesService.deleteRegularDues(meetingId, currentUserId, regularDuesId))
        )
    }
}

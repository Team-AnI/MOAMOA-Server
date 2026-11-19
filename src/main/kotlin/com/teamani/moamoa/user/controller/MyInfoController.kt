package com.teamani.moamoa.user.controller

import com.teamani.moamoa.global.auth.CurrentUserId
import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.user.dto.MyInfoResponse
import com.teamani.moamoa.user.dto.MyInfoUpdateRequest
import com.teamani.moamoa.user.service.UserService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/me")
class MyInfoController(
    private val userService: UserService
) {

    @GetMapping
    fun getMyInfo(@CurrentUserId currentUserId: Long): ResponseEntity<CommonResponse<MyInfoResponse>> =
        ResponseEntity.ok(CommonResponse(data = userService.getMyInfo(currentUserId)))

    @PatchMapping
    fun updateMyInfo(
        @CurrentUserId currentUserId: Long,
        @Valid @RequestBody request: MyInfoUpdateRequest
    ): ResponseEntity<CommonResponse<MyInfoResponse>> =
        ResponseEntity.ok(CommonResponse(data = userService.updateMyInfo(currentUserId, request)))
}

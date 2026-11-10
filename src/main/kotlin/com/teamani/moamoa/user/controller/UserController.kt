package com.teamani.moamoa.user.controller

import com.teamani.moamoa.global.response.CommonResponse
import com.teamani.moamoa.user.dto.UserSignUpRequest
import com.teamani.moamoa.user.dto.UserSignUpResponse
import com.teamani.moamoa.user.service.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/users")
class UserController(
    private val userService: UserService
) {

    @PostMapping
    fun signUp(@Valid @RequestBody request: UserSignUpRequest): ResponseEntity<CommonResponse<UserSignUpResponse>> {
        val response = userService.signUp(request)

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse(data = response))
    }
}

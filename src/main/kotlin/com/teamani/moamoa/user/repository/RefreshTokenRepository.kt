package com.teamani.moamoa.user.repository

import com.teamani.moamoa.user.entity.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long>

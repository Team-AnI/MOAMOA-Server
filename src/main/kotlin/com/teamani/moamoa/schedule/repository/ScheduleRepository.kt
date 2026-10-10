package com.teamani.moamoa.schedule.repository

import com.teamani.moamoa.schedule.entity.Schedule
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleRepository : JpaRepository<Schedule, Long>

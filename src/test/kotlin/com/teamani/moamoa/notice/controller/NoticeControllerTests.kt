package com.teamani.moamoa.notice.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.dto.NoticeCreateResponse
import com.teamani.moamoa.notice.dto.NoticeDeleteResponse
import com.teamani.moamoa.notice.dto.NoticeDetailResponse
import com.teamani.moamoa.notice.dto.NoticeListResponse
import com.teamani.moamoa.notice.dto.NoticeReadMemberResponse
import com.teamani.moamoa.notice.dto.NoticeReadResponse
import com.teamani.moamoa.notice.dto.NoticeReadStatusResponse
import com.teamani.moamoa.notice.dto.NoticeSummaryResponse
import com.teamani.moamoa.notice.dto.NoticeUnreadMemberResponse
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.user.entity.ProfileColor
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.OffsetDateTime

class NoticeControllerTests : BehaviorSpec({

    val noticeService: NoticeService = mock(NoticeService::class.java)
    val noticeController = NoticeController(noticeService)
    val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(noticeController).build()
    val objectMapper = ObjectMapper()

    Given("유효한 공지사항 작성 요청(POST /v1/meetings/{meetingId}/notices)이 주어졌을 때") {
        val meetingId = 1L
        val request = NoticeCreateRequest(
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다."
        )
        val mockResponse = NoticeCreateResponse(
            noticeId = 30L,
            title = "10월 회비 안내"
        )

        `when`(noticeService.createNotice(meetingId, 1L, request)).thenReturn(mockResponse)

        When("API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/$meetingId/notices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )

            Then("201 Created 응답과 공통 Envelope 포맷이 반환된다") {
                resultActions
                    .andExpect(status().isCreated)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.data.title").value("10월 회비 안내"))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }
    Given("제목이 50자를 초과하거나 내용이 빈 공지 작성 요청이 주어졌을 때") {
        val meetingId = 1L
        val longTitleRequest = NoticeCreateRequest(title = "a".repeat(51), content = "내용")
        val blankContentRequest = NoticeCreateRequest(title = "제목", content = "   ")

        When("50자를 초과한 제목으로 작성 API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/$meetingId/notices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(longTitleRequest))
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }

        When("공백 내용으로 작성 API를 호출하면") {
            val resultActions = mockMvc.perform(
                post("/v1/meetings/$meetingId/notices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(blankContentRequest))
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }
    }

    Given("공지사항 삭제 요청(DELETE)이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val mockResponse = NoticeDeleteResponse(noticeId = noticeId)

        `when`(noticeService.deleteNotice(meetingId, noticeId, 1L)).thenReturn(mockResponse)

        When("삭제 API를 호출하면") {
            val resultActions = mockMvc.perform(
                delete("/v1/meetings/$meetingId/notices/$noticeId")
            )

            Then("200 OK 응답과 Envelope 포맷이 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("특정 공지사항 상세 조회 요청(GET)이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val now = OffsetDateTime.now()
        val mockResponse = NoticeDetailResponse(
            noticeId = noticeId,
            title = "10월 회비 안내",
            content = "10월 회비는 10일까지 납부 부탁드립니다.",
            settlementId = 40L,
            createdAt = now
        )

        `when`(noticeService.getNoticeDetail(meetingId, noticeId, 1L)).thenReturn(mockResponse)

        When("상세 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices/$noticeId")
            )

            Then("200 OK 응답과 상세 데이터 규격이 Envelope 형식으로 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.data.title").value("10월 회비 안내"))
                    .andExpect(jsonPath("$.data.content").value("10월 회비는 10일까지 납부 부탁드립니다."))
                    .andExpect(jsonPath("$.data.settlementId").value(40L))
                    .andExpect(jsonPath("$.data.createdAt").exists())
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("공지사항 목록 조회 요청(GET)이 주어졌을 때") {
        val meetingId = 1L
        val page = 0
        val size = 20
        val now = OffsetDateTime.now()
        val mockResponse = NoticeListResponse(
            notices = listOf(
                NoticeSummaryResponse(
                    noticeId = 30L,
                    title = "10월 회비 안내",
                    readCount = 6,
                    totalCount = 8,
                    createdAt = now
                )
            ),
            page = 0,
            size = 20,
            hasNext = false
        )

        `when`(noticeService.getNotices(meetingId, page, size, 1L)).thenReturn(mockResponse)

        When("목록 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices")
                    .param("page", "0")
                    .param("size", "20")
            )

            Then("200 OK 응답과 notices 배열 및 페이징 정보가 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.notices[0].noticeId").value(30L))
                    .andExpect(jsonPath("$.data.notices[0].title").value("10월 회비 안내"))
                    .andExpect(jsonPath("$.data.notices[0].readCount").value(6))
                    .andExpect(jsonPath("$.data.notices[0].totalCount").value(8))
                    .andExpect(jsonPath("$.data.notices[0].createdAt").exists())
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.timestamp").exists())
            }
        }
    }

    Given("page가 음수이거나 size가 허용 범위를 벗어난 목록 조회 요청이 주어졌을 때") {
        val meetingId = 1L

        When("음수 page로 목록 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices")
                    .param("page", "-1")
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }

        When("100을 초과한 size로 목록 조회 API를 호출하면") {
            val resultActions = mockMvc.perform(
                get("/v1/meetings/$meetingId/notices")
                    .param("size", "101")
            )

            Then("400 Bad Request를 반환한다") {
                resultActions.andExpect(status().isBadRequest)
            }
        }
    }

    Given("공지 읽음 처리 요청(PUT .../reads/me)이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val readAt = OffsetDateTime.parse("2026-10-07T10:05:00+09:00")

        `when`(noticeService.readNotice(meetingId, noticeId, 1L)).thenReturn(NoticeReadResponse(noticeId, readAt))

        When("읽음 처리 API를 호출하면") {
            val resultActions = mockMvc.perform(put("/v1/meetings/$meetingId/notices/$noticeId/reads/me"))

            Then("200 OK와 공지 ID, 처음 읽은 시각이 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.data.readAt").exists())
                    .andExpect(jsonPath("$.error").doesNotExist())
            }
        }
    }

    Given("공지 읽음 현황 조회 요청(GET .../reads)이 주어졌을 때") {
        val meetingId = 1L
        val noticeId = 30L
        val mockResponse = NoticeReadStatusResponse(
            noticeId = noticeId,
            readCount = 1,
            totalCount = 2,
            readMembers = listOf(
                NoticeReadMemberResponse(3L, "김모아", ProfileColor.LIGHT_BLUE, OffsetDateTime.parse("2026-10-07T10:00:00+09:00"))
            ),
            unreadMembers = listOf(NoticeUnreadMemberResponse(7L, "박모아", ProfileColor.GRAY))
        )

        `when`(noticeService.getNoticeReads(meetingId, noticeId, 1L)).thenReturn(mockResponse)

        When("읽음 현황 API를 호출하면") {
            val resultActions = mockMvc.perform(get("/v1/meetings/$meetingId/notices/$noticeId/reads"))

            Then("200 OK와 읽은 인원·전체 인원·읽은 구성원·안 읽은 구성원이 반환된다") {
                resultActions
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.noticeId").value(30L))
                    .andExpect(jsonPath("$.data.readCount").value(1))
                    .andExpect(jsonPath("$.data.totalCount").value(2))
                    .andExpect(jsonPath("$.data.readMembers[0].memberId").value(3L))
                    .andExpect(jsonPath("$.data.readMembers[0].userName").value("김모아"))
                    .andExpect(jsonPath("$.data.readMembers[0].profileColor").value("LIGHT_BLUE"))
                    .andExpect(jsonPath("$.data.readMembers[0].readAt").exists())
                    .andExpect(jsonPath("$.data.unreadMembers[0].memberId").value(7L))
                    .andExpect(jsonPath("$.data.unreadMembers[0].profileColor").value("GRAY"))
            }
        }
    }
})

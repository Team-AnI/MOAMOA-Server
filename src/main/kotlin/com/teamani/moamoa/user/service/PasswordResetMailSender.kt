package com.teamani.moamoa.user.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 재설정 토큰이 저장(커밋)된 뒤 별도 스레드에서 메일 발송. 실패해도 응답에는 영향 없이 로그만 남김 (사용자는 다시 요청)
@Component
class PasswordResetMailSender(
    private val mailSender: JavaMailSender,
    @Value("\${moamoa.password-reset.mail-from}") private val mailFrom: String,
    @Value("\${moamoa.password-reset.web-url}") private val webUrl: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun send(event: PasswordResetMailEvent) {
        val message = SimpleMailMessage().apply {
            from = mailFrom
            setTo(event.email)
            subject = "[MOAMOA] 비밀번호 재설정 안내"
            text = "아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 10분 동안 한 번만 사용할 수 있습니다.\n\n" +
                "${webUrl.trimEnd('/')}/reset-password?token=${event.token}\n\n" +
                "비밀번호 재설정을 요청하지 않았다면 이 메일을 무시해 주세요."
        }
        try {
            mailSender.send(message)
        } catch (ex: MailException) {
            // 이메일·토큰은 로그에 남기지 않음. SMTP 거절 사유에 수신자 주소가 들어갈 수 있어 예외 메시지·스택도 남기지 않고 종류만 기록
            log.error("비밀번호 재설정 메일 발송 실패 userId={}, error={}", event.userId, ex.javaClass.simpleName)
        }
    }
}

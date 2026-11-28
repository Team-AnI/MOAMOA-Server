package com.teamani.moamoa.global.exception

import com.teamani.moamoa.global.response.ErrorResponse
import com.teamani.moamoa.global.response.ValidationDetail
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.method.ParameterErrors
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingRequestValueException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(
        ex: ApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ex.errorCode, ex, request)
        return ResponseEntity.status(ex.errorCode.status)
            .body(ErrorResponseFactory.of(ex.errorCode, ex.message, ex.details))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.VALIDATION_ERROR, ex, request)
        val details = ex.bindingResult.fieldErrors.map {
            ValidationDetail(
                field = it.field,
                reason = it.defaultMessage.orEmpty(),
                rejectedValue = maskSensitive(it.field, it.rejectedValue),
            )
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status)
            .body(ErrorResponseFactory.of(ErrorCode.VALIDATION_ERROR, details = details))
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidation(
        ex: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.VALIDATION_ERROR, ex, request)
        // 요청 본문 검증 오류는 본문 전체가 아닌 필드 단위로 변환
        val details = ex.parameterValidationResults.flatMap { result ->
            if (result is ParameterErrors) {
                result.fieldErrors.map {
                    ValidationDetail(
                        field = it.field,
                        reason = it.defaultMessage.orEmpty(),
                        rejectedValue = maskSensitive(it.field, it.rejectedValue),
                    )
                }
            } else {
                val field = result.methodParameter.parameterName.orEmpty()
                result.resolvableErrors.map {
                    ValidationDetail(
                        field = field,
                        reason = it.defaultMessage.orEmpty(),
                        rejectedValue = maskSensitive(field, result.argument),
                    )
                }
            }
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status)
            .body(ErrorResponseFactory.of(ErrorCode.VALIDATION_ERROR, details = details))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.VALIDATION_ERROR, ex, request)
        val details = ex.constraintViolations.map {
            val field = it.propertyPath.lastOrNull()?.name.orEmpty()
            ValidationDetail(
                field = field,
                reason = it.message,
                rejectedValue = maskSensitive(field, it.invalidValue),
            )
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status)
            .body(ErrorResponseFactory.of(ErrorCode.VALIDATION_ERROR, details = details))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.JSON_PARSE_ERROR, ex, request)
        return ResponseEntity.status(ErrorCode.JSON_PARSE_ERROR.status)
            .body(ErrorResponseFactory.of(ErrorCode.JSON_PARSE_ERROR))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.INPUT_ERROR, ex, request)
        return ResponseEntity.status(ErrorCode.INPUT_ERROR.status)
            .body(ErrorResponseFactory.of(ErrorCode.INPUT_ERROR))
    }

    @ExceptionHandler(MissingServletRequestParameterException::class, MissingRequestHeaderException::class)
    fun handleMissingValue(
        ex: MissingRequestValueException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.MISSING_REQUIRED_VALUE, ex, request)
        return ResponseEntity.status(ErrorCode.MISSING_REQUIRED_VALUE.status)
            .body(ErrorResponseFactory.of(ErrorCode.MISSING_REQUIRED_VALUE))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(
        ex: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.NOT_FOUND, ex, request)
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status)
            .body(ErrorResponseFactory.of(ErrorCode.NOT_FOUND))
    }

    // 허용 메서드 안내를 위해 Allow 헤더 포함
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(
        ex: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.METHOD_NOT_ALLOWED, ex, request)
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status)
            .headers(ex.headers)
            .body(ErrorResponseFactory.of(ErrorCode.METHOD_NOT_ALLOWED))
    }

    // 지원 형식 안내를 위해 Accept 헤더 포함
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(
        ex: HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ex, request)
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status)
            .headers(ex.headers)
            .body(ErrorResponseFactory.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logError(ErrorCode.INTERNAL_ERROR, ex, request)
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status)
            .body(ErrorResponseFactory.of(ErrorCode.INTERNAL_ERROR))
    }

    // 4xx는 warn, 5xx는 stack trace와 함께 error 로그 기록
    // 요청값은 민감 정보 포함 가능성으로 로그 제외
    private fun logError(errorCode: ErrorCode, ex: Exception, request: HttpServletRequest) {
        val status = errorCode.status.value()
        if (errorCode.status.is5xxServerError) {
            log.error(LOG_FORMAT, status, errorCode.code, request.method, request.requestURI, ex.javaClass.simpleName, ex)
        } else {
            log.warn(LOG_FORMAT, status, errorCode.code, request.method, request.requestURI, ex.javaClass.simpleName)
        }
    }

    // 비밀번호·토큰·개인정보 등 민감 필드는 입력값 대신 null 반환
    private fun maskSensitive(field: String, value: Any?): Any? =
        if (SENSITIVE_FIELD_KEYWORDS.any { field.contains(it, ignoreCase = true) }) null else value

    companion object {
        private const val LOG_FORMAT = "[{} {}] {} {} - {}"
        private val SENSITIVE_FIELD_KEYWORDS = listOf("password", "token", "secret", "email", "phone")
    }
}

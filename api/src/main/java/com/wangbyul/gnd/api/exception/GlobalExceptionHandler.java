package com.wangbyul.gnd.api.exception;

import com.wangbyul.gnd.core.dto.ErrorDto;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
/**
 * GlobalExceptionHandler 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String msg = fieldError == null ? "validation error" : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorDto> handleConstraint(ConstraintViolationException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    public ResponseEntity<ErrorDto> handleBadRequest(Exception e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorDto> handleRequestParameter(Exception e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorDto> handleNotFound(EntityNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorDto> handleDataAccess(DataAccessException e) {
        String rootMessage = rootCauseMessage(e);
        if (isSchemaMismatch(rootMessage)) {
            log.error("schema mismatch detected trace_id={} message={}", traceId(), rootMessage, e);
            return build(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "SCHEMA_MISMATCH",
                    "데이터 스키마 불일치로 분석을 완료하지 못했습니다. 관리자 점검이 필요합니다.");
        }
        log.error("data access failure trace_id={} message={}", traceId(), rootMessage, e);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "DATA_ACCESS_ERROR",
                "데이터 조회 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleFallback(Exception e) {
        String rootMessage = rootCauseMessage(e);
        if (isSchemaMismatch(rootMessage)) {
            log.error("schema mismatch fallback trace_id={} message={}", traceId(), rootMessage, e);
            return build(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "SCHEMA_MISMATCH",
                    "데이터 스키마 불일치로 분석을 완료하지 못했습니다. 관리자 점검이 필요합니다.");
        }
        log.error("internal error trace_id={} message={}", traceId(), rootMessage, e);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "내부 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private ResponseEntity<ErrorDto> build(HttpStatus status, String code, String message) {
        ErrorDto dto = ErrorDto.builder()
                .code(code)
                .message(message)
                .traceId(traceId())
                .build();
        return ResponseEntity.status(status).body(dto);
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private boolean isSchemaMismatch(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean missingColumn =
                value.contains("column")
                        && (value.contains("not found")
                        || value.contains("does not exist")
                        || value.contains("unknown column"));
        boolean missingTable =
                value.contains("table")
                        && (value.contains("not found")
                        || value.contains("does not exist")
                        || value.contains("not exist"));
        return missingColumn || missingTable;
    }
}

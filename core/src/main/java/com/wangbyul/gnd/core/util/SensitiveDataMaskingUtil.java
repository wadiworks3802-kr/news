package com.wangbyul.gnd.core.util;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 외부 API 요청/응답의 민감정보를 마스킹하는 공통 유틸리티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Component
public class SensitiveDataMaskingUtil {

    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"(?:token|access_token|refresh_token|password|secret|api[_-]?key|authorization)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[a-z0-9\\-._~+/=]+");

    /**
     * JSON/문자열 형태 페이로드에서 토큰/키/패스워드를 마스킹한다.
     */
    public String maskPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        String masked = JSON_SECRET_PATTERN.matcher(payload).replaceAll("$1***$3");
        return BEARER_PATTERN.matcher(masked).replaceAll("Bearer ***");
    }

    /**
     * 에러코드를 영숫자/일부 기호만 남기고 정리한다.
     */
    public String sanitizeErrorCode(String errorCode, int maxLength) {
        if (errorCode == null || errorCode.isBlank()) {
            return null;
        }
        String normalized = errorCode.replaceAll("[^A-Za-z0-9_\\-.:]", "");
        int safeLength = Math.max(1, maxLength);
        return normalized.length() > safeLength ? normalized.substring(0, safeLength) : normalized;
    }

    public String sanitizeErrorCode(String errorCode) {
        return sanitizeErrorCode(errorCode, 80);
    }
}

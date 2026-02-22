package com.wangbyul.gnd.core.market.dto;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Provider 호출 결과(표준 DTO + 감사 메타데이터) 래퍼.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record MarketProviderFetchResult<T>(
        String providerName,
        String apiName,
        List<T> items,
        boolean success,
        Integer httpStatus,
        OffsetDateTime requestTimeUtc,
        OffsetDateTime responseTimeUtc,
        String samplePayloadJson,
        String errorCode,
        Map<String, Object> meta) {

    public MarketProviderFetchResult {
        items = items == null ? List.of() : List.copyOf(items);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public boolean emptyResponse() {
        return items == null || items.isEmpty();
    }

    public int recordCount() {
        return items == null ? 0 : items.size();
    }

    public long latencyMs() {
        if (requestTimeUtc == null || responseTimeUtc == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(requestTimeUtc, responseTimeUtc).toMillis());
    }

    public static <T> MarketProviderFetchResult<T> success(
            String providerName,
            String apiName,
            List<T> items,
            Integer httpStatus,
            OffsetDateTime requestTimeUtc,
            OffsetDateTime responseTimeUtc,
            String samplePayloadJson,
            Map<String, Object> meta) {
        return new MarketProviderFetchResult<>(
                providerName,
                apiName,
                items,
                true,
                httpStatus,
                requestTimeUtc,
                responseTimeUtc,
                samplePayloadJson,
                null,
                meta);
    }

    public static <T> MarketProviderFetchResult<T> failure(
            String providerName,
            String apiName,
            Integer httpStatus,
            OffsetDateTime requestTimeUtc,
            OffsetDateTime responseTimeUtc,
            String samplePayloadJson,
            String errorCode,
            Map<String, Object> meta) {
        return new MarketProviderFetchResult<>(
                providerName,
                apiName,
                List.of(),
                false,
                httpStatus,
                requestTimeUtc,
                responseTimeUtc,
                samplePayloadJson,
                errorCode,
                meta);
    }
}

package com.wangbyul.gnd.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.ApiResponseAuditEntity;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.JobStatus;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketProviderJobEntity;
import com.wangbyul.gnd.core.domain.MarketProviderJobType;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.market.MarketDataProvider;
import com.wangbyul.gnd.core.market.dto.MarketPriceBarDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderFetchResult;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthDto;
import com.wangbyul.gnd.core.market.dto.MarketQuoteDto;
import com.wangbyul.gnd.core.market.provider.MarketDataProviderRouter;
import com.wangbyul.gnd.core.repository.ApiResponseAuditRepository;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketProviderJobRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.util.SensitiveDataMaskingUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시장 데이터 Provider 수집 실행/정규화/저장/감사 로그 서비스를 담당한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 목적:
 * - Provider 추상화 계층과 실제 저장 경로를 연결
 * - 실패/빈응답/지연/trace_id를 market_provider_job + api_response_audit에 기록
 * - Mock provider만으로도 end-to-end 검증 가능하도록 구성
 */
@Service
@RequiredArgsConstructor
public class MarketDataCollectionService {

    private final AssetUniverseRepository assetUniverseRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final MarketProviderJobRepository marketProviderJobRepository;
    private final ApiResponseAuditRepository apiResponseAuditRepository;
    private final MarketDataProviderRouter providerRouter;
    private final MarketDataNormalizationService normalizationService;
    private final SensitiveDataMaskingUtil sensitiveDataMaskingUtil;
    private final ObjectMapper objectMapper;

    @Value("${app.market.collection.quote-asset-limit:60}")
    private int quoteAssetLimit;

    @Value("${app.market.collection.bar-asset-limit:30}")
    private int barAssetLimit;

    @Value("${app.market.collection.bar-timeframe:1m}")
    private String defaultBarTimeframe;

    @Value("${app.market.collection.bar-points-per-asset:3}")
    private int barPointsPerAsset;

    /**
     * quote 수집 배치 실행.
     */
    @Transactional
    public CollectionRunResult collectQuotes(String triggeredBy) {
        List<AssetUniverseEntity> assets = selectAssets(Math.max(1, quoteAssetLimit));
        return runQuoteCollection(assets, normalizeTriggeredBy(triggeredBy));
    }

    /**
     * bar 수집 배치 실행.
     */
    @Transactional
    public CollectionRunResult collectBars(String timeframe, String triggeredBy) {
        List<AssetUniverseEntity> assets = selectAssets(Math.max(1, barAssetLimit));
        String safeTimeframe = normalizeTimeframe(timeframe);
        return runBarCollection(assets, safeTimeframe, Math.max(1, barPointsPerAsset), normalizeTriggeredBy(triggeredBy));
    }

    /**
     * provider health check 실행.
     */
    @Transactional
    public CollectionRunResult runProviderHealthCheck(String triggeredBy) {
        String traceId = traceId();
        MarketProviderJobEntity job = beginJob(
                providerRouter.activeProviderId(),
                "market-provider-health-check",
                MarketProviderJobType.HEALTH_CHECK,
                null,
                normalizeTriggeredBy(triggeredBy),
                1,
                traceId);

        ExecutionContext<MarketProviderHealthDto> exec = executeWithFallback("health-check", null, null);
        persistAudit(traceId, exec.primaryRequestHash(), exec.finalResponse());
        if (exec.usedFallback() && exec.primaryResponse() != null) {
            persistAudit(traceId, exec.primaryRequestHash(), exec.primaryResponse());
        }

        int successCount = 0;
        int failedCount = 0;
        if (exec.finalResponse() != null && exec.finalResponse().success() && !exec.finalResponse().emptyResponse()) {
            successCount = exec.finalResponse().recordCount();
        } else if (exec.finalResponse() == null || !exec.finalResponse().success()) {
            failedCount = 1;
        }

        finishJob(job, exec, 1, exec.finalResponse() == null ? 0 : exec.finalResponse().recordCount(), successCount, failedCount);
        return resultOf(job, exec);
    }

    private CollectionRunResult runQuoteCollection(List<AssetUniverseEntity> assets, String triggeredBy) {
        String traceId = traceId();
        Map<String, AssetUniverseEntity> assetMap = assets.stream()
                .collect(java.util.stream.Collectors.toMap(AssetUniverseEntity::getAssetCode, a -> a, (a, b) -> a));
        MarketProviderJobEntity job = beginJob(
                providerRouter.activeProviderId(),
                "market-quote-collection",
                MarketProviderJobType.QUOTE,
                null,
                triggeredBy,
                assets.size(),
                traceId);

        ExecutionContext<MarketQuoteDto> exec = executeWithFallback("quotes", assets, null);
        persistAudit(traceId, exec.primaryRequestHash(), exec.finalResponse());
        if (exec.usedFallback() && exec.primaryResponse() != null) {
            persistAudit(traceId, exec.primaryRequestHash(), exec.primaryResponse());
        }

        int processed = exec.finalResponse() == null ? 0 : exec.finalResponse().recordCount();
        int success = 0;
        int failed = 0;
        if (exec.finalResponse() != null && exec.finalResponse().success()) {
            for (MarketQuoteDto dto : exec.finalResponse().items()) {
                Optional<MarketQuoteSnapshotEntity> entityOpt = normalizationService.toQuoteEntity(dto);
                if (entityOpt.isEmpty()) {
                    failed++;
                    continue;
                }
                marketQuoteSnapshotRepository.save(entityOpt.get());
                AssetUniverseEntity asset = assetMap.get(entityOpt.get().getAssetCode());
                if (asset != null) {
                    asset.setLastQuoteReceivedAt(entityOpt.get().getQuoteTimeUtc() != null
                            ? entityOpt.get().getQuoteTimeUtc()
                            : entityOpt.get().getSnapshotUtc());
                }
                success++;
            }
        } else if (!assets.isEmpty()) {
            failed = assets.size();
        }

        finishJob(job, exec, assets.size(), processed, success, failed);
        if (!assetMap.isEmpty()) {
            assetUniverseRepository.saveAll(assetMap.values());
        }
        return resultOf(job, exec);
    }

    private CollectionRunResult runBarCollection(
            List<AssetUniverseEntity> assets,
            String timeframe,
            int barsPerAsset,
            String triggeredBy) {
        String traceId = traceId();
        MarketProviderJobEntity job = beginJob(
                providerRouter.activeProviderId(),
                "market-bar-collection",
                MarketProviderJobType.BAR,
                null,
                triggeredBy,
                assets.size(),
                traceId);

        ExecutionContext<MarketPriceBarDto> exec = executeWithFallback("bars", assets, Map.of(
                "timeframe", timeframe,
                "bars_per_asset", barsPerAsset));
        persistAudit(traceId, exec.primaryRequestHash(), exec.finalResponse());
        if (exec.usedFallback() && exec.primaryResponse() != null) {
            persistAudit(traceId, exec.primaryRequestHash(), exec.primaryResponse());
        }

        int processed = exec.finalResponse() == null ? 0 : exec.finalResponse().recordCount();
        int success = 0;
        int failed = 0;
        int duplicate = 0;
        if (exec.finalResponse() != null && exec.finalResponse().success()) {
            for (MarketPriceBarDto dto : exec.finalResponse().items()) {
                Optional<MarketPriceBarEntity> entityOpt = normalizationService.toBarEntity(dto);
                if (entityOpt.isEmpty()) {
                    failed++;
                    continue;
                }
                try {
                    marketPriceBarRepository.save(entityOpt.get());
                    success++;
                } catch (DataIntegrityViolationException e) {
                    duplicate++;
                }
            }
        } else if (!assets.isEmpty()) {
            failed = assets.size();
        }

        finishJob(job, exec, assets.size(), processed, success, failed, Map.of(
                "timeframe", timeframe,
                "bars_per_asset", barsPerAsset,
                "duplicate_count", duplicate));
        return resultOf(job, exec);
    }

    private <T> ExecutionContext<T> executeWithFallback(
            String apiName,
            List<AssetUniverseEntity> assets,
            Map<String, Object> requestExtra) {
        MarketDataProvider primary = providerRouter.resolveActiveProvider();
        String primaryRequestHash = hashText(buildRequestFingerprint(primary.providerId(), apiName, assets, requestExtra));
        MarketProviderFetchResult<T> primaryResponse = invokeProvider(primary, apiName, assets, requestExtra);

        if (shouldFallback(primary, primaryResponse)) {
            MarketDataProvider fallback = providerRouter.resolveMockProvider();
            if (!fallback.providerId().equalsIgnoreCase(primary.providerId())) {
                MarketProviderFetchResult<T> fallbackResponse = invokeProvider(fallback, apiName, assets, requestExtra);
                return new ExecutionContext<>(
                        primary.providerId(),
                        primaryResponse,
                        fallbackResponse,
                        true,
                        "PRIMARY_" + (primaryResponse.success() ? "EMPTY" : "FAILED"),
                        primaryRequestHash);
            }
        }
        return new ExecutionContext<>(
                primary.providerId(),
                primaryResponse,
                primaryResponse,
                false,
                null,
                primaryRequestHash);
    }

    private boolean shouldFallback(MarketDataProvider primary, MarketProviderFetchResult<?> response) {
        if (!providerRouter.fallbackToMockOnFailure()) {
            return false;
        }
        if (primary == null || "mock".equalsIgnoreCase(primary.providerId())) {
            return false;
        }
        if (response == null) {
            return true;
        }
        return !response.success() || response.emptyResponse();
    }

    private <T> MarketProviderFetchResult<T> invokeProvider(
            MarketDataProvider provider,
            String apiName,
            List<AssetUniverseEntity> assets,
            Map<String, Object> requestExtra) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            if ("quotes".equals(apiName)) {
                @SuppressWarnings("unchecked")
                MarketProviderFetchResult<T> result = (MarketProviderFetchResult<T>) provider.fetchQuotes(assets == null ? List.of() : assets);
                return result;
            }
            if ("bars".equals(apiName)) {
                String timeframe = requestExtra == null ? "1m" : String.valueOf(requestExtra.getOrDefault("timeframe", "1m"));
                int barsPerAsset = requestExtra != null && requestExtra.get("bars_per_asset") instanceof Number n
                        ? Math.max(1, n.intValue())
                        : providerRouter.mockBarsPerAsset();
                @SuppressWarnings("unchecked")
                MarketProviderFetchResult<T> result = (MarketProviderFetchResult<T>) provider.fetchBars(
                        assets == null ? List.of() : assets,
                        timeframe,
                        barsPerAsset);
                return result;
            }
            if ("health-check".equals(apiName)) {
                @SuppressWarnings("unchecked")
                MarketProviderFetchResult<T> result = (MarketProviderFetchResult<T>) provider.healthCheck();
                return result;
            }
            return MarketProviderFetchResult.failure(
                    provider.providerId(),
                    apiName,
                    500,
                    startedAt,
                    OffsetDateTime.now(),
                    "{\"error\":\"unsupported_api\"}",
                    "UNSUPPORTED_API",
                    Map.of());
        } catch (Exception e) {
            OffsetDateTime finishedAt = OffsetDateTime.now();
            return MarketProviderFetchResult.failure(
                    provider.providerId(),
                    apiName,
                    500,
                    startedAt,
                    finishedAt,
                    "{\"error\":\"" + safeJson(sensitiveDataMaskingUtil.maskPayload(e.getMessage())) + "\"}",
                    sensitiveDataMaskingUtil.sanitizeErrorCode(e.getClass().getSimpleName()),
                    Map.of("exception", e.getClass().getName()));
        }
    }

    private void persistAudit(String traceId, String requestHash, MarketProviderFetchResult<?> response) {
        if (response == null) {
            return;
        }
        ApiResponseAuditEntity audit = new ApiResponseAuditEntity();
        audit.setProvider(defaultIfBlank(response.providerName(), "unknown"));
        audit.setApiName(defaultIfBlank(response.apiName(), "unknown"));
        audit.setRequestTimeUtc(response.requestTimeUtc() == null ? OffsetDateTime.now() : response.requestTimeUtc());
        audit.setResponseTimeUtc(response.responseTimeUtc());
        long latency = response.latencyMs();
        audit.setLatencyMs(latency < 0 ? null : latency);
        audit.setHttpStatus(response.httpStatus());
        audit.setRequestHash(requestHash);
        audit.setResponseHash(hashText(response.samplePayloadJson()));
        audit.setRecordCount(response.recordCount());
        audit.setSamplePayloadJson(sensitiveDataMaskingUtil.maskPayload(response.samplePayloadJson()));
        audit.setSuccess(response.success());
        audit.setErrorCode(sensitiveDataMaskingUtil.sanitizeErrorCode(response.errorCode()));
        audit.setTraceId(traceId);
        apiResponseAuditRepository.save(audit);
    }

    private MarketProviderJobEntity beginJob(
            String providerName,
            String jobName,
            MarketProviderJobType jobType,
            String assetCode,
            String triggeredBy,
            int requestedCount,
            String traceId) {
        MarketProviderJobEntity job = new MarketProviderJobEntity();
        job.setProviderName(defaultIfBlank(providerName, "mock"));
        job.setJobName(jobName);
        job.setJobType(jobType);
        job.setAssetCode(assetCode);
        job.setTriggeredBy(triggeredBy);
        job.setRequestedCount(Math.max(0, requestedCount));
        job.setStatus(JobStatus.RUNNING);
        job.setAttempt(1);
        job.setScheduledAt(OffsetDateTime.now());
        job.setStartedAt(OffsetDateTime.now());
        job.setTraceId(traceId);
        job.setDetailJson(toJson(Map.of(
                "active_provider", defaultIfBlank(providerName, "mock"),
                "fallback_to_mock_on_failure", providerRouter.fallbackToMockOnFailure())));
        return marketProviderJobRepository.save(job);
    }

    private void finishJob(
            MarketProviderJobEntity job,
            ExecutionContext<?> exec,
            int requestedCount,
            int processedCount,
            int successCount,
            int failedCount) {
        finishJob(job, exec, requestedCount, processedCount, successCount, failedCount, null);
    }

    private void finishJob(
            MarketProviderJobEntity job,
            ExecutionContext<?> exec,
            int requestedCount,
            int processedCount,
            int successCount,
            int failedCount,
            Map<String, Object> extraDetail) {
        MarketProviderFetchResult<?> finalResponse = exec.finalResponse();
        job.setProviderName(finalResponse == null
                ? defaultIfBlank(job.getProviderName(), exec.primaryProviderName())
                : defaultIfBlank(finalResponse.providerName(), exec.primaryProviderName()));
        job.setRequestedCount(Math.max(0, requestedCount));
        job.setProcessedCount(Math.max(0, processedCount));
        job.setSuccessCount(Math.max(0, successCount));
        job.setFailedCount(Math.max(0, failedCount));
        job.setEmptyResponse(finalResponse == null || finalResponse.emptyResponse());
        job.setLatencyMs(finalResponse == null || finalResponse.latencyMs() < 0 ? null : finalResponse.latencyMs());
        job.setFinishedAt(OffsetDateTime.now());
        job.setStatus(finalResponse != null && finalResponse.success() ? JobStatus.SUCCESS : JobStatus.FAILED);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("primary_provider", exec.primaryProviderName());
        detail.put("used_fallback", exec.usedFallback());
        detail.put("fallback_reason", exec.fallbackReason());
        detail.put("final_provider", finalResponse == null ? null : finalResponse.providerName());
        detail.put("api_name", finalResponse == null ? null : finalResponse.apiName());
        detail.put("http_status", finalResponse == null ? null : finalResponse.httpStatus());
        detail.put("record_count", finalResponse == null ? 0 : finalResponse.recordCount());
        detail.put("response_success", finalResponse != null && finalResponse.success());
        if (finalResponse != null && finalResponse.meta() != null && !finalResponse.meta().isEmpty()) {
            detail.put("provider_meta", finalResponse.meta());
        }
        if (extraDetail != null && !extraDetail.isEmpty()) {
            detail.put("extra", extraDetail);
        }
        job.setDetailJson(toJson(detail));

        if (finalResponse == null || !finalResponse.success()) {
            String errorCode = finalResponse == null ? "PROVIDER_CALL_FAILED" : finalResponse.errorCode();
            job.setLastError(sensitiveDataMaskingUtil.sanitizeErrorCode(errorCode, 512));
        } else {
            job.setLastError(null);
        }
        marketProviderJobRepository.save(job);
    }

    private CollectionRunResult resultOf(MarketProviderJobEntity job, ExecutionContext<?> exec) {
        return new CollectionRunResult(
                job.getId(),
                job.getJobName(),
                defaultIfBlank(job.getProviderName(), exec.primaryProviderName()),
                job.getStatus() == null ? "UNKNOWN" : job.getStatus().name(),
                job.getRequestedCount() == null ? 0 : job.getRequestedCount(),
                job.getProcessedCount() == null ? 0 : job.getProcessedCount(),
                job.getSuccessCount() == null ? 0 : job.getSuccessCount(),
                job.getFailedCount() == null ? 0 : job.getFailedCount(),
                Boolean.TRUE.equals(job.getEmptyResponse()),
                exec.usedFallback(),
                exec.fallbackReason(),
                job.getTraceId(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getLatencyMs());
    }

    private List<AssetUniverseEntity> selectAssets(int limit) {
        return assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc().stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    private String normalizeTimeframe(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return defaultBarTimeframe == null || defaultBarTimeframe.isBlank() ? "1m" : defaultBarTimeframe.trim();
        }
        return timeframe.trim().toLowerCase();
    }

    private String normalizeTriggeredBy(String triggeredBy) {
        if (triggeredBy == null || triggeredBy.isBlank()) {
            return "system";
        }
        String value = triggeredBy.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private String buildRequestFingerprint(
            String providerName,
            String apiName,
            List<AssetUniverseEntity> assets,
            Map<String, Object> requestExtra) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", defaultIfBlank(providerName, "unknown"));
        map.put("api_name", defaultIfBlank(apiName, "unknown"));
        map.put("asset_count", assets == null ? 0 : assets.size());
        if (assets != null && !assets.isEmpty()) {
            map.put("asset_codes", assets.stream().limit(20).map(AssetUniverseEntity::getAssetCode).toList());
        }
        if (requestExtra != null && !requestExtra.isEmpty()) {
            map.put("extra", requestExtra);
        }
        return toJson(map);
    }

    private String hashText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    /**
     * 수집 실행 결과 요약 DTO.
     */
    public record CollectionRunResult(
            Long jobId,
            String jobName,
            String providerName,
            String status,
            int requestedCount,
            int processedCount,
            int successCount,
            int failedCount,
            boolean emptyResponse,
            boolean fallbackUsed,
            String fallbackReason,
            String traceId,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long latencyMs) {
    }

    private record ExecutionContext<T>(
            String primaryProviderName,
            MarketProviderFetchResult<T> primaryResponse,
            MarketProviderFetchResult<T> finalResponse,
            boolean usedFallback,
            String fallbackReason,
            String primaryRequestHash) {
    }
}

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
import java.util.ArrayList;
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
        return collectQuotes(triggeredBy, null);
    }

    /**
     * quote 수집 실행(수동 테스트용 provider override 지원).
     */
    @Transactional
    public CollectionRunResult collectQuotes(String triggeredBy, String providerOverride) {
        List<AssetUniverseEntity> assets = selectAssets(Math.max(1, quoteAssetLimit));
        return runQuoteCollection(assets, normalizeTriggeredBy(triggeredBy), normalizeProviderOverride(providerOverride));
    }

    /**
     * bar 수집 배치 실행.
     */
    @Transactional
    public CollectionRunResult collectBars(String timeframe, String triggeredBy) {
        return collectBars(timeframe, null, triggeredBy, null);
    }

    /**
     * bar 수집 실행(수동 테스트용 provider override/barsPerAsset override 지원).
     */
    @Transactional
    public CollectionRunResult collectBars(String timeframe, Integer barsPerAssetOverride, String triggeredBy, String providerOverride) {
        List<AssetUniverseEntity> assets = selectAssets(Math.max(1, barAssetLimit));
        String safeTimeframe = normalizeTimeframe(timeframe);
        int safeBarsPerAsset = barsPerAssetOverride == null ? Math.max(1, barPointsPerAsset) : Math.max(1, barsPerAssetOverride);
        return runBarCollection(
                assets,
                safeTimeframe,
                safeBarsPerAsset,
                normalizeTriggeredBy(triggeredBy),
                normalizeProviderOverride(providerOverride));
    }

    /**
     * provider health check 실행.
     */
    @Transactional
    public CollectionRunResult runProviderHealthCheck(String triggeredBy) {
        return runProviderHealthCheck(triggeredBy, null);
    }

    /**
     * provider health check 실행(수동 테스트용 provider override 지원).
     */
    @Transactional
    public CollectionRunResult runProviderHealthCheck(String triggeredBy, String providerOverride) {
        String traceId = traceId();
        String requestedProvider = defaultIfBlank(providerOverride, providerRouter.activeProviderId());
        MarketProviderJobEntity job = beginJob(
                requestedProvider,
                "market-provider-health-check",
                MarketProviderJobType.HEALTH_CHECK,
                null,
                normalizeTriggeredBy(triggeredBy),
                1,
                traceId);

        ExecutionContext<MarketProviderHealthDto> exec = executeWithFallback("health-check", null, null, providerOverride);
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

    private CollectionRunResult runQuoteCollection(List<AssetUniverseEntity> assets, String triggeredBy, String providerOverride) {
        String traceId = traceId();
        Map<String, AssetUniverseEntity> assetMap = assets.stream()
                .collect(java.util.stream.Collectors.toMap(AssetUniverseEntity::getAssetCode, a -> a, (a, b) -> a));
        String requestedProvider = defaultIfBlank(providerOverride, providerRouter.activeProviderId());
        MarketProviderJobEntity job = beginJob(
                requestedProvider,
                "market-quote-collection",
                MarketProviderJobType.QUOTE,
                null,
                triggeredBy,
                assets.size(),
                traceId);

        ExecutionContext<MarketQuoteDto> exec = executeWithFallback("quotes", assets, null, providerOverride);
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
                entityOpt.get().setTraceId(traceId);
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
            String triggeredBy,
            String providerOverride) {
        String traceId = traceId();
        String requestedProvider = defaultIfBlank(providerOverride, providerRouter.activeProviderId());
        MarketProviderJobEntity job = beginJob(
                requestedProvider,
                "market-bar-collection",
                MarketProviderJobType.BAR,
                null,
                triggeredBy,
                assets.size(),
                traceId);

        ExecutionContext<MarketPriceBarDto> exec = executeWithFallback("bars", assets, Map.of(
                "timeframe", timeframe,
                "bars_per_asset", barsPerAsset), providerOverride);
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
                    entityOpt.get().setTraceId(traceId);
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
            Map<String, Object> requestExtra,
            String providerOverride) {
        String requestedProvider = defaultIfBlank(providerOverride, providerRouter.activeProviderId());
        String primaryRequestHash = hashText(buildRequestFingerprint(requestedProvider, apiName, assets, requestExtra));

        if (providerRouter.isMockProviderId(requestedProvider) && !providerRouter.allowMock()) {
            MarketProviderFetchResult<T> blockedResponse = blockedMockResponse(apiName, "REQUESTED_PROVIDER");
            return new ExecutionContext<>(
                    requestedProvider,
                    requestedProvider,
                    blockedResponse,
                    blockedResponse,
                    false,
                    true,
                    true,
                    "MOCK_PROVIDER_DISABLED",
                    primaryRequestHash);
        }

        MarketDataProvider primary;
        try {
            primary = providerRouter.resolveRequired(requestedProvider);
        } catch (Exception e) {
            MarketProviderFetchResult<T> failure = providerResolutionFailureResponse(requestedProvider, apiName, e);
            return new ExecutionContext<>(
                    requestedProvider,
                    requestedProvider,
                    failure,
                    failure,
                    false,
                    false,
                    false,
                    "PROVIDER_NOT_REGISTERED",
                    primaryRequestHash);
        }

        MarketProviderFetchResult<T> primaryResponse = invokeProvider(primary, apiName, assets, requestExtra);

        if (shouldFallback(primary, primaryResponse)) {
            if (!providerRouter.allowMock()) {
                return new ExecutionContext<>(
                        requestedProvider,
                        primary.providerId(),
                        primaryResponse,
                        primaryResponse,
                        false,
                        true,
                        false,
                        "MOCK_FALLBACK_BLOCKED",
                        primaryRequestHash);
            }
            MarketDataProvider fallback = providerRouter.resolveMockProvider();
            if (!fallback.providerId().equalsIgnoreCase(primary.providerId())) {
                MarketProviderFetchResult<T> fallbackResponse = invokeProvider(fallback, apiName, assets, requestExtra);
                return new ExecutionContext<>(
                        requestedProvider,
                        primary.providerId(),
                        primaryResponse,
                        fallbackResponse,
                        true,
                        false,
                        false,
                        "PRIMARY_" + (primaryResponse.success() ? "EMPTY" : "FAILED"),
                        primaryRequestHash);
            }
        }
        return new ExecutionContext<>(
                requestedProvider,
                primary.providerId(),
                primaryResponse,
                primaryResponse,
                false,
                false,
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

    private <T> MarketProviderFetchResult<T> blockedMockResponse(String apiName, String scope) {
        OffsetDateTime now = OffsetDateTime.now();
        return MarketProviderFetchResult.failure(
                "mock",
                apiName,
                503,
                now,
                now,
                "{\"provider\":\"mock\",\"error\":\"mock_disabled\"}",
                "MOCK_PROVIDER_DISABLED",
                "mock provider is disabled by app.market.provider.allow-mock=false (" + scope + ")",
                Map.of(
                        "mock", true,
                        "degraded", true,
                        "fallback_blocked", true,
                        "allow_mock", false));
    }

    private <T> MarketProviderFetchResult<T> providerResolutionFailureResponse(String providerId, String apiName, Exception e) {
        OffsetDateTime now = OffsetDateTime.now();
        String safeProvider = defaultIfBlank(providerId, "unknown");
        String maskedMessage = sanitizeProviderErrorMessage(e.getMessage(), 500);
        return MarketProviderFetchResult.failure(
                safeProvider,
                apiName,
                500,
                now,
                now,
                "{\"provider\":\"" + safeJson(safeProvider) + "\",\"error\":\"provider_not_registered\"}",
                "PROVIDER_NOT_REGISTERED",
                maskedMessage == null ? "provider is not registered: " + safeProvider : maskedMessage,
                Map.of(
                        "provider_resolution_failed", true,
                        "degraded", true));
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
                    "unsupported api: " + apiName,
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
                    sanitizeProviderErrorMessage(e.getMessage(), 1000),
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
                "fallback_to_mock_on_failure", providerRouter.fallbackToMockOnFailure(),
                "allow_mock", providerRouter.allowMock())));
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
        MarketProviderFetchResult<?> primaryResponse = exec.primaryResponse();
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
        boolean degraded = isDegraded(job.getJobType(), requestedCount, exec, finalResponse);
        boolean fallbackBlockedFailure = exec.fallbackBlocked()
                && (finalResponse == null || !finalResponse.success()
                || (requestedCount > 0 && finalResponse.emptyResponse() && job.getJobType() != MarketProviderJobType.HEALTH_CHECK));
        job.setStatus((finalResponse != null && finalResponse.success() && !fallbackBlockedFailure && !exec.mockBlocked())
                ? JobStatus.SUCCESS
                : JobStatus.FAILED);

        String providerErrorCode = null;
        String providerErrorMessage = null;
        if (finalResponse != null && !finalResponse.success()) {
            providerErrorCode = sensitiveDataMaskingUtil.sanitizeErrorCode(finalResponse.errorCode(), 128);
            providerErrorMessage = sanitizeProviderErrorMessage(finalResponse.errorMessage(), 2000);
        } else if (exec.usedFallback() && primaryResponse != null && (!primaryResponse.success() || primaryResponse.emptyResponse())) {
            providerErrorCode = sensitiveDataMaskingUtil.sanitizeErrorCode(
                    primaryResponse.errorCode() == null && primaryResponse.emptyResponse()
                            ? "PRIMARY_EMPTY_RESPONSE"
                            : primaryResponse.errorCode(),
                    128);
            providerErrorMessage = sanitizeProviderErrorMessage(
                    primaryResponse.errorMessage() == null && primaryResponse.emptyResponse()
                            ? "primary provider returned empty response and fallback provider was used"
                            : primaryResponse.errorMessage(),
                    2000);
        } else if (exec.fallbackBlocked() && primaryResponse != null) {
            providerErrorCode = sensitiveDataMaskingUtil.sanitizeErrorCode(primaryResponse.errorCode(), 128);
            providerErrorMessage = sanitizeProviderErrorMessage(primaryResponse.errorMessage(), 2000);
        }
        job.setProviderErrorCode(providerErrorCode);
        job.setProviderErrorMessage(providerErrorMessage);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requested_provider", exec.requestedProviderName());
        detail.put("active_provider", providerRouter.activeProviderId());
        detail.put("primary_provider", exec.primaryProviderName());
        detail.put("used_fallback", exec.usedFallback());
        detail.put("fallback_blocked", exec.fallbackBlocked());
        detail.put("mock_blocked", exec.mockBlocked());
        detail.put("fallback_reason", exec.fallbackReason());
        detail.put("final_provider", finalResponse == null ? null : finalResponse.providerName());
        detail.put("api_name", finalResponse == null ? null : finalResponse.apiName());
        detail.put("http_status", finalResponse == null ? null : finalResponse.httpStatus());
        detail.put("record_count", finalResponse == null ? 0 : finalResponse.recordCount());
        detail.put("response_success", finalResponse != null && finalResponse.success());
        detail.put("degraded", degraded);
        detail.put("allow_mock", providerRouter.allowMock());
        detail.put("provider_error_code", providerErrorCode);
        detail.put("provider_error_message", providerErrorMessage);
        if (finalResponse != null && finalResponse.meta() != null && !finalResponse.meta().isEmpty()) {
            detail.put("provider_meta", finalResponse.meta());
        }
        if (extraDetail != null && !extraDetail.isEmpty()) {
            detail.put("extra", extraDetail);
        }
        job.setDetailJson(toJson(detail));

        if (finalResponse == null || !finalResponse.success()) {
            String errorCode = finalResponse == null ? "PROVIDER_CALL_FAILED" : finalResponse.errorCode();
            String errorMessage = finalResponse == null ? null : finalResponse.errorMessage();
            String merged = errorMessage == null || errorMessage.isBlank()
                    ? sensitiveDataMaskingUtil.sanitizeErrorCode(errorCode, 512)
                    : safeMergedError(errorCode, errorMessage);
            job.setLastError(merged);
        } else {
            job.setLastError(null);
        }
        marketProviderJobRepository.save(job);
    }

    private CollectionRunResult resultOf(MarketProviderJobEntity job, ExecutionContext<?> exec) {
        MarketProviderFetchResult<?> finalResponse = exec.finalResponse();
        boolean degraded = isDegraded(job.getJobType(), job.getRequestedCount() == null ? 0 : job.getRequestedCount(), exec, finalResponse);
        boolean mockProvider = finalResponse != null && providerRouter.isMockProviderId(finalResponse.providerName());
        boolean isDelayed = isDelayed(finalResponse);
        List<String> warnings = buildWarnings(job, exec, finalResponse, degraded, mockProvider, isDelayed);
        return new CollectionRunResult(
                job.getId(),
                job.getJobName(),
                exec.requestedProviderName(),
                providerRouter.activeProviderId(),
                defaultIfBlank(job.getProviderName(), exec.primaryProviderName()),
                job.getStatus() == null ? "UNKNOWN" : job.getStatus().name(),
                job.getRequestedCount() == null ? 0 : job.getRequestedCount(),
                job.getProcessedCount() == null ? 0 : job.getProcessedCount(),
                job.getSuccessCount() == null ? 0 : job.getSuccessCount(),
                job.getFailedCount() == null ? 0 : job.getFailedCount(),
                Boolean.TRUE.equals(job.getEmptyResponse()),
                exec.usedFallback(),
                exec.fallbackBlocked(),
                exec.mockBlocked(),
                degraded,
                mockProvider,
                isDelayed,
                exec.fallbackReason(),
                job.getProviderErrorCode(),
                job.getProviderErrorMessage(),
                warnings,
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

    private String normalizeProviderOverride(String providerOverride) {
        if (providerOverride == null || providerOverride.isBlank()) {
            return null;
        }
        String value = providerOverride.trim().toLowerCase();
        return value.length() > 32 ? value.substring(0, 32) : value;
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

    private String safeMergedError(String errorCode, String errorMessage) {
        String code = sensitiveDataMaskingUtil.sanitizeErrorCode(errorCode, 120);
        String message = sanitizeProviderErrorMessage(errorMessage, 800);
        if (code == null || code.isBlank()) {
            return message;
        }
        if (message == null || message.isBlank()) {
            return code;
        }
        return code + ": " + message;
    }

    private String sanitizeProviderErrorMessage(String message, int maxLength) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String masked = sensitiveDataMaskingUtil.maskPayload(message).replaceAll("\\s+", " ").trim();
        int safeLength = Math.max(1, maxLength);
        return masked.length() > safeLength ? masked.substring(0, safeLength) : masked;
    }

    private boolean isDegraded(
            MarketProviderJobType jobType,
            int requestedCount,
            ExecutionContext<?> exec,
            MarketProviderFetchResult<?> finalResponse) {
        if (exec == null) {
            return true;
        }
        if (exec.usedFallback() || exec.fallbackBlocked() || exec.mockBlocked()) {
            return true;
        }
        if (finalResponse == null || !finalResponse.success()) {
            return true;
        }
        return requestedCount > 0
                && finalResponse.emptyResponse()
                && jobType != MarketProviderJobType.HEALTH_CHECK;
    }

    private boolean isDelayed(MarketProviderFetchResult<?> response) {
        if (response == null) {
            return true;
        }
        Object delayed = response.meta() == null ? null : response.meta().get("is_delayed");
        if (delayed instanceof Boolean bool) {
            return bool;
        }
        long latencyMs = response.latencyMs();
        return latencyMs >= 0 && latencyMs > 3000L;
    }

    private List<String> buildWarnings(
            MarketProviderJobEntity job,
            ExecutionContext<?> exec,
            MarketProviderFetchResult<?> finalResponse,
            boolean degraded,
            boolean mockProvider,
            boolean isDelayed) {
        List<String> warnings = new ArrayList<>();
        if (mockProvider) {
            warnings.add("시장데이터 provider_name=MOCK 입니다. 운영 판단용 실데이터가 아닙니다.");
        }
        if (exec.mockBlocked()) {
            warnings.add("allowMock=false 설정으로 mock provider 호출이 차단되었습니다.");
        }
        if (exec.fallbackBlocked()) {
            warnings.add("실 Provider 실패/빈응답 발생. allowMock=false 로 mock fallback 이 차단되었습니다.");
        }
        if (exec.usedFallback()) {
            warnings.add("실 Provider 실패/빈응답으로 mock fallback 이 사용되었습니다.");
        }
        if (isDelayed) {
            warnings.add("시장데이터 응답 지연이 감지되었습니다.");
        }
        if (degraded) {
            warnings.add("시장데이터 수집 결과가 degraded 상태입니다.");
        }
        if (job != null && Boolean.TRUE.equals(job.getEmptyResponse())
                && (job.getRequestedCount() == null || job.getRequestedCount() > 0)
                && job.getJobType() != MarketProviderJobType.HEALTH_CHECK) {
            warnings.add("Provider 응답이 비어 있습니다.");
        }
        if (job != null && job.getProviderErrorCode() != null) {
            warnings.add("Provider 오류: " + job.getProviderErrorCode());
        } else if (finalResponse != null && finalResponse.errorCode() != null) {
            warnings.add("Provider 오류: " + finalResponse.errorCode());
        }
        return warnings;
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
            String requestedProviderName,
            String activeProviderName,
            String providerName,
            String status,
            int requestedCount,
            int processedCount,
            int successCount,
            int failedCount,
            boolean emptyResponse,
            boolean fallbackUsed,
            boolean fallbackBlocked,
            boolean mockBlocked,
            boolean degraded,
            boolean mockProvider,
            boolean delayed,
            String fallbackReason,
            String providerErrorCode,
            String providerErrorMessage,
            List<String> warnings,
            String traceId,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long latencyMs) {
    }

    private record ExecutionContext<T>(
            String requestedProviderName,
            String primaryProviderName,
            MarketProviderFetchResult<T> primaryResponse,
            MarketProviderFetchResult<T> finalResponse,
            boolean usedFallback,
            boolean fallbackBlocked,
            boolean mockBlocked,
            String fallbackReason,
            String primaryRequestHash) {
    }
}

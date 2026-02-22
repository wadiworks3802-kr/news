package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.AdminMarketDataCollectRequest;
import com.wangbyul.gnd.core.domain.MarketProviderJobType;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import com.wangbyul.gnd.core.service.MarketDataCollectionService;
import com.wangbyul.gnd.core.service.MarketDataCollectionService.CollectionRunResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 관리자 수동 시장데이터 수집 API 컨트롤러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@RestController
@Validated
@RequestMapping("/api/admin/market-data")
public class AdminMarketDataController {

    private final MarketDataCollectionService marketDataCollectionService;

    public AdminMarketDataController(MarketDataCollectionService marketDataCollectionService) {
        this.marketDataCollectionService = marketDataCollectionService;
    }

    /**
     * 수동 시장데이터 수집 실행(QUOTE/BAR/HEALTH_CHECK).
     */
    @PostMapping("/collect")
    public ApiEnvelope<CollectionRunResult> collect(@RequestBody @Valid AdminMarketDataCollectRequest request) {
        CollectionRunResult result = execute(request);
        Map<String, Object> meta = buildMeta(result, request.getJobType());
        return ApiEnvelope.<CollectionRunResult>builder()
                .data(result)
                .meta(meta)
                .traceId(defaultTraceId(result.traceId()))
                .build();
    }

    private CollectionRunResult execute(@NotNull AdminMarketDataCollectRequest request) {
        String provider = request.getProvider();
        String triggeredBy = request.getTriggeredBy();
        MarketProviderJobType jobType = request.getJobType();
        if (jobType == MarketProviderJobType.QUOTE) {
            return marketDataCollectionService.collectQuotes(triggeredBy, provider);
        }
        if (jobType == MarketProviderJobType.BAR) {
            return marketDataCollectionService.collectBars(
                    request.getTimeframe(),
                    request.getBarsPerAsset(),
                    triggeredBy,
                    provider);
        }
        if (jobType == MarketProviderJobType.HEALTH_CHECK) {
            return marketDataCollectionService.runProviderHealthCheck(triggeredBy, provider);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported jobType for manual collect: " + jobType);
    }

    private Map<String, Object> buildMeta(CollectionRunResult result, MarketProviderJobType jobType) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", "manual");
        meta.put("job_type", jobType == null ? null : jobType.name());
        meta.put("provider_name", result.providerName());
        meta.put("requested_provider_name", result.requestedProviderName());
        meta.put("active_provider_name", result.activeProviderName());
        meta.put("is_delayed", result.delayed());
        meta.put("degraded", result.degraded());
        meta.put("warnings", result.warnings() == null ? List.of() : result.warnings());
        meta.put("fallback_used", result.fallbackUsed());
        meta.put("fallback_blocked", result.fallbackBlocked());
        meta.put("mock_blocked", result.mockBlocked());
        meta.put("mock_provider_warning", result.mockProvider());
        if (result.providerErrorCode() != null) {
            meta.put("provider_error_code", result.providerErrorCode());
        }
        if (result.providerErrorMessage() != null) {
            meta.put("provider_error_message", result.providerErrorMessage());
        }
        meta.put("trace_id", defaultTraceId(result.traceId()));
        return meta;
    }

    private String defaultTraceId(String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate;
        }
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }
}

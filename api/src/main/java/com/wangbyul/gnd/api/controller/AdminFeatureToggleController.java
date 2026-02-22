package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.FeatureToggleDto;
import com.wangbyul.gnd.api.dto.FeatureTogglePatchRequest;
import com.wangbyul.gnd.api.dto.FeatureToggleUpsertRequest;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.core.domain.FeatureScopeType;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 기능 토글 API 컨트롤러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/feature-toggles")
public class AdminFeatureToggleController {

    private final SystemFeatureToggleService systemFeatureToggleService;

    @GetMapping
    public ApiEnvelope<List<FeatureToggleDto>> list(
            @RequestParam(name = "feature_key", required = false) String featureKey,
            @RequestParam(name = "scope_type", required = false) FeatureScopeType scopeType,
            @RequestParam(name = "enabled", required = false) Boolean enabled,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(defaultValue = "50") int limit) {
        List<FeatureToggleDto> data = systemFeatureToggleService.list(featureKey, scopeType, enabled, traceId, limit);
        return envelope(data, Map.of(
                "generated_at", OffsetDateTime.now(),
                "source_window", "feature-toggle:latest",
                "warning_count", 0,
                "count", data.size()));
    }

    @PostMapping
    public ApiEnvelope<FeatureToggleDto> upsert(@RequestBody @Valid FeatureToggleUpsertRequest request) {
        FeatureToggleDto data = systemFeatureToggleService.upsert(request);
        return envelope(data, Map.of(
                "generated_at", OffsetDateTime.now(),
                "source_window", "feature-toggle:upsert",
                "warning_count", 0));
    }

    @PatchMapping("/{id}")
    public ApiEnvelope<FeatureToggleDto> patch(
            @PathVariable Long id,
            @RequestBody @Valid FeatureTogglePatchRequest request) {
        FeatureToggleDto data = systemFeatureToggleService.patch(id, request);
        return envelope(data, Map.of(
                "generated_at", OffsetDateTime.now(),
                "source_window", "feature-toggle:patch",
                "warning_count", 0));
    }

    private <T> ApiEnvelope<T> envelope(T data, Map<String, Object> meta) {
        return ApiEnvelope.<T>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }
}

package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.dto.FeatureToggleDto;
import com.wangbyul.gnd.api.dto.FeatureTogglePatchRequest;
import com.wangbyul.gnd.api.dto.FeatureToggleUpsertRequest;
import com.wangbyul.gnd.core.domain.FeatureScopeType;
import com.wangbyul.gnd.core.domain.SystemFeatureToggleEntity;
import com.wangbyul.gnd.core.repository.SystemFeatureToggleRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 킬스위치/전략 비활성화 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
@RequiredArgsConstructor
public class SystemFeatureToggleService {

    private static final List<String> DEFAULT_OFF_FEATURE_KEYS = List.of(
            "LIVE_TRADE",
            "AUTO_ORDER_FULLY_AUTOMATED",
            "AUTO_ORDER_WITH_ADMIN_APPROVAL");

    private final SystemFeatureToggleRepository systemFeatureToggleRepository;

    @Transactional(readOnly = true)
    public List<FeatureToggleDto> list(
            String featureKey,
            FeatureScopeType scopeType,
            Boolean enabled,
            String traceId,
            int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 300));
        List<SystemFeatureToggleEntity> rows;
        if (traceId != null && !traceId.isBlank()) {
            rows = systemFeatureToggleRepository.findByTraceIdOrderByUpdatedAtDesc(traceId.trim(), PageRequest.of(0, safeLimit));
        } else if (featureKey != null && !featureKey.isBlank()) {
            rows = systemFeatureToggleRepository.findByFeatureKeyOrderByUpdatedAtDesc(normalizeFeatureKey(featureKey), PageRequest.of(0, safeLimit));
        } else if (enabled != null) {
            rows = systemFeatureToggleRepository.findByEnabledOrderByUpdatedAtDesc(enabled, PageRequest.of(0, safeLimit));
        } else {
            rows = new ArrayList<>(systemFeatureToggleRepository.findAll(PageRequest.of(0, safeLimit)).getContent());
            rows.sort(Comparator.comparing(SystemFeatureToggleEntity::getUpdatedAt).reversed());
        }

        return rows.stream()
                .filter(row -> scopeType == null || row.getScopeType() == scopeType)
                .limit(safeLimit)
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public FeatureToggleDto upsert(FeatureToggleUpsertRequest request) {
        String featureKey = normalizeFeatureKey(request.getFeatureKey());
        FeatureScopeType scopeType = request.getScopeType() == null ? FeatureScopeType.GLOBAL : request.getScopeType();
        String scopeValue = normalizeScopeValue(scopeType, request.getScopeValue());

        Optional<SystemFeatureToggleEntity> existing = scopeType == FeatureScopeType.GLOBAL
                ? systemFeatureToggleRepository.findByFeatureKeyAndScopeTypeAndScopeValueIsNull(featureKey, scopeType)
                : systemFeatureToggleRepository.findByFeatureKeyAndScopeTypeAndScopeValue(featureKey, scopeType, scopeValue);

        SystemFeatureToggleEntity entity = existing.orElseGet(SystemFeatureToggleEntity::new);
        entity.setFeatureKey(featureKey);
        entity.setScopeType(scopeType);
        entity.setScopeValue(scopeValue);
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        entity.setReason(trimToNull(request.getReason()));
        entity.setUpdatedBy(trimToNull(request.getUpdatedBy()));
        entity.setTraceId(traceId());
        entity.setUpdatedAt(OffsetDateTime.now());

        return toDto(systemFeatureToggleRepository.save(entity));
    }

    @Transactional
    public FeatureToggleDto patch(Long id, FeatureTogglePatchRequest request) {
        SystemFeatureToggleEntity entity = systemFeatureToggleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("feature toggle not found: " + id));
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        if (request.getReason() != null) {
            entity.setReason(trimToNull(request.getReason()));
        }
        if (request.getUpdatedBy() != null && !request.getUpdatedBy().isBlank()) {
            entity.setUpdatedBy(trimToNull(request.getUpdatedBy()));
        }
        entity.setTraceId(traceId());
        entity.setUpdatedAt(OffsetDateTime.now());
        return toDto(systemFeatureToggleRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public boolean isFeatureEnabled(String featureKey, String country, String theme, String assetCode) {
        String normalizedKey = normalizeFeatureKey(featureKey);

        if (assetCode != null && !assetCode.isBlank()) {
            Optional<SystemFeatureToggleEntity> assetToggle = systemFeatureToggleRepository
                    .findByFeatureKeyAndScopeTypeAndScopeValue(normalizedKey, FeatureScopeType.ASSET, assetCode.trim());
            if (assetToggle.isPresent()) {
                return Boolean.TRUE.equals(assetToggle.get().getEnabled());
            }
        }

        if (theme != null && !theme.isBlank()) {
            Optional<SystemFeatureToggleEntity> themeToggle = systemFeatureToggleRepository
                    .findByFeatureKeyAndScopeTypeAndScopeValue(normalizedKey, FeatureScopeType.THEME, theme.trim());
            if (themeToggle.isPresent()) {
                return Boolean.TRUE.equals(themeToggle.get().getEnabled());
            }
        }

        if (country != null && !country.isBlank()) {
            Optional<SystemFeatureToggleEntity> countryToggle = systemFeatureToggleRepository
                    .findByFeatureKeyAndScopeTypeAndScopeValue(normalizedKey, FeatureScopeType.COUNTRY, country.trim());
            if (countryToggle.isPresent()) {
                return Boolean.TRUE.equals(countryToggle.get().getEnabled());
            }
        }

        Optional<SystemFeatureToggleEntity> global = systemFeatureToggleRepository
                .findByFeatureKeyAndScopeTypeAndScopeValueIsNull(normalizedKey, FeatureScopeType.GLOBAL);
        if (global.isPresent()) {
            return Boolean.TRUE.equals(global.get().getEnabled());
        }
        return !DEFAULT_OFF_FEATURE_KEYS.contains(normalizedKey);
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, Boolean>> matrix(List<String> featureKeys) {
        List<SystemFeatureToggleEntity> rows = systemFeatureToggleRepository.findAll();
        Map<String, List<SystemFeatureToggleEntity>> grouped = rows.stream()
                .collect(Collectors.groupingBy(row -> normalizeFeatureKey(row.getFeatureKey())));
        return featureKeys.stream()
                .collect(Collectors.toMap(
                        this::normalizeFeatureKey,
                        key -> buildScopedMap(grouped.getOrDefault(normalizeFeatureKey(key), List.of()))));
    }

    private Map<String, Boolean> buildScopedMap(List<SystemFeatureToggleEntity> rows) {
        Map<String, Boolean> map = rows.stream()
                .sorted(Comparator.comparing(SystemFeatureToggleEntity::getUpdatedAt).reversed())
                .collect(Collectors.toMap(
                        row -> row.getScopeType() + ":" + (row.getScopeValue() == null ? "*" : row.getScopeValue()),
                        row -> Boolean.TRUE.equals(row.getEnabled()),
                        (a, b) -> a));
        if (rows.isEmpty()) {
            map.put("GLOBAL:*", true);
        }
        return map;
    }

    private FeatureToggleDto toDto(SystemFeatureToggleEntity entity) {
        return FeatureToggleDto.builder()
                .id(entity.getId())
                .featureKey(entity.getFeatureKey())
                .enabled(entity.getEnabled())
                .scopeType(entity.getScopeType())
                .scopeValue(entity.getScopeValue())
                .reason(entity.getReason())
                .updatedBy(entity.getUpdatedBy())
                .updatedAt(entity.getUpdatedAt())
                .traceId(entity.getTraceId())
                .build();
    }

    private String normalizeFeatureKey(String value) {
        String normalized = String.valueOf(value)
                .trim()
                .toUpperCase(Locale.ROOT);
        if ("AUTO_ORDER_WITH_APPROVAL".equals(normalized)) {
            return "AUTO_ORDER_WITH_ADMIN_APPROVAL";
        }
        return normalized;
    }

    private String normalizeScopeValue(FeatureScopeType scopeType, String scopeValue) {
        if (scopeType == FeatureScopeType.GLOBAL) {
            return null;
        }
        return trimToNull(scopeValue);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }
}

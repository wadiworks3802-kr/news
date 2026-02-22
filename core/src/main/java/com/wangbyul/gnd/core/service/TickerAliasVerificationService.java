package com.wangbyul.gnd.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AssetVerificationStatusType;
import com.wangbyul.gnd.core.domain.AuditSeverityType;
import com.wangbyul.gnd.core.domain.TickerAliasAuditEntity;
import com.wangbyul.gnd.core.domain.TickerAliasCheckType;
import com.wangbyul.gnd.core.domain.TickerAliasDictionaryEntity;
import com.wangbyul.gnd.core.domain.TickerAliasType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.TickerAliasAuditRepository;
import com.wangbyul.gnd.core.repository.TickerAliasDictionaryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티커 별칭 사전 검증 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
@RequiredArgsConstructor
public class TickerAliasVerificationService {

    private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{1,15}$");

    private final TickerAliasDictionaryRepository tickerAliasDictionaryRepository;
    private final TickerAliasAuditRepository tickerAliasAuditRepository;
    private final AssetUniverseRepository assetUniverseRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TickerAliasVerificationResult verifyAll(String triggeredBy) {
        bootstrapAliasesIfMissing();

        List<TickerAliasDictionaryEntity> aliases = tickerAliasDictionaryRepository.findByActiveTrueOrderByAliasValueAsc();
        Map<String, List<TickerAliasDictionaryEntity>> byAlias = aliases.stream()
                .collect(java.util.stream.Collectors.groupingBy(alias -> normalize(alias.getAliasValue())));
        Map<String, List<TickerAliasDictionaryEntity>> byAsset = aliases.stream()
                .collect(java.util.stream.Collectors.groupingBy(TickerAliasDictionaryEntity::getAssetCode));

        int passed = 0;
        int failed = 0;
        int errorCount = 0;
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Boolean> assetHasError = new HashMap<>();

        for (TickerAliasDictionaryEntity alias : aliases) {
            List<AuditEntry> entries = validateAlias(alias, byAlias, byAsset);
            for (AuditEntry entry : entries) {
                TickerAliasAuditEntity audit = new TickerAliasAuditEntity();
                audit.setAssetCode(alias.getAssetCode());
                audit.setAliasValue(alias.getAliasValue());
                audit.setAliasType(alias.getAliasType());
                audit.setCheckType(entry.checkType());
                audit.setSeverity(entry.severity());
                audit.setValid(entry.valid());
                audit.setDetailJson(toJson(entry.detail()));
                audit.setTraceId(traceId());
                tickerAliasAuditRepository.save(audit);

                if (entry.valid()) {
                    passed++;
                } else {
                    failed++;
                }
                if (entry.severity() == AuditSeverityType.ERROR && !entry.valid()) {
                    errorCount++;
                    assetHasError.put(alias.getAssetCode(), true);
                }
            }
        }

        List<AssetUniverseEntity> assets = assetUniverseRepository.findAll();
        for (AssetUniverseEntity asset : assets) {
            boolean hasAlias = byAsset.containsKey(asset.getAssetCode()) && !byAsset.get(asset.getAssetCode()).isEmpty();
            boolean hasError = assetHasError.getOrDefault(asset.getAssetCode(), false);
            asset.setLastVerifiedAt(now);
            if (!hasAlias) {
                asset.setVerificationStatus(AssetVerificationStatusType.UNVERIFIED);
            } else if (hasError) {
                asset.setVerificationStatus(AssetVerificationStatusType.FAILED);
            } else {
                asset.setVerificationStatus(AssetVerificationStatusType.VERIFIED);
            }
        }
        assetUniverseRepository.saveAll(assets);

        return new TickerAliasVerificationResult(
                aliases.size(),
                passed,
                failed,
                errorCount,
                traceId(),
                triggeredBy,
                now);
    }

    public Map<String, Object> diagnostics(int limit) {
        List<TickerAliasAuditEntity> rows = tickerAliasAuditRepository.findTop300ByOrderByAuditTimeUtcDesc();
        List<TickerAliasAuditEntity> limited = rows.stream().limit(Math.max(1, limit)).toList();
        long failed = rows.stream().filter(row -> !Boolean.TRUE.equals(row.getValid())).count();
        long errors = rows.stream()
                .filter(row -> !Boolean.TRUE.equals(row.getValid()) && row.getSeverity() == AuditSeverityType.ERROR)
                .count();
        long verifiedAssets = assetUniverseRepository.findAll().stream()
                .filter(asset -> asset.getVerificationStatus() == AssetVerificationStatusType.VERIFIED)
                .count();
        long failedAssets = assetUniverseRepository.findAll().stream()
                .filter(asset -> asset.getVerificationStatus() == AssetVerificationStatusType.FAILED)
                .count();

        List<Map<String, Object>> items = limited.stream()
                .map(row -> Map.<String, Object>of(
                        "id", row.getId(),
                        "audit_time_utc", row.getAuditTimeUtc(),
                        "asset_code", row.getAssetCode() == null ? "" : row.getAssetCode(),
                        "alias_value", row.getAliasValue() == null ? "" : row.getAliasValue(),
                        "alias_type", row.getAliasType() == null ? "" : row.getAliasType().name(),
                        "check_type", row.getCheckType() == null ? "" : row.getCheckType().name(),
                        "severity", row.getSeverity() == null ? "" : row.getSeverity().name(),
                        "valid", Boolean.TRUE.equals(row.getValid()),
                        "detail_json", row.getDetailJson() == null ? "{}" : row.getDetailJson()))
                .toList();

        return Map.of(
                "total_audit_rows", rows.size(),
                "failed_rows", failed,
                "error_rows", errors,
                "verified_assets", verifiedAssets,
                "failed_assets", failedAssets,
                "items", items);
    }

    private List<AuditEntry> validateAlias(
            TickerAliasDictionaryEntity alias,
            Map<String, List<TickerAliasDictionaryEntity>> byAlias,
            Map<String, List<TickerAliasDictionaryEntity>> byAsset) {
        List<AuditEntry> entries = new ArrayList<>();
        String aliasValue = alias.getAliasValue() == null ? "" : alias.getAliasValue().trim();
        String country = alias.getCountry() == null ? "" : alias.getCountry().toUpperCase(Locale.ROOT);

        boolean formatValid = alias.getAliasType() == TickerAliasType.TICKER
                ? TICKER_PATTERN.matcher(aliasValue.toUpperCase(Locale.ROOT)).matches()
                : aliasValue.length() >= 2;
        entries.add(new AuditEntry(
                TickerAliasCheckType.FORMAT,
                formatValid,
                formatValid ? AuditSeverityType.INFO : AuditSeverityType.ERROR,
                Map.of("alias_value", aliasValue)));

        boolean exchangeValid = isExchangeCompatible(country, alias.getExchangeCode());
        entries.add(new AuditEntry(
                TickerAliasCheckType.EXCHANGE,
                exchangeValid,
                exchangeValid ? AuditSeverityType.INFO : AuditSeverityType.WARN,
                Map.of("country", country, "exchange_code", alias.getExchangeCode())));

        boolean nameMappingValid = validateNameMapping(alias);
        entries.add(new AuditEntry(
                TickerAliasCheckType.NAME_MAPPING,
                nameMappingValid,
                nameMappingValid ? AuditSeverityType.INFO : AuditSeverityType.WARN,
                Map.of("asset_code", alias.getAssetCode(), "alias_type", alias.getAliasType() == null ? "" : alias.getAliasType().name())));

        List<TickerAliasDictionaryEntity> conflicts = byAlias.getOrDefault(normalize(aliasValue), List.of());
        Set<String> conflictAssets = conflicts.stream().map(TickerAliasDictionaryEntity::getAssetCode).collect(java.util.stream.Collectors.toSet());
        boolean duplicateConflict = conflictAssets.size() > 1;
        entries.add(new AuditEntry(
                TickerAliasCheckType.DUPLICATE_ALIAS,
                !duplicateConflict,
                duplicateConflict ? AuditSeverityType.ERROR : AuditSeverityType.INFO,
                Map.of("conflict_assets", conflictAssets)));

        boolean localeConflict = hasLocaleConflict(alias, byAsset.getOrDefault(alias.getAssetCode(), List.of()));
        entries.add(new AuditEntry(
                TickerAliasCheckType.LOCALE_CONFLICT,
                !localeConflict,
                localeConflict ? AuditSeverityType.WARN : AuditSeverityType.INFO,
                Map.of("asset_code", alias.getAssetCode(), "alias_value", aliasValue)));
        return entries;
    }

    private boolean validateNameMapping(TickerAliasDictionaryEntity alias) {
        if (alias.getAliasType() == null) {
            return false;
        }
        if (alias.getAliasType() == TickerAliasType.TICKER) {
            return alias.getAliasValue() != null && !alias.getAliasValue().isBlank();
        }
        if (alias.getAsset() == null || alias.getAsset().getAssetName() == null) {
            return false;
        }
        String assetName = alias.getAsset().getAssetName().toLowerCase(Locale.ROOT);
        String aliasValue = alias.getAliasValue() == null ? "" : alias.getAliasValue().toLowerCase(Locale.ROOT);
        return assetName.contains(aliasValue) || aliasValue.contains(assetName.substring(0, Math.min(3, assetName.length())));
    }

    private boolean hasLocaleConflict(TickerAliasDictionaryEntity current, List<TickerAliasDictionaryEntity> sameAssetAliases) {
        String normalized = normalize(current.getAliasValue());
        Set<TickerAliasType> types = sameAssetAliases.stream()
                .filter(alias -> normalize(alias.getAliasValue()).equals(normalized))
                .map(TickerAliasDictionaryEntity::getAliasType)
                .collect(java.util.stream.Collectors.toSet());
        return types.size() > 1;
    }

    private boolean isExchangeCompatible(String country, String exchangeCode) {
        if (exchangeCode == null || exchangeCode.isBlank()) {
            return true;
        }
        String ex = exchangeCode.toUpperCase(Locale.ROOT).trim();
        return switch (country) {
            case "KR" -> ex.startsWith("KRX") || ex.startsWith("KOSPI") || ex.startsWith("KOSDAQ");
            case "US" -> ex.startsWith("NYSE") || ex.startsWith("NASDAQ") || ex.startsWith("AMEX");
            case "JP" -> ex.startsWith("TSE");
            case "UK" -> ex.startsWith("LSE");
            case "DE" -> ex.startsWith("XETRA") || ex.startsWith("FRA");
            default -> true;
        };
    }

    private void bootstrapAliasesIfMissing() {
        if (!tickerAliasDictionaryRepository.findByActiveTrueOrderByAliasValueAsc().isEmpty()) {
            return;
        }
        List<AssetUniverseEntity> assets = assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc();
        List<TickerAliasDictionaryEntity> aliases = new ArrayList<>();
        for (AssetUniverseEntity asset : assets) {
            TickerAliasDictionaryEntity tickerAlias = new TickerAliasDictionaryEntity();
            tickerAlias.setAssetCode(asset.getAssetCode());
            tickerAlias.setAliasValue(asset.getAssetCode());
            tickerAlias.setAliasType(TickerAliasType.TICKER);
            tickerAlias.setCountry(asset.getCountry());
            tickerAlias.setExchangeCode(guessExchangeCode(asset.getCountry()));
            aliases.add(tickerAlias);

            if (asset.getAssetName() != null && !asset.getAssetName().isBlank()) {
                TickerAliasDictionaryEntity enAlias = new TickerAliasDictionaryEntity();
                enAlias.setAssetCode(asset.getAssetCode());
                enAlias.setAliasValue(asset.getAssetName());
                enAlias.setAliasType(TickerAliasType.EN_NAME);
                enAlias.setCountry(asset.getCountry());
                enAlias.setExchangeCode(guessExchangeCode(asset.getCountry()));
                aliases.add(enAlias);
            }
        }
        tickerAliasDictionaryRepository.saveAll(aliases);
    }

    private String guessExchangeCode(String country) {
        return switch (country == null ? "" : country.toUpperCase(Locale.ROOT)) {
            case "KR" -> "KRX";
            case "US" -> "NASDAQ";
            case "JP" -> "TSE";
            case "UK" -> "LSE";
            case "DE" -> "XETRA";
            default -> "UNKNOWN";
        };
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    private record AuditEntry(
            TickerAliasCheckType checkType,
            boolean valid,
            AuditSeverityType severity,
            Map<String, Object> detail) {
    }

    public record TickerAliasVerificationResult(
            int aliasCount,
            int passedChecks,
            int failedChecks,
            int errorCount,
            String traceId,
            String triggeredBy,
            OffsetDateTime generatedAt) {
    }
}

package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.SignalAuditEngineType;
import com.wangbyul.gnd.core.domain.SignalAuditLogEntity;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.SignalAuditLogRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시그널 감사 로그 저장 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class SignalAuditLogService {

    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"(?:token|access_token|refresh_token|password|secret|api[_-]?key|authorization)\"\\s*:\\s*\")([^\"]+)(\")");

    private final SignalAuditLogRepository signalAuditLogRepository;
    private final ObjectMapper objectMapper;

    public SignalAuditLogService(SignalAuditLogRepository signalAuditLogRepository, ObjectMapper objectMapper) {
        this.signalAuditLogRepository = signalAuditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * FUSION 엔진 결과 감사 로그를 저장한다.
     */
    @Transactional
    public void saveFusionAuditLog(
            TradingSignalEntity signal,
            AssetUniverseEntity asset,
            SignalActionType decisionBeforeRisk,
            SignalActionType decisionAfterRisk,
            BigDecimal confidenceBefore,
            BigDecimal confidenceAfter,
            Map<String, Object> inputSnapshot,
            Map<String, Object> ruleHits,
            Object riskChecksPayload,
            String blockedReason,
            String modelVersion) {
        if (signal == null || asset == null) {
            return;
        }
        SignalAuditLogEntity entity = new SignalAuditLogEntity();
        entity.setSignalId(signal.getId());
        entity.setAssetCode(asset.getAssetCode());
        entity.setEngineType(SignalAuditEngineType.FUSION);
        entity.setInputSnapshotJson(maskSensitiveJson(toJson(inputSnapshot)));
        entity.setRuleHitsJson(maskSensitiveJson(toJson(ruleHits)));
        entity.setRiskChecksJson(maskSensitiveJson(toJson(
                riskChecksPayload == null ? Map.of("risk_checks", java.util.List.of()) : riskChecksPayload)));
        entity.setTopPositiveFactorsJson(maskSensitiveJson(signal.getTopPositiveFactorsJson()));
        entity.setTopNegativeFactorsJson(maskSensitiveJson(signal.getTopNegativeFactorsJson()));
        entity.setExplainText(signal.getExplainText());
        entity.setNewsAlignmentResultJson(maskSensitiveJson(signal.getNewsAlignmentResultJson()));
        entity.setDataFreshnessJson(maskSensitiveJson(signal.getDataFreshnessJson()));
        entity.setDedupResultJson(maskSensitiveJson(signal.getDedupResultJson()));
        entity.setRagContextRefsJson(maskSensitiveJson(signal.getRagContextRefsJson()));
        entity.setDecisionBeforeRisk(decisionBeforeRisk);
        entity.setDecisionAfterRisk(decisionAfterRisk);
        entity.setBlockedReason(blockedReason);
        entity.setConfidenceBefore(confidenceBefore);
        entity.setConfidenceAfter(confidenceAfter);
        entity.setModelVersion(modelVersion);
        entity.setTraceId(traceId());
        signalAuditLogRepository.save(entity);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String maskSensitiveJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        return JSON_SECRET_PATTERN.matcher(json).replaceAll("$1***$3");
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }
}

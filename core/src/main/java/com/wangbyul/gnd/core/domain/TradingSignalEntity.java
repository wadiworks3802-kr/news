package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 뉴스+차트 결합 시그널 저장 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "trading_signal")
public class TradingSignalEntity {

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Column(name = "asset_code", nullable = false, length = 32)
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "country", nullable = false, length = 5)
    private String country;

    @Column(name = "theme", length = 64)
    private String theme;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    @Column(name = "signal_window", nullable = false, length = 16)
    private String signalWindow = "1h";

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 24)
    private SignalActionType action = SignalActionType.WATCH;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_regime", nullable = false, length = 24)
    private MarketRegimeType marketRegime = MarketRegimeType.MIXED;

    @Column(name = "good_news_probability", nullable = false, precision = 5, scale = 4)
    private BigDecimal goodNewsProbability = BigDecimal.valueOf(0.5d);

    @Column(name = "bad_news_probability", nullable = false, precision = 5, scale = 4)
    private BigDecimal badNewsProbability = BigDecimal.valueOf(0.5d);

    @Column(name = "news_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal newsConfidence = BigDecimal.valueOf(0.5d);

    @Column(name = "probability_reason_breakdown_json", nullable = false, columnDefinition = "jsonb")
    @Comment("RULE_V1 확률 계산 근거(표본/필터/감쇠/질문응답) JSON")
    private String probabilityReasonBreakdownJson = "{}";

    @Column(name = "chart_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal chartConfidence = BigDecimal.valueOf(0.5d);

    @Column(name = "combined_confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal combinedConfidence = BigDecimal.valueOf(0.5d);

    @Column(name = "weekly_context_score", nullable = false, precision = 8, scale = 4)
    private BigDecimal weeklyContextScore = BigDecimal.ZERO;

    @Column(name = "scalp_signal_score", nullable = false, precision = 8, scale = 4)
    private BigDecimal scalpSignalScore = BigDecimal.ZERO;

    @Column(name = "swing_signal_score", nullable = false, precision = 8, scale = 4)
    private BigDecimal swingSignalScore = BigDecimal.ZERO;

    @Column(name = "position_management_signal", nullable = false, precision = 8, scale = 4)
    private BigDecimal positionManagementSignal = BigDecimal.ZERO;

    @Column(name = "discovery_score", nullable = false, precision = 8, scale = 4)
    private BigDecimal discoveryScore = BigDecimal.ZERO;

    @Column(name = "sell_pressure_detected", nullable = false)
    private Boolean sellPressureDetected = false;

    @Column(name = "sell_pressure_is_negative", nullable = false)
    private Boolean sellPressureIsNegative = false;

    @Column(name = "buy_pressure_detected", nullable = false)
    private Boolean buyPressureDetected = false;

    @Column(name = "buy_pressure_is_positive", nullable = false)
    private Boolean buyPressureIsPositive = false;

    @Column(name = "volume_regime_same", nullable = false)
    private Boolean volumeRegimeSame = false;

    @Column(name = "pressure_reason_json", nullable = false, columnDefinition = "jsonb")
    @Comment("PRESSURE_RULESET_V1 계산 근거(연속성/거래량 비교) JSON")
    private String pressureReasonJson = "{}";

    @Column(name = "avg_down_allowed", nullable = false)
    private Boolean avgDownAllowed = false;

    @Column(name = "avg_down_stage", nullable = false)
    private Integer avgDownStage = 0;

    @Column(name = "avg_down_reason", columnDefinition = "text")
    private String avgDownReason;

    @Column(name = "avg_down_next_buy_ratio", precision = 8, scale = 4)
    private BigDecimal avgDownNextBuyRatio;

    @Column(name = "reanalysis_lock_required", nullable = false)
    private Boolean reanalysisLockRequired = false;

    @Column(name = "reanalysis_lock_until")
    private OffsetDateTime reanalysisLockUntil;

    @Column(name = "risk_checks", nullable = false, columnDefinition = "jsonb")
    private String riskChecks = "[]";

    @Column(name = "blocked_reason", columnDefinition = "text")
    private String blockedReason;

    @Column(name = "reason_json", nullable = false, columnDefinition = "jsonb")
    @Comment("시그널 생성 전체 근거 요약 JSON")
    private String reasonJson = "{}";

    @Column(name = "top_positive_factors_json", nullable = false, columnDefinition = "jsonb")
    @Comment("상위 긍정 요인 목록 JSON")
    private String topPositiveFactorsJson = "[]";

    @Column(name = "top_negative_factors_json", nullable = false, columnDefinition = "jsonb")
    @Comment("상위 부정 요인 목록 JSON")
    private String topNegativeFactorsJson = "[]";

    @Column(name = "explain_text", columnDefinition = "text")
    @Comment("상세보기용 설명 텍스트(왜 WATCH/HOLD/BUY인지)")
    private String explainText;

    @Column(name = "news_alignment_result_json", nullable = false, columnDefinition = "jsonb")
    @Comment("뉴스-가격 시간정렬 검증 결과 JSON")
    private String newsAlignmentResultJson = "{}";

    @Column(name = "data_freshness_json", nullable = false, columnDefinition = "jsonb")
    @Comment("입력 데이터 신선도(뉴스/시세/거래량) JSON")
    private String dataFreshnessJson = "{}";

    @Column(name = "dedup_result_json", nullable = false, columnDefinition = "jsonb")
    @Comment("중복기사/중복링크 처리 결과 JSON")
    private String dedupResultJson = "{}";

    @Column(name = "rag_context_refs_json", nullable = false, columnDefinition = "jsonb")
    @Comment("향후 RAG 연동용 참조 컨텍스트 목록(JSON 배열, 현재 빈값 허용)")
    private String ragContextRefsJson = "[]";

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion = "rule-heuristic-v2";

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (generatedAt == null) {
            generatedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (probabilityReasonBreakdownJson == null || probabilityReasonBreakdownJson.isBlank()) {
            probabilityReasonBreakdownJson = "{}";
        }
        if (pressureReasonJson == null || pressureReasonJson.isBlank()) {
            pressureReasonJson = "{}";
        }
        if (reasonJson == null || reasonJson.isBlank()) {
            reasonJson = "{}";
        }
        if (topPositiveFactorsJson == null || topPositiveFactorsJson.isBlank()) {
            topPositiveFactorsJson = "[]";
        }
        if (topNegativeFactorsJson == null || topNegativeFactorsJson.isBlank()) {
            topNegativeFactorsJson = "[]";
        }
        if (newsAlignmentResultJson == null || newsAlignmentResultJson.isBlank()) {
            newsAlignmentResultJson = "{}";
        }
        if (dataFreshnessJson == null || dataFreshnessJson.isBlank()) {
            dataFreshnessJson = "{}";
        }
        if (dedupResultJson == null || dedupResultJson.isBlank()) {
            dedupResultJson = "{}";
        }
        if (ragContextRefsJson == null || ragContextRefsJson.isBlank()) {
            ragContextRefsJson = "[]";
        }
    }
}

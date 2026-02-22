package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 시그널 의사결정 감사 로그 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "signal_audit_log",
        indexes = {
                @Index(name = "idx_signal_audit_asset_time_desc", columnList = "asset_code,audit_time_utc"),
                @Index(name = "idx_signal_audit_engine_type", columnList = "engine_type"),
                @Index(name = "idx_signal_audit_signal_id", columnList = "signal_id")
        })
@Comment("시그널 생성 의사결정 과정 감사 로그")
public class SignalAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("시그널 감사 PK")
    private Long id;

    @Column(name = "signal_id", length = 64)
    @Comment("연결된 시그널 ID")
    private String signalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", referencedColumnName = "id", insertable = false, updatable = false)
    private TradingSignalEntity signal;

    @Column(name = "asset_code", nullable = false, length = 32)
    @Comment("분석 대상 자산 코드")
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "audit_time_utc", nullable = false)
    @Comment("감사 로그 생성 시각(UTC)")
    private OffsetDateTime auditTimeUtc;

    @Enumerated(EnumType.STRING)
    @Column(name = "engine_type", nullable = false, length = 24)
    @Comment("엔진 타입(SCALP/SWING/POSITION/DISCOVERY/FUSION)")
    private SignalAuditEngineType engineType = SignalAuditEngineType.FUSION;

    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @Comment("입력값 스냅샷(뉴스/가격/거래량/지표)")
    private String inputSnapshotJson = "{}";

    @Column(name = "rule_hits_json", nullable = false, columnDefinition = "jsonb")
    @Comment("룰셋 히트 목록/결과")
    private String ruleHitsJson = "{}";

    @Column(name = "risk_checks_json", nullable = false, columnDefinition = "jsonb")
    @Comment("리스크 점검 결과")
    private String riskChecksJson = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_before_risk", length = 24)
    @Comment("리스크 적용 전 의사결정")
    private SignalActionType decisionBeforeRisk;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_after_risk", length = 24)
    @Comment("리스크 적용 후 의사결정")
    private SignalActionType decisionAfterRisk;

    @Column(name = "blocked_reason", columnDefinition = "text")
    @Comment("차단 사유")
    private String blockedReason;

    @Column(name = "confidence_before", precision = 8, scale = 6)
    @Comment("리스크 적용 전 신뢰도")
    private BigDecimal confidenceBefore;

    @Column(name = "confidence_after", precision = 8, scale = 6)
    @Comment("리스크 적용 후 신뢰도")
    private BigDecimal confidenceAfter;

    @Column(name = "model_version", length = 64)
    @Comment("룰/모델 버전")
    private String modelVersion;

    @Column(name = "trace_id", length = 64)
    @Comment("요청/배치 추적 ID")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (auditTimeUtc == null) {
            auditTimeUtc = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (inputSnapshotJson == null || inputSnapshotJson.isBlank()) {
            inputSnapshotJson = "{}";
        }
        if (ruleHitsJson == null || ruleHitsJson.isBlank()) {
            ruleHitsJson = "{}";
        }
        if (riskChecksJson == null || riskChecksJson.isBlank()) {
            riskChecksJson = "{}";
        }
    }
}

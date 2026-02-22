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
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 시장데이터 누락/지연/이상 상세 이벤트 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "market_data_gap_event",
        indexes = {
                @Index(name = "idx_gap_event_time_desc", columnList = "event_time_utc"),
                @Index(name = "idx_gap_event_asset_type", columnList = "asset_code,event_type"),
                @Index(name = "idx_gap_event_severity_resolved", columnList = "severity,resolved")
        })
@Comment("시장데이터 누락/지연/이상 이벤트 상세 로그")
public class MarketDataGapEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("갭 이벤트 PK")
    private Long id;

    @Column(name = "event_time_utc", nullable = false)
    @Comment("이벤트 감지 시각(UTC)")
    private OffsetDateTime eventTimeUtc;

    @Column(name = "asset_code", length = 32)
    @Comment("관련 자산 코드")
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "provider", nullable = false, length = 40)
    @Comment("시장데이터 공급자 코드")
    private String provider;

    @Column(name = "timeframe", length = 16)
    @Comment("바 데이터 타임프레임(quote 이벤트는 null 가능)")
    private String timeframe;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    @Comment("이벤트 유형(MISSING_BAR, DELAYED_QUOTE 등)")
    private MarketGapEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    @Comment("심각도(INFO/WARN/ERROR)")
    private AuditSeverityType severity = AuditSeverityType.INFO;

    @Column(name = "expected_time_utc")
    @Comment("기대 수집 시각")
    private OffsetDateTime expectedTimeUtc;

    @Column(name = "actual_time_utc")
    @Comment("실제 수집 시각")
    private OffsetDateTime actualTimeUtc;

    @Column(name = "delay_seconds")
    @Comment("지연 시간(초)")
    private Long delaySeconds;

    @Column(name = "detail_json", nullable = false, columnDefinition = "jsonb")
    @Comment("이벤트 상세 원인/샘플 JSON")
    private String detailJson = "{}";

    @Column(name = "resolved", nullable = false)
    @Comment("해결 여부")
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    @Comment("해결 처리 시각")
    private OffsetDateTime resolvedAt;

    @Column(name = "trace_id", length = 64)
    @Comment("요청/배치 추적 ID")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (eventTimeUtc == null) {
            eventTimeUtc = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (detailJson == null || detailJson.isBlank()) {
            detailJson = "{}";
        }
    }
}

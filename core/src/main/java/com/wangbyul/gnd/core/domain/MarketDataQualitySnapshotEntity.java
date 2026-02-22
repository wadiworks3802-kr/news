package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 시장데이터 수집 품질 스냅샷 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "market_data_quality_snapshot",
        indexes = {
                @Index(name = "idx_mdq_snapshot_time_desc", columnList = "snapshot_time_utc"),
                @Index(name = "idx_mdq_provider_country_theme", columnList = "provider,country,theme"),
                @Index(name = "idx_mdq_quality_score", columnList = "quality_score")
        })
@Comment("시장데이터 수집 품질 스냅샷 집계 테이블")
public class MarketDataQualitySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("품질 스냅샷 PK")
    private Long id;

    @Column(name = "snapshot_time_utc", nullable = false)
    @Comment("품질 집계 시각(UTC)")
    private OffsetDateTime snapshotTimeUtc;

    @Column(name = "provider", nullable = false, length = 40)
    @Comment("시장데이터 공급자 코드")
    private String provider;

    @Column(name = "country", nullable = false, length = 10)
    @Comment("국가 코드(KR, US 등)")
    private String country;

    @Column(name = "theme", length = 64)
    @Comment("테마 코드(선택)")
    private String theme;

    @Column(name = "asset_count_expected", nullable = false)
    @Comment("해당 구간 기대 자산 수")
    private Integer assetCountExpected = 0;

    @Column(name = "asset_count_collected", nullable = false)
    @Comment("수집 성공 자산 수")
    private Integer assetCountCollected = 0;

    @Column(name = "quote_count_expected", nullable = false)
    @Comment("기대 시세 건수")
    private Integer quoteCountExpected = 0;

    @Column(name = "quote_count_collected", nullable = false)
    @Comment("수집 시세 건수")
    private Integer quoteCountCollected = 0;

    @Column(name = "bar_count_expected", nullable = false)
    @Comment("기대 바 데이터 건수")
    private Integer barCountExpected = 0;

    @Column(name = "bar_count_collected", nullable = false)
    @Comment("수집 바 데이터 건수")
    private Integer barCountCollected = 0;

    @Column(name = "missing_rate", nullable = false, precision = 8, scale = 6)
    @Comment("누락률(0~1)")
    private BigDecimal missingRate = BigDecimal.ZERO;

    @Column(name = "delay_rate", nullable = false, precision = 8, scale = 6)
    @Comment("지연률(0~1)")
    private BigDecimal delayRate = BigDecimal.ZERO;

    @Column(name = "duplicate_rate", nullable = false, precision = 8, scale = 6)
    @Comment("중복률(0~1)")
    private BigDecimal duplicateRate = BigDecimal.ZERO;

    @Column(name = "anomaly_rate", nullable = false, precision = 8, scale = 6)
    @Comment("이상치율(0~1)")
    private BigDecimal anomalyRate = BigDecimal.ZERO;

    @Column(name = "quality_score", nullable = false, precision = 6, scale = 2)
    @Comment("종합 품질 점수(0~100)")
    private BigDecimal qualityScore = BigDecimal.ZERO;

    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    @Comment("품질 원인/샘플/권고사항 JSON")
    private String summaryJson = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @Column(name = "trace_id", length = 64)
    @Comment("요청/배치 추적 ID")
    private String traceId;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (snapshotTimeUtc == null) {
            snapshotTimeUtc = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (summaryJson == null || summaryJson.isBlank()) {
            summaryJson = "{}";
        }
    }
}

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
 * 뉴스-자산 매핑 품질 리포트 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "mapping_quality_report",
        indexes = {
                @Index(name = "idx_mapping_quality_report_scope_time", columnList = "country,theme,report_time_utc"),
                @Index(name = "idx_mapping_quality_report_score", columnList = "direct_match_precision,theme_match_false_positive_rate")
        })
@Comment("뉴스-자산 매핑 품질 진단 리포트")
public class MappingQualityReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("매핑 품질 리포트 PK")
    private Long id;

    @Column(name = "report_time_utc", nullable = false)
    @Comment("리포트 생성 시각")
    private OffsetDateTime reportTimeUtc;

    @Column(name = "country", length = 10)
    @Comment("국가 코드")
    private String country;

    @Column(name = "theme", length = 64)
    @Comment("테마 코드")
    private String theme;

    @Column(name = "sample_size", nullable = false)
    @Comment("진단 대상 링크 샘플 수")
    private Integer sampleSize = 0;

    @Column(name = "direct_match_precision", nullable = false, precision = 8, scale = 6)
    @Comment("직접 언급 매칭 정밀도")
    private BigDecimal directMatchPrecision = BigDecimal.ZERO;

    @Column(name = "theme_match_false_positive_rate", nullable = false, precision = 8, scale = 6)
    @Comment("테마 매칭 오탐률")
    private BigDecimal themeMatchFalsePositiveRate = BigDecimal.ZERO;

    @Column(name = "country_theme_overexpansion_rate", nullable = false, precision = 8, scale = 6)
    @Comment("국가/테마 매칭 과확장률")
    private BigDecimal countryThemeOverexpansionRate = BigDecimal.ZERO;

    @Column(name = "link_score_avg", nullable = false, precision = 8, scale = 6)
    @Comment("링크 점수 평균")
    private BigDecimal linkScoreAvg = BigDecimal.ZERO;

    @Column(name = "link_score_p50", nullable = false, precision = 8, scale = 6)
    @Comment("링크 점수 P50")
    private BigDecimal linkScoreP50 = BigDecimal.ZERO;

    @Column(name = "link_score_p90", nullable = false, precision = 8, scale = 6)
    @Comment("링크 점수 P90")
    private BigDecimal linkScoreP90 = BigDecimal.ZERO;

    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    @Comment("리포트 상세/샘플 JSON")
    private String summaryJson = "{}";

    @Column(name = "trace_id", length = 64)
    @Comment("추적 ID")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (reportTimeUtc == null) {
            reportTimeUtc = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (summaryJson == null || summaryJson.isBlank()) {
            summaryJson = "{}";
        }
    }
}

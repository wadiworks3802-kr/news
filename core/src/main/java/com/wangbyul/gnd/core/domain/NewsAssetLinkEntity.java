package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * 뉴스-자산 연결 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "news_asset_link")
public class NewsAssetLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "news_id", nullable = false, length = 64)
    @Comment("연결된 뉴스 ID")
    private String newsId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "news_id", referencedColumnName = "id", insertable = false, updatable = false)
    private NewsEntity news;

    @Column(name = "asset_code", nullable = false, length = 32)
    @Comment("연결된 자산 코드")
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 16)
    @Comment("링크 유형(DIRECT/THEME)")
    private NewsLinkType linkType = NewsLinkType.DIRECT;

    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    @Comment("기존 호환용 링크 신뢰도(신규 link_confidence와 동기화)")
    private BigDecimal confidence = BigDecimal.valueOf(0.5d);

    @Column(name = "link_confidence", nullable = false, precision = 5, scale = 4)
    @Comment("뉴스-자산 매핑 링크 신뢰도(0~1)")
    private BigDecimal linkConfidence = BigDecimal.valueOf(0.5d);

    @Column(name = "link_method", nullable = false, length = 16)
    @Comment("매핑 방법(RULE, DICT, NER, LLM, HYBRID)")
    private String linkMethod = "RULE";

    @Column(name = "keyword_hits_json", nullable = false, columnDefinition = "jsonb")
    @Comment("별칭/테마/이벤트 키워드 매칭 근거 JSON")
    private String keywordHitsJson = "{}";

    @Column(name = "ticker_alias_hit", length = 200)
    @Comment("실제 매칭된 티커/별칭 문자열")
    private String tickerAliasHit;

    @Column(name = "theme_match_score", nullable = false, precision = 5, scale = 4)
    @Comment("테마 키워드 매칭 점수(0~1)")
    private BigDecimal themeMatchScore = BigDecimal.ZERO;

    @Column(name = "event_type", length = 32)
    @Comment("이벤트 유형(EARNINGS, REGULATION, ORDER, INCIDENT, SUPPLY_CHAIN, MACRO 등)")
    private String eventType;

    @Column(name = "impact_direction", length = 16)
    @Comment("영향 방향(POSITIVE, NEGATIVE, MIXED, NEUTRAL)")
    private String impactDirection;

    @Column(name = "impact_horizon", length = 16)
    @Comment("영향 기간(INTRADAY, SHORT, MID, LONG)")
    private String impactHorizon;

    @Column(name = "trace_id", length = 64)
    @Comment("매핑 생성 trace_id")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (linkConfidence == null) {
            linkConfidence = confidence == null ? BigDecimal.valueOf(0.5d) : confidence;
        }
        if (confidence == null) {
            confidence = linkConfidence == null ? BigDecimal.valueOf(0.5d) : linkConfidence;
        }
        if (linkMethod == null || linkMethod.isBlank()) {
            linkMethod = "RULE";
        }
        if (keywordHitsJson == null || keywordHitsJson.isBlank()) {
            keywordHitsJson = "{}";
        }
        if (themeMatchScore == null) {
            themeMatchScore = BigDecimal.ZERO;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

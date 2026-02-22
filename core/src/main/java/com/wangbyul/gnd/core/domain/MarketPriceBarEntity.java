package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * OHLCV 시계열 바 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "market_price_bar")
@Comment("자산별 OHLCV 바 시계열 저장 테이블")
public class MarketPriceBarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_code", nullable = false, length = 32)
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "bar_time", nullable = false)
    @Comment("시장 이벤트 기준 bar 시각(기존 호환 컬럼)")
    private OffsetDateTime barTime;

    @Column(name = "bar_time_utc")
    @Comment("시장 이벤트 기준 bar 시각(UTC)")
    private OffsetDateTime barTimeUtc;

    @Column(name = "timeframe", nullable = false, length = 8)
    private String timeframe;

    @Column(name = "open_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal closePrice;

    @Column(name = "volume", nullable = false, precision = 24, scale = 4)
    private BigDecimal volume = BigDecimal.ZERO;

    @Column(name = "provider_name", length = 64)
    @Comment("수집 Provider 코드")
    private String providerName;

    @Column(name = "trace_id", length = 64)
    @Comment("수집 실행 trace_id (시장데이터 수집/진단 추적용)")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @Column(name = "ingested_at")
    @Comment("시스템 저장 시각(UTC)")
    private OffsetDateTime ingestedAt;

    @PrePersist
    public void prePersist() {
        if (barTimeUtc == null) {
            barTimeUtc = barTime;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (ingestedAt == null) {
            ingestedAt = createdAt;
        }
    }
}

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
 * 실시간 시세 스냅샷 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "market_quote_snapshot")
@Comment("자산별 실시간 호가/체결 스냅샷 저장 테이블")
public class MarketQuoteSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_code", nullable = false, length = 32)
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "snapshot_utc", nullable = false)
    @Comment("시세 스냅샷 시각(기존 호환 컬럼)")
    private OffsetDateTime snapshotUtc;

    @Column(name = "quote_time_utc")
    @Comment("시장 이벤트 기준 시세 시각(UTC)")
    private OffsetDateTime quoteTimeUtc;

    @Column(name = "last_price", precision = 18, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "change_pct", precision = 8, scale = 4)
    private BigDecimal changePct;

    @Column(name = "bid_price", precision = 18, scale = 6)
    private BigDecimal bidPrice;

    @Column(name = "ask_price", precision = 18, scale = 6)
    private BigDecimal askPrice;

    @Column(name = "bid_size", precision = 24, scale = 4)
    private BigDecimal bidSize;

    @Column(name = "ask_size", precision = 24, scale = 4)
    private BigDecimal askSize;

    @Column(name = "spread_pct", precision = 8, scale = 4)
    private BigDecimal spreadPct;

    @Column(name = "volume", precision = 24, scale = 4)
    private BigDecimal volume;

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
        if (snapshotUtc == null) {
            snapshotUtc = OffsetDateTime.now();
        }
        if (quoteTimeUtc == null) {
            quoteTimeUtc = snapshotUtc;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (ingestedAt == null) {
            ingestedAt = createdAt;
        }
    }
}

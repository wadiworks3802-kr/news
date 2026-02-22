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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 모의매매 보유 포지션 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "paper_trade_position")
public class PaperTradePositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_code", nullable = false, length = 32, unique = true)
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "avg_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal avgPrice = BigDecimal.ZERO;

    @Column(name = "invested_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal investedAmount = BigDecimal.ZERO;

    @Column(name = "current_price", precision = 18, scale = 6)
    private BigDecimal currentPrice;

    @Column(name = "pnl_pct", precision = 8, scale = 4)
    private BigDecimal pnlPct;

    @Column(name = "avg_down_stage", nullable = false)
    private Integer avgDownStage = 0;

    @Column(name = "buy_lock", nullable = false)
    private Boolean buyLock = false;

    @Column(name = "lock_reason", length = 120)
    private String lockReason;

    @Column(name = "lock_until")
    private OffsetDateTime lockUntil;

    @Column(name = "last_reanalysis_at")
    private OffsetDateTime lastReanalysisAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}


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

/**
 * 모의매매 주문 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "paper_trade_order")
public class PaperTradeOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_code", nullable = false, length = 32)
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "signal_id", length = 64)
    private String signalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", referencedColumnName = "id", insertable = false, updatable = false)
    private TradingSignalEntity signal;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_side", nullable = false, length = 8)
    private OrderSideType orderSide;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 16)
    private OrderType orderType = OrderType.MARKET;

    @Column(name = "request_ratio", precision = 8, scale = 4)
    private BigDecimal requestRatio;

    @Column(name = "request_amount", precision = 18, scale = 2)
    private BigDecimal requestAmount;

    @Column(name = "request_price", precision = 18, scale = 6)
    private BigDecimal requestPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrderStatusType status = OrderStatusType.REQUESTED;

    @Column(name = "blocked_reason", columnDefinition = "text")
    private String blockedReason;

    @Column(name = "risk_checks", nullable = false, columnDefinition = "jsonb")
    private String riskChecks = "[]";

    @Column(name = "executed_price", precision = 18, scale = 6)
    private BigDecimal executedPrice;

    @Column(name = "executed_amount", precision = 18, scale = 2)
    private BigDecimal executedAmount;

    @Column(name = "executed_at")
    private OffsetDateTime executedAt;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}


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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 티커/종목명 별칭 사전 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "ticker_alias_dictionary",
        indexes = {
                @Index(name = "idx_ticker_alias_lookup", columnList = "alias_value,alias_type,country"),
                @Index(name = "idx_ticker_alias_asset", columnList = "asset_code,alias_type"),
                @Index(name = "idx_ticker_alias_active", columnList = "active")
        })
@Comment("티커/한글명/영문명/약칭 별칭 사전")
public class TickerAliasDictionaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("티커 별칭 PK")
    private Long id;

    @Column(name = "asset_code", nullable = false, length = 32)
    @Comment("연결 자산 코드")
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "alias_value", nullable = false, length = 200)
    @Comment("별칭 값(티커/한글명/영문명/약칭)")
    private String aliasValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "alias_type", nullable = false, length = 30)
    @Comment("별칭 유형(TICKER, KO_NAME, EN_NAME, SHORT_NAME)")
    private TickerAliasType aliasType = TickerAliasType.TICKER;

    @Column(name = "exchange_code", length = 20)
    @Comment("거래소 코드(KRX, NASDAQ, NYSE 등)")
    private String exchangeCode;

    @Column(name = "country", length = 10)
    @Comment("국가 코드")
    private String country;

    @Column(name = "active", nullable = false)
    @Comment("별칭 활성 여부")
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("생성 시각")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("수정 시각")
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
        if (aliasType == null) {
            aliasType = TickerAliasType.TICKER;
        }
        if (active == null) {
            active = true;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}

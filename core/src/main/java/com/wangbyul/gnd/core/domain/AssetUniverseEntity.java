package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 분석/모의매매 대상 자산 마스터 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "asset_universe")
@Comment("뉴스/시장데이터/시그널 분석에 사용하는 자산 유니버스 마스터")
public class AssetUniverseEntity {

    @Id
    @Column(name = "asset_code", nullable = false, length = 32)
    private String assetCode;

    @Column(name = "asset_name", nullable = false, length = 160)
    private String assetName;

    @Column(name = "country", nullable = false, length = 5)
    private String country;

    @Column(name = "theme", length = 64)
    private String theme;

    @Column(name = "sector", length = 64)
    private String sector;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 16)
    private AssetType assetType = AssetType.STOCK;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "liquidity_score", precision = 8, scale = 4)
    private BigDecimal liquidityScore = BigDecimal.valueOf(0.5d);

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_source", length = 30)
    @Comment("유니버스 선정 방식(MANUAL, MARKET_CAP, VOLUME, THEME_LEADER, WATCHLIST, DISCOVERY)")
    private AssetSelectionSourceType selectionSource = AssetSelectionSourceType.MANUAL;

    @Column(name = "selection_score", precision = 5, scale = 2)
    @Comment("유니버스 선정 점수(0~100)")
    private BigDecimal selectionScore = BigDecimal.ZERO;

    @Column(name = "market_cap_rank")
    @Comment("국가/시장 기준 시가총액 순위")
    private Integer marketCapRank;

    @Column(name = "avg_volume_rank")
    @Comment("최근 거래량 기준 순위")
    private Integer avgVolumeRank;

    @Column(name = "is_core_asset", nullable = false)
    @Comment("국가/테마 대표 핵심 자산 여부")
    private Boolean isCoreAsset = false;

    @Column(name = "is_watchlist_asset", nullable = false)
    @Comment("사용자 관심자산 여부")
    private Boolean isWatchlistAsset = false;

    @Column(name = "display_weight", nullable = false)
    @Comment("UI 노출 우선순위 가중치")
    private Integer displayWeight = 0;

    @Column(name = "last_verified_at")
    @Comment("티커/종목명 매핑 검증 시각")
    private OffsetDateTime lastVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 20)
    @Comment("검증 상태(VERIFIED, UNVERIFIED, FAILED)")
    private AssetVerificationStatusType verificationStatus = AssetVerificationStatusType.UNVERIFIED;

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
        if (selectionSource == null) {
            selectionSource = AssetSelectionSourceType.MANUAL;
        }
        if (selectionScore == null) {
            selectionScore = BigDecimal.ZERO;
        }
        if (isCoreAsset == null) {
            isCoreAsset = false;
        }
        if (isWatchlistAsset == null) {
            isWatchlistAsset = false;
        }
        if (displayWeight == null) {
            displayWeight = 0;
        }
        if (verificationStatus == null) {
            verificationStatus = AssetVerificationStatusType.UNVERIFIED;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}

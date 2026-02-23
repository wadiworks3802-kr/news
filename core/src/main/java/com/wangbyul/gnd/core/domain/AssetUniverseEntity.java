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

    @Column(name = "country_code", length = 5)
    @Comment("정규화된 국가 코드(조회/진단/정책 필터용)")
    private String countryCode;

    @Column(name = "theme", length = 64)
    private String theme;

    @Column(name = "theme_code", length = 64)
    @Comment("정규화된 테마 코드(AI, SEMICONDUCTOR, ENERGY 등)")
    private String themeCode;

    @Column(name = "sector", length = 64)
    private String sector;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 16)
    private AssetType assetType = AssetType.STOCK;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "is_trade_enabled", nullable = false)
    @Comment("전략 엔진/모의매매에서 거래 후보로 사용할 수 있는지 여부")
    private Boolean isTradeEnabled = true;

    @Column(name = "liquidity_score", precision = 8, scale = 4)
    private BigDecimal liquidityScore = BigDecimal.valueOf(0.5d);

    @Enumerated(EnumType.STRING)
    @Column(name = "universe_layer", length = 24)
    @Comment("유니버스 레이어(CORE, WATCHLIST, THEME_LEADER, DISCOVERY)")
    private UniverseLayerType universeLayer = UniverseLayerType.CORE;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_source", length = 30)
    @Comment("유니버스 선정 방식(MANUAL, MARKET_CAP, VOLUME, THEME_LEADER, WATCHLIST, DISCOVERY)")
    private AssetSelectionSourceType selectionSource = AssetSelectionSourceType.MANUAL;

    @Column(name = "selection_score", precision = 5, scale = 2)
    @Comment("유니버스 선정 점수(0~100)")
    private BigDecimal selectionScore = BigDecimal.ZERO;

    @Column(name = "diversity_score", precision = 6, scale = 4)
    @Comment("다양성 점수(패밀리/테마/레이어 편중 억제 결과)")
    private BigDecimal diversityScore = BigDecimal.ZERO;

    @Column(name = "selection_reason", columnDefinition = "text")
    @Comment("유니버스 선정 근거 요약(진단/패널 메타용)")
    private String selectionReason;

    @Column(name = "strategy_scope", length = 64)
    @Comment("전략 패널 우선 노출 범위(SCALP, SWING, POSITION, DISCOVERY, SCALP_SWING, ALL)")
    private String strategyScope;

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

    @Column(name = "is_user_watch", nullable = false)
    @Comment("사용자 직접 지정 관심종목 여부(프론트/정책용 별도 플래그)")
    private Boolean isUserWatch = false;

    @Column(name = "display_weight", nullable = false)
    @Comment("UI 노출 우선순위 가중치")
    private Integer displayWeight = 0;

    @Column(name = "dup_exposure_cooldown_minutes", nullable = false)
    @Comment("중복 노출 억제를 위한 쿨다운 분 단위")
    private Integer dupExposureCooldownMinutes = 0;

    @Column(name = "last_signal_generated_at")
    @Comment("가장 최근 시그널 생성 시각")
    private OffsetDateTime lastSignalGeneratedAt;

    @Column(name = "last_panel_exposed_at")
    @Comment("가장 최근 전략 패널 노출 시각(중복 억제용)")
    private OffsetDateTime lastPanelExposedAt;

    @Column(name = "panel_exposure_count_24h", nullable = false)
    @Comment("최근 24시간 전략 패널 노출 누적 횟수")
    private Integer panelExposureCount24h = 0;

    @Column(name = "last_quote_received_at")
    @Comment("가장 최근 시세 수신 시각(quote)")
    private OffsetDateTime lastQuoteReceivedAt;

    @Column(name = "last_news_linked_at")
    @Comment("가장 최근 뉴스-자산 링크 시각")
    private OffsetDateTime lastNewsLinkedAt;

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
        if (themeCode != null && themeCode.isBlank()) {
            themeCode = null;
        }
        if (countryCode != null && countryCode.isBlank()) {
            countryCode = null;
        }
        if (isTradeEnabled == null) {
            isTradeEnabled = true;
        }
        if (universeLayer == null) {
            universeLayer = UniverseLayerType.CORE;
        }
        if (selectionScore == null) {
            selectionScore = BigDecimal.ZERO;
        }
        if (diversityScore == null) {
            diversityScore = BigDecimal.ZERO;
        }
        if (isCoreAsset == null) {
            isCoreAsset = false;
        }
        if (isWatchlistAsset == null) {
            isWatchlistAsset = false;
        }
        if (isUserWatch == null) {
            isUserWatch = Boolean.TRUE.equals(isWatchlistAsset);
        }
        if (displayWeight == null) {
            displayWeight = 0;
        }
        if (dupExposureCooldownMinutes == null) {
            dupExposureCooldownMinutes = 0;
        }
        if (panelExposureCount24h == null) {
            panelExposureCount24h = 0;
        }
        if (verificationStatus == null) {
            verificationStatus = AssetVerificationStatusType.UNVERIFIED;
        }
        if (countryCode == null || countryCode.isBlank()) {
            countryCode = country;
        }
        if (strategyScope != null && strategyScope.isBlank()) {
            strategyScope = null;
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (isUserWatch == null) {
            isUserWatch = Boolean.TRUE.equals(isWatchlistAsset);
        }
        if (countryCode == null || countryCode.isBlank()) {
            countryCode = country;
        }
        if (panelExposureCount24h == null) {
            panelExposureCount24h = 0;
        }
        updatedAt = OffsetDateTime.now();
    }
}

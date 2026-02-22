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
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 티커 별칭 검증 결과 로그 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "ticker_alias_audit",
        indexes = {
                @Index(name = "idx_ticker_alias_audit_time", columnList = "audit_time_utc"),
                @Index(name = "idx_ticker_alias_audit_asset", columnList = "asset_code,audit_time_utc"),
                @Index(name = "idx_ticker_alias_audit_valid", columnList = "valid,severity")
        })
@Comment("티커 별칭 검증 배치 결과 로그")
public class TickerAliasAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("티커 별칭 검증 로그 PK")
    private Long id;

    @Column(name = "audit_time_utc", nullable = false)
    @Comment("검증 수행 시각")
    private OffsetDateTime auditTimeUtc;

    @Column(name = "asset_code", length = 32)
    @Comment("검증 대상 자산 코드")
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "alias_value", length = 200)
    @Comment("검증 대상 별칭")
    private String aliasValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "alias_type", length = 30)
    @Comment("검증 대상 별칭 유형")
    private TickerAliasType aliasType;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 40)
    @Comment("검증 항목(FORMAT, EXCHANGE, NAME_MAPPING, DUPLICATE_ALIAS, LOCALE_CONFLICT)")
    private TickerAliasCheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    @Comment("심각도(INFO/WARN/ERROR)")
    private AuditSeverityType severity = AuditSeverityType.INFO;

    @Column(name = "valid", nullable = false)
    @Comment("검증 통과 여부")
    private Boolean valid = false;

    @Column(name = "detail_json", nullable = false, columnDefinition = "jsonb")
    @Comment("검증 상세/원인 JSON")
    private String detailJson = "{}";

    @Column(name = "trace_id", length = 64)
    @Comment("추적 ID")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (auditTimeUtc == null) {
            auditTimeUtc = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (detailJson == null || detailJson.isBlank()) {
            detailJson = "{}";
        }
        if (severity == null) {
            severity = AuditSeverityType.INFO;
        }
        if (valid == null) {
            valid = false;
        }
    }
}

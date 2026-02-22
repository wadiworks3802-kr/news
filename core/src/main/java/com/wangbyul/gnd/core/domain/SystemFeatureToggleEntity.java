package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 운영 킬스위치/전략 비활성화 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "system_feature_toggle",
        indexes = {
                @Index(name = "idx_feature_toggle_enabled", columnList = "enabled,feature_key,scope_type"),
                @Index(name = "idx_feature_toggle_trace_id", columnList = "trace_id,updated_at")
        })
@Comment("운영 킬스위치/전략 비활성화 설정 테이블")
public class SystemFeatureToggleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("토글 PK")
    private Long id;

    @Column(name = "feature_key", nullable = false, length = 80)
    @Comment("기능 키(SIGNAL_GENERATION, PAPER_TRADING, SCALP_ENGINE 등)")
    private String featureKey;

    @Column(name = "enabled", nullable = false)
    @Comment("기능 활성 여부")
    private Boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    @Comment("적용 범위(GLOBAL, COUNTRY, THEME, ASSET)")
    private FeatureScopeType scopeType = FeatureScopeType.GLOBAL;

    @Column(name = "scope_value", length = 120)
    @Comment("범위 값(국가코드/테마코드/자산코드)")
    private String scopeValue;

    @Column(name = "reason", columnDefinition = "text")
    @Comment("변경 사유")
    private String reason;

    @Column(name = "updated_by", length = 80)
    @Comment("변경자")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Comment("변경 시각")
    private OffsetDateTime updatedAt;

    @Column(name = "trace_id", length = 64)
    @Comment("요청 추적 ID")
    private String traceId;

    @PrePersist
    public void prePersist() {
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
        normalizeScopeValue();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
        normalizeScopeValue();
    }

    private void normalizeScopeValue() {
        if (scopeType == FeatureScopeType.GLOBAL) {
            scopeValue = null;
            return;
        }
        if (scopeValue != null) {
            scopeValue = scopeValue.trim();
            if (scopeValue.isBlank()) {
                scopeValue = null;
            }
        }
    }
}

package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
/**
 * SourceEntity 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Setter
@Entity
@Table(name = "source")
public class SourceEntity {

    @Id
    @Column(name = "sid", nullable = false, length = 64)
    private String sid;

    @Column(nullable = false, length = 5)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_grade", nullable = false, length = 2)
    private SourceGrade sourceGrade = SourceGrade.P1;

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(name = "allow_fetch", nullable = false)
    private Boolean allowFetch = true;

    @Column(name = "allow_store_raw", nullable = false)
    private Boolean allowStoreRaw = false;

    @Column(name = "allow_store_derived", nullable = false)
    private Boolean allowStoreDerived = true;

    @Column(name = "cache_ttl_seconds", nullable = false)
    private Integer cacheTtlSeconds = 900;

    @Column(name = "license_policy", nullable = false, length = 120)
    private String licensePolicy;

    @Column(name = "robots_policy", nullable = false, length = 120)
    private String robotsPolicy;

    @Column(name = "endpoint_url")
    private String endpointUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (sourceGrade != SourceGrade.P0 && allowStoreRaw == null) {
            allowStoreRaw = false;
        }
    }
}

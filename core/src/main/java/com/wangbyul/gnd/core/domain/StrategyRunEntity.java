package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 전략 실행 이력 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "strategy_run")
public class StrategyRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 24)
    private StrategyRunType runType;

    @Column(name = "scope_country", length = 5)
    private String scopeCountry;

    @Column(name = "scope_theme", length = 64)
    private String scopeTheme;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private JobStatus status = JobStatus.QUEUED;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "processed_count", nullable = false)
    private Integer processedCount = 0;

    @Column(name = "created_signal_count", nullable = false)
    private Integer createdSignalCount = 0;

    @Column(name = "report_json", columnDefinition = "jsonb")
    private String reportJson = "{}";

    @Column(name = "result_summary_json", columnDefinition = "jsonb")
    private String resultSummaryJson = "{}";

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (reportJson == null || reportJson.isBlank()) {
            reportJson = "{}";
        }
        if (resultSummaryJson == null || resultSummaryJson.isBlank()) {
            resultSummaryJson = "{}";
        }
    }
}

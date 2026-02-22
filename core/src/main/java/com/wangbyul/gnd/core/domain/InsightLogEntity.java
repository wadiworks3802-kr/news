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
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
/**
 * InsightLogEntity 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Setter
@Entity
@Table(name = "insight_log")
public class InsightLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "news_id", nullable = false)
    private NewsEntity news;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "risk_flag", nullable = false)
    private Boolean riskFlag = false;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @PrePersist
    public void prePersist() {
        if (generatedAt == null) {
            generatedAt = OffsetDateTime.now();
        }
    }
}

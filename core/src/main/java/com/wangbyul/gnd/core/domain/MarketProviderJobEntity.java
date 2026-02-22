package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 시장 데이터 제공자 배치 실행 이력 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(name = "market_provider_job")
@Comment("시장 데이터 Provider 수집/헬스체크 실행 감사 이력")
public class MarketProviderJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("시장 Provider 잡 이력 PK")
    private Long id;

    @Column(name = "provider_name", nullable = false, length = 64)
    @Comment("실행 대상 Provider 코드(mock/toss/kiwoom 등)")
    private String providerName;

    @Column(name = "job_name", length = 80)
    @Comment("실행 잡 이름(예: market-quote-collection)")
    private String jobName;

    @Column(name = "asset_code", length = 32)
    @Comment("단일 자산 대상 실행 시 자산 코드(배치 전체 실행이면 null)")
    private String assetCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 32)
    @Comment("잡 유형(QUOTE/BAR/HEALTH_CHECK 등)")
    private MarketProviderJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Comment("잡 실행 상태(QUEUED/RUNNING/SUCCESS/FAILED)")
    private JobStatus status = JobStatus.QUEUED;

    @Column(name = "attempt", nullable = false)
    @Comment("재시도 포함 시도 횟수")
    private Integer attempt = 0;

    @Column(name = "triggered_by", length = 64)
    @Comment("실행 주체(batch-quartz/admin-api/manual 등)")
    private String triggeredBy;

    @Column(name = "requested_count")
    @Comment("Provider 요청 대상 개수(자산 수 등)")
    private Integer requestedCount = 0;

    @Column(name = "processed_count")
    @Comment("Provider 응답 처리 대상 개수")
    private Integer processedCount = 0;

    @Column(name = "success_count")
    @Comment("저장 성공 건수")
    private Integer successCount = 0;

    @Column(name = "failed_count")
    @Comment("저장 실패/유효성 실패 건수")
    private Integer failedCount = 0;

    @Column(name = "empty_response")
    @Comment("Provider 응답이 비어 있었는지 여부")
    private Boolean emptyResponse = false;

    @Column(name = "latency_ms")
    @Comment("Provider 호출 왕복 지연 시간(ms)")
    private Long latencyMs;

    @Column(name = "last_error", columnDefinition = "text")
    @Comment("마지막 실패 사유(민감정보 마스킹 후 저장)")
    private String lastError;

    @Column(name = "detail_json", columnDefinition = "text")
    @Comment("실행 세부 메타데이터(JSON 문자열)")
    private String detailJson;

    @Column(name = "trace_id", length = 64)
    @Comment("요청/배치 추적 ID")
    private String traceId;

    @Column(name = "scheduled_at", nullable = false)
    @Comment("잡 예약 시각")
    private OffsetDateTime scheduledAt;

    @Column(name = "started_at")
    @Comment("실제 실행 시작 시각")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    @Comment("실제 실행 종료 시각")
    private OffsetDateTime finishedAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("레코드 최종 갱신 시각")
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (scheduledAt == null) {
            scheduledAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (jobName == null || jobName.isBlank()) {
            jobName = jobType == null ? "market-provider-job" : "market-" + jobType.name().toLowerCase();
        }
        if (requestedCount == null) {
            requestedCount = 0;
        }
        if (processedCount == null) {
            processedCount = 0;
        }
        if (successCount == null) {
            successCount = 0;
        }
        if (failedCount == null) {
            failedCount = 0;
        }
        if (emptyResponse == null) {
            emptyResponse = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}

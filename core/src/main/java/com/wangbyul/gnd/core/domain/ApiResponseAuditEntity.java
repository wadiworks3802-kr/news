package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 외부 API 응답 감사 샘플 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "api_response_audit",
        indexes = {
                @Index(name = "idx_api_audit_provider_time_desc", columnList = "provider,request_time_utc"),
                @Index(name = "idx_api_audit_api_name", columnList = "api_name"),
                @Index(name = "idx_api_audit_success", columnList = "success")
        })
@Comment("외부 Provider API 응답 감사 샘플 로그")
public class ApiResponseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("API 응답 감사 PK")
    private Long id;

    @Column(name = "provider", nullable = false, length = 40)
    @Comment("외부 데이터 공급자")
    private String provider;

    @Column(name = "api_name", nullable = false, length = 80)
    @Comment("호출 API 이름")
    private String apiName;

    @Column(name = "request_time_utc", nullable = false)
    @Comment("요청 시작 시각")
    private OffsetDateTime requestTimeUtc;

    @Column(name = "response_time_utc")
    @Comment("응답 수신 시각")
    private OffsetDateTime responseTimeUtc;

    @Column(name = "latency_ms")
    @Comment("왕복 지연(ms)")
    private Long latencyMs;

    @Column(name = "http_status")
    @Comment("HTTP 상태코드")
    private Integer httpStatus;

    @Column(name = "request_hash", length = 128)
    @Comment("요청 파라미터 해시")
    private String requestHash;

    @Column(name = "response_hash", length = 128)
    @Comment("응답 본문 해시")
    private String responseHash;

    @Column(name = "record_count")
    @Comment("응답 레코드 수")
    private Integer recordCount;

    @Column(name = "sample_payload_json", columnDefinition = "jsonb")
    @Comment("민감정보 제거 후 샘플 응답 JSON")
    private String samplePayloadJson;

    @Column(name = "success", nullable = false)
    @Comment("호출 성공 여부")
    private Boolean success = false;

    @Column(name = "error_code", length = 80)
    @Comment("실패 코드")
    private String errorCode;

    @Column(name = "trace_id", length = 64)
    @Comment("요청/배치 추적 ID")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (requestTimeUtc == null) {
            requestTimeUtc = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}

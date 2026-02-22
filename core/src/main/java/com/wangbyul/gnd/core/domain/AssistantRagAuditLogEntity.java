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
 * 경량 RAG/LLM 보조 계층 감사 로그 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "assistant_rag_audit_log",
        indexes = {
                @Index(name = "idx_assistant_rag_audit_scope_time", columnList = "request_scope,created_at"),
                @Index(name = "idx_assistant_rag_audit_trace", columnList = "trace_id,created_at"),
                @Index(name = "idx_assistant_rag_audit_signal", columnList = "signal_id,created_at"),
                @Index(name = "idx_assistant_rag_audit_asset", columnList = "asset_code,created_at")
        })
@Comment("경량 RAG/LLM 보조 분석 호출 감사 로그")
public class AssistantRagAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("RAG 감사 로그 PK")
    private Long id;

    @Column(name = "request_scope", nullable = false, length = 32)
    @Comment("요청 범위(SIGNAL_DETAIL, TRACE_DETAIL 등)")
    private String requestScope;

    @Column(name = "request_key", length = 120)
    @Comment("요청 식별키(signal_id 또는 trace_id 등)")
    private String requestKey;

    @Column(name = "signal_id", length = 64)
    @Comment("연결된 시그널 ID(선택)")
    private String signalId;

    @Column(name = "asset_code", length = 32)
    @Comment("연결된 자산 코드(선택)")
    private String assetCode;

    @Column(name = "trace_id", length = 64)
    @Comment("요청/배치 trace_id")
    private String traceId;

    @Column(name = "model_version", length = 64)
    @Comment("경량 모델/템플릿 버전")
    private String modelVersion;

    @Column(name = "prompt_version", length = 64)
    @Comment("프롬프트/출력 스키마 버전")
    private String promptVersion;

    @Column(name = "rag_context_refs_json", nullable = false, columnDefinition = "jsonb")
    @Comment("RAG 컨텍스트 참조 목록(JSON 배열)")
    private String ragContextRefsJson = "[]";

    @Column(name = "latency_ms_total")
    @Comment("전체 보조 분석 처리 지연(ms)")
    private Long latencyMsTotal;

    @Column(name = "fallback_applied", nullable = false)
    @Comment("모델 실패/지연/비활성화로 fallback 적용 여부")
    private Boolean fallbackApplied = false;

    @Column(name = "success", nullable = false)
    @Comment("보조 분석 처리 성공 여부")
    private Boolean success = true;

    @Column(name = "error_code", length = 80)
    @Comment("실패/폴백 원인 코드")
    private String errorCode;

    @Column(name = "output_json", nullable = false, columnDefinition = "jsonb")
    @Comment("구조화 보조 분석 결과 JSON")
    private String outputJson = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (requestScope == null || requestScope.isBlank()) {
            requestScope = "UNKNOWN";
        }
        if (ragContextRefsJson == null || ragContextRefsJson.isBlank()) {
            ragContextRefsJson = "[]";
        }
        if (outputJson == null || outputJson.isBlank()) {
            outputJson = "{}";
        }
        if (fallbackApplied == null) {
            fallbackApplied = false;
        }
        if (success == null) {
            success = true;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

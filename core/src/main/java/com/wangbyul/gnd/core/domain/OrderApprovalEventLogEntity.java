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
 * 주문 승인 파이프라인 이벤트 감사 로그 엔티티.
 *
 * 단계 전이/요청/결과를 재현 가능한 JSON 스냅샷과 함께 저장한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "order_approval_event_log",
        indexes = {
                @Index(name = "idx_order_approval_event_workflow", columnList = "workflow_id,created_at"),
                @Index(name = "idx_order_approval_event_trace", columnList = "trace_id,created_at"),
                @Index(name = "idx_order_approval_event_type", columnList = "event_type,created_at")
        })
@Comment("주문 승인 파이프라인 단계 전이/요청/결과 감사 로그")
public class OrderApprovalEventLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("주문 승인 이벤트 로그 PK")
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    @Comment("대상 워크플로 ID")
    private Long workflowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", referencedColumnName = "id", insertable = false, updatable = false)
    private OrderApprovalWorkflowEntity workflow;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    @Comment("이벤트 유형")
    private OrderApprovalEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", length = 32)
    @Comment("전이 이전 단계")
    private OrderApprovalWorkflowStageType fromStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", length = 32)
    @Comment("전이 이후 단계")
    private OrderApprovalWorkflowStageType toStage;

    @Column(name = "actor", length = 80)
    @Comment("수행 주체(관리자/시스템)")
    private String actor;

    @Column(name = "actor_role", length = 40)
    @Comment("수행 주체 역할(ADMIN/SYSTEM)")
    private String actorRole;

    @Column(name = "reason", columnDefinition = "text")
    @Comment("수행 사유/메모")
    private String reason;

    @Column(name = "success", nullable = false)
    @Comment("이벤트 처리 성공 여부")
    private Boolean success = true;

    @Column(name = "error_code", length = 80)
    @Comment("실패 코드")
    private String errorCode;

    @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    @Comment("입력 요청 스냅샷 JSON")
    private String requestJson = "{}";

    @Column(name = "response_json", nullable = false, columnDefinition = "jsonb")
    @Comment("처리 결과 스냅샷 JSON")
    private String responseJson = "{}";

    @Column(name = "trace_id", length = 64)
    @Comment("추적 ID")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("이벤트 생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (eventType == null) {
            eventType = OrderApprovalEventType.ANALYZE_SNAPSHOT;
        }
        if (success == null) {
            success = true;
        }
        if (requestJson == null || requestJson.isBlank()) {
            requestJson = "{}";
        }
        if (responseJson == null || responseJson.isBlank()) {
            responseJson = "{}";
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

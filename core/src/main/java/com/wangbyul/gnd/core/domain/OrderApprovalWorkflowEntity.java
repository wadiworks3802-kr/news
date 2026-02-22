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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * AI 추천 기반 주문 승인 파이프라인 워크플로 엔티티.
 *
 * 추천/승인/주문요청/주문결과를 trace_id와 스냅샷 참조로 재현 가능하게 저장한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@Entity
@Table(
        name = "order_approval_workflow",
        indexes = {
                @Index(name = "idx_order_approval_workflow_stage_updated", columnList = "current_stage,updated_at"),
                @Index(name = "idx_order_approval_workflow_country_stage", columnList = "country,current_stage,updated_at"),
                @Index(name = "idx_order_approval_workflow_signal", columnList = "signal_id,created_at"),
                @Index(name = "idx_order_approval_workflow_trace", columnList = "trace_id,created_at"),
                @Index(name = "idx_order_approval_workflow_paper_order", columnList = "paper_order_id")
        })
@Comment("AI 추천 기반 주문 승인 파이프라인 워크플로(준비 단계)")
public class OrderApprovalWorkflowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("주문 승인 파이프라인 워크플로 PK")
    private Long id;

    @Column(name = "signal_id", nullable = false, length = 64)
    @Comment("원본 추천 시그널 ID")
    private String signalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", referencedColumnName = "id", insertable = false, updatable = false)
    private TradingSignalEntity signal;

    @Column(name = "asset_code", nullable = false, length = 32)
    @Comment("대상 자산 코드")
    private String assetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_code", referencedColumnName = "asset_code", insertable = false, updatable = false)
    private AssetUniverseEntity asset;

    @Column(name = "country", length = 5)
    @Comment("자산 국가 스코프")
    private String country;

    @Column(name = "theme", length = 64)
    @Comment("자산/추천 테마")
    private String theme;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_side", nullable = false, length = 8)
    @Comment("추천 주문 방향(BUY/SELL)")
    private OrderSideType orderSide;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_action", length = 24)
    @Comment("원본 시그널 액션(BUY_CANDIDATE 등)")
    private SignalActionType recommendedAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 32)
    @Comment("현재 파이프라인 단계")
    private OrderApprovalWorkflowStageType currentStage = OrderApprovalWorkflowStageType.RECOMMEND;

    @Column(name = "recommendation_confidence", precision = 8, scale = 4)
    @Comment("추천 시점 결합 신뢰도 스냅샷")
    private BigDecimal recommendationConfidence;

    @Column(name = "recommendation_reason", columnDefinition = "text")
    @Comment("추천 요약 사유(관리자 목록용)")
    private String recommendationReason;

    @Column(name = "analyze_at")
    @Comment("입력 스냅샷 수집/분석 시각")
    private OffsetDateTime analyzeAt;

    @Column(name = "recommended_at")
    @Comment("추천 생성 시각")
    private OffsetDateTime recommendedAt;

    @Column(name = "approved_by", length = 80)
    @Comment("승인자 식별자")
    private String approvedBy;

    @Column(name = "approved_at")
    @Comment("승인 시각")
    private OffsetDateTime approvedAt;

    @Column(name = "approval_reason", columnDefinition = "text")
    @Comment("승인 사유")
    private String approvalReason;

    @Column(name = "rejected_by", length = 80)
    @Comment("반려자 식별자")
    private String rejectedBy;

    @Column(name = "rejected_at")
    @Comment("반려 시각")
    private OffsetDateTime rejectedAt;

    @Column(name = "reject_reason", columnDefinition = "text")
    @Comment("반려 사유")
    private String rejectReason;

    @Column(name = "order_requested_by", length = 80)
    @Comment("주문 요청자 식별자")
    private String orderRequestedBy;

    @Column(name = "order_requested_at")
    @Comment("주문 요청 시각")
    private OffsetDateTime orderRequestedAt;

    @Column(name = "order_request_reason", columnDefinition = "text")
    @Comment("주문 요청 사유")
    private String orderRequestReason;

    @Column(name = "order_executed_at")
    @Comment("주문 결과 반영 시각")
    private OffsetDateTime orderExecutedAt;

    @Column(name = "order_execution_mode", nullable = false, length = 32)
    @Comment("주문 실행 모드(PAPER_ONLY/LIVE_DISABLED_PAPER 등)")
    private String orderExecutionMode = "PAPER_ONLY";

    @Column(name = "paper_order_id")
    @Comment("연결된 모의주문 ID")
    private Long paperOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_order_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PaperTradeOrderEntity paperOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "paper_order_status", length = 24)
    @Comment("모의주문 최종 상태(FILLED/BLOCKED 등)")
    private OrderStatusType paperOrderStatus;

    @Column(name = "paper_order_result_json", nullable = false, columnDefinition = "jsonb")
    @Comment("모의주문 실행 결과 스냅샷 JSON")
    private String paperOrderResultJson = "{}";

    @Column(name = "order_error_code", length = 80)
    @Comment("주문 요청/실행 실패 코드")
    private String orderErrorCode;

    @Column(name = "order_error_message", columnDefinition = "text")
    @Comment("주문 요청/실행 실패 메시지")
    private String orderErrorMessage;

    @Column(name = "live_trade_requested", nullable = false)
    @Comment("실주문 경로 요청 여부")
    private Boolean liveTradeRequested = false;

    @Column(name = "live_trade_blocked_reason", length = 120)
    @Comment("실주문 경로 차단 사유")
    private String liveTradeBlockedReason;

    @Column(name = "signal_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @Comment("추천 입력 시그널 요약 스냅샷 JSON")
    private String signalSnapshotJson = "{}";

    @Column(name = "signal_detail_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @Comment("추천 입력 시그널 상세 스냅샷 JSON")
    private String signalDetailSnapshotJson = "{}";

    @Column(name = "quote_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @Comment("추천 시점 시세 스냅샷 JSON")
    private String quoteSnapshotJson = "{}";

    @Column(name = "risk_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @Comment("추천 시점 리스크/자금관리 스냅샷 JSON")
    private String riskSnapshotJson = "{}";

    @Column(name = "news_context_refs_json", nullable = false, columnDefinition = "jsonb")
    @Comment("추천 시점 뉴스/RAG 컨텍스트 참조 목록 JSON")
    private String newsContextRefsJson = "[]";

    @Column(name = "assistant_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @Comment("추천 시점 RAG 보조 요약 스냅샷 JSON")
    private String assistantSnapshotJson = "{}";

    @Column(name = "trace_id", length = 64)
    @Comment("추천/승인/주문 흐름 추적 ID")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("워크플로 생성 시각")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("워크플로 최종 갱신 시각")
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
        if (currentStage == null) {
            currentStage = OrderApprovalWorkflowStageType.RECOMMEND;
        }
        if (orderExecutionMode == null || orderExecutionMode.isBlank()) {
            orderExecutionMode = "PAPER_ONLY";
        }
        if (paperOrderResultJson == null || paperOrderResultJson.isBlank()) {
            paperOrderResultJson = "{}";
        }
        if (signalSnapshotJson == null || signalSnapshotJson.isBlank()) {
            signalSnapshotJson = "{}";
        }
        if (signalDetailSnapshotJson == null || signalDetailSnapshotJson.isBlank()) {
            signalDetailSnapshotJson = "{}";
        }
        if (quoteSnapshotJson == null || quoteSnapshotJson.isBlank()) {
            quoteSnapshotJson = "{}";
        }
        if (riskSnapshotJson == null || riskSnapshotJson.isBlank()) {
            riskSnapshotJson = "{}";
        }
        if (newsContextRefsJson == null || newsContextRefsJson.isBlank()) {
            newsContextRefsJson = "[]";
        }
        if (assistantSnapshotJson == null || assistantSnapshotJson.isBlank()) {
            assistantSnapshotJson = "{}";
        }
        if (liveTradeRequested == null) {
            liveTradeRequested = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}

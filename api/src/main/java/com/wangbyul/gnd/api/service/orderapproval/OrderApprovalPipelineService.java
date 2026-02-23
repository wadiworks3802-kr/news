package com.wangbyul.gnd.api.service.orderapproval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.OrderApprovalApproveRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalCreateRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalOrderRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalRejectRequest;
import com.wangbyul.gnd.api.dto.PaperTradeOrderRequestDto;
import com.wangbyul.gnd.api.dto.PaperTradeOrderResultDto;
import com.wangbyul.gnd.api.dto.SignalDetailDto;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.api.service.signal.PaperTradeSimulationService;
import com.wangbyul.gnd.api.service.signal.RiskPolicyService;
import com.wangbyul.gnd.api.service.signal.TradingSignalEngineService;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.domain.OrderApprovalEventLogEntity;
import com.wangbyul.gnd.core.domain.OrderApprovalEventType;
import com.wangbyul.gnd.core.domain.OrderApprovalWorkflowEntity;
import com.wangbyul.gnd.core.domain.OrderApprovalWorkflowStageType;
import com.wangbyul.gnd.core.domain.OrderSideType;
import com.wangbyul.gnd.core.domain.OrderStatusType;
import com.wangbyul.gnd.core.domain.PaperTradeOrderEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.OrderApprovalEventLogRepository;
import com.wangbyul.gnd.core.repository.OrderApprovalWorkflowRepository;
import com.wangbyul.gnd.core.repository.PaperTradeOrderRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 승인 기반 주문 승인 파이프라인 서비스(준비 단계).
 *
 * 실주문 경로는 기본 OFF를 유지하고, 승인 이후에는 paper trade로만 실행/기록한다.
 * 모든 단계 전이는 이벤트 감사 로그와 스냅샷 참조를 함께 저장해 trace 재현성을 확보한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
@RequiredArgsConstructor
public class OrderApprovalPipelineService {

    private final OrderApprovalWorkflowRepository workflowRepository;
    private final OrderApprovalEventLogRepository eventLogRepository;
    private final TradingSignalRepository tradingSignalRepository;
    private final TradingSignalEngineService tradingSignalEngineService;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final PaperTradeOrderRepository paperTradeOrderRepository;
    private final PaperTradeSimulationService paperTradeSimulationService;
    private final RiskPolicyService riskPolicyService;
    private final SystemFeatureToggleService systemFeatureToggleService;
    private final ObjectMapper objectMapper;

    @Value("${app.trade.live-enabled:false}")
    private boolean liveTradeEnabledProperty;

    @Transactional
    public Map<String, Object> createRecommendation(OrderApprovalCreateRequest request) {
        String signalId = safe(request.getSignalId());
        TradingSignalEntity signal = tradingSignalRepository.findById(signalId)
                .orElseThrow(() -> new IllegalArgumentException("signal not found: " + signalId));

        if (!Boolean.TRUE.equals(request.getAllowDuplicate())) {
            workflowRepository.findTop1BySignalIdOrderByCreatedAtDesc(signalId).ifPresent(existing -> {
                if (existing.getCurrentStage() != OrderApprovalWorkflowStageType.REJECTED
                        && existing.getCurrentStage() != OrderApprovalWorkflowStageType.ORDER_EXECUTED
                        && existing.getCurrentStage() != OrderApprovalWorkflowStageType.ORDER_FAILED) {
                    throw new IllegalArgumentException("active recommendation already exists for signal_id: " + signalId);
                }
            });
        }

        SignalDetailDto signalDetail = tradingSignalEngineService.getSignalDetail(signalId, true);
        OrderSideType orderSide = resolveOrderSide(signal, request.getOrderSide());
        OffsetDateTime now = OffsetDateTime.now();

        MarketQuoteSnapshotEntity quote = marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(signal.getAssetCode())
                .orElse(null);

        OrderApprovalWorkflowEntity workflow = new OrderApprovalWorkflowEntity();
        workflow.setSignalId(signal.getId());
        workflow.setAssetCode(signal.getAssetCode());
        workflow.setCountry(signal.getCountry());
        workflow.setTheme(signal.getTheme());
        workflow.setOrderSide(orderSide);
        workflow.setRecommendedAction(signal.getAction());
        workflow.setCurrentStage(OrderApprovalWorkflowStageType.RECOMMEND);
        workflow.setRecommendationConfidence(scale(signal.getCombinedConfidence(), 4));
        workflow.setRecommendationReason(buildRecommendationReason(signalDetail, request.getRequestReason()));
        workflow.setAnalyzeAt(now);
        workflow.setRecommendedAt(now);
        workflow.setOrderExecutionMode("PAPER_ONLY");
        workflow.setLiveTradeRequested(false);
        workflow.setLiveTradeBlockedReason("LIVE_TRADE_DEFAULT_OFF");
        workflow.setSignalSnapshotJson(toJson(signalSummarySnapshot(signal)));
        workflow.setSignalDetailSnapshotJson(toJson(signalDetail));
        workflow.setQuoteSnapshotJson(toJson(quoteSnapshot(quote)));
        workflow.setRiskSnapshotJson(toJson(riskPolicyService.portfolioRisk(null)));
        workflow.setNewsContextRefsJson(safeJsonArray(signalDetail == null ? null : signalDetail.getRagContextRefsJson()));
        workflow.setAssistantSnapshotJson(toJson(signalDetail == null ? Map.of() : signalDetail.getAssistantRag()));
        workflow.setTraceId(traceId());
        workflow = workflowRepository.save(workflow);

        logEvent(workflow, OrderApprovalEventType.ANALYZE_SNAPSHOT, null, OrderApprovalWorkflowStageType.ANALYZE,
                actorOrDefault(request.getRequestedBy()), "ADMIN", safe(request.getRequestReason()),
                true, null,
                Map.of("signal_id", signalId),
                Map.of("snapshot_saved", true));
        logEvent(workflow, OrderApprovalEventType.RECOMMEND_CREATED, OrderApprovalWorkflowStageType.ANALYZE,
                OrderApprovalWorkflowStageType.RECOMMEND,
                actorOrDefault(request.getRequestedBy()), "ADMIN", safe(request.getRequestReason()),
                true, null,
                Map.of("signal_id", signalId, "order_side", orderSide.name()),
                Map.of("workflow_id", workflow.getId(), "current_stage", workflow.getCurrentStage().name()));

        return workflowDetailMap(workflow, true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listRecommendations(String country, String stage, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String scopeCountry = safe(country).toUpperCase(Locale.ROOT);
        OrderApprovalWorkflowStageType stageFilter = parseStage(stage);

        List<OrderApprovalWorkflowEntity> rows;
        if (!scopeCountry.isBlank() && stageFilter != null) {
            rows = workflowRepository.findByCountryAndCurrentStageOrderByUpdatedAtDesc(scopeCountry, stageFilter, PageRequest.of(0, safeLimit));
        } else if (!scopeCountry.isBlank()) {
            rows = workflowRepository.findByCountryOrderByUpdatedAtDesc(scopeCountry, PageRequest.of(0, safeLimit));
        } else if (stageFilter != null) {
            rows = workflowRepository.findByCurrentStageOrderByUpdatedAtDesc(stageFilter, PageRequest.of(0, safeLimit));
        } else {
            rows = workflowRepository.findByOrderByUpdatedAtDesc(PageRequest.of(0, safeLimit));
        }

        Map<String, Long> stageCounts = new LinkedHashMap<>();
        for (OrderApprovalWorkflowStageType type : OrderApprovalWorkflowStageType.values()) {
            stageCounts.put(type.name(), 0L);
        }
        for (OrderApprovalWorkflowEntity row : rows) {
            String key = row.getCurrentStage() == null ? "UNKNOWN" : row.getCurrentStage().name();
            stageCounts.merge(key, 1L, Long::sum);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", scopeCountry.isBlank() ? "ALL" : scopeCountry);
        data.put("scope_stage", stageFilter == null ? "ALL" : stageFilter.name());
        data.put("count", rows.size());
        data.put("stage_counts", stageCounts);
        data.put("items", rows.stream().map(this::workflowListItem).toList());
        data.put("pipeline_state_machine", List.of("ANALYZE", "RECOMMEND", "APPROVE", "ORDER_REQUESTED", "ORDER_EXECUTED"));
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRecommendationDetail(Long workflowId) {
        OrderApprovalWorkflowEntity workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        return workflowDetailMap(workflow, true);
    }

    @Transactional
    public Map<String, Object> approveRecommendation(Long workflowId, OrderApprovalApproveRequest request) {
        OrderApprovalWorkflowEntity workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        ensureApprovable(workflow);
        if (safe(request.getApprovalReason()).isBlank()) {
            throw new IllegalArgumentException("approval_reason is required");
        }

        OrderApprovalWorkflowStageType fromStage = workflow.getCurrentStage();
        workflow.setApprovedBy(actorOrDefault(request.getApprovedBy()));
        workflow.setApprovedAt(OffsetDateTime.now());
        workflow.setApprovalReason(safe(request.getApprovalReason()));
        workflow.setCurrentStage(OrderApprovalWorkflowStageType.APPROVE);
        workflow.setTraceId(pipelineTraceId(workflow));
        workflow = workflowRepository.save(workflow);

        logEvent(workflow, OrderApprovalEventType.APPROVED, fromStage, OrderApprovalWorkflowStageType.APPROVE,
                actorOrDefault(request.getApprovedBy()), "ADMIN", safe(request.getApprovalReason()),
                true, null, Map.of("workflow_id", workflowId), Map.of("approved", true));

        boolean autoApprovalEnabled = systemFeatureToggleService.isFeatureEnabled(
                "AUTO_ORDER_WITH_ADMIN_APPROVAL", workflow.getCountry(), workflow.getTheme(), workflow.getAssetCode());
        boolean autoRequested = false;
        if (Boolean.TRUE.equals(request.getAutoRequestPaperOrder()) && autoApprovalEnabled) {
            requestPaperOrderInternal(workflow, actorOrDefault(request.getApprovedBy()),
                    "AUTO_AFTER_APPROVAL:" + safe(request.getApprovalReason()),
                    false, true);
            autoRequested = true;
        }

        Map<String, Object> data = workflowDetailMap(workflowRepository.findById(workflowId).orElse(workflow), true);
        data.put("auto_order_with_admin_approval_enabled", autoApprovalEnabled);
        data.put("auto_paper_order_requested", autoRequested);
        if (Boolean.TRUE.equals(request.getAutoRequestPaperOrder()) && !autoApprovalEnabled) {
            data.put("auto_request_skipped_reason", "AUTO_ORDER_WITH_ADMIN_APPROVAL toggle is OFF");
        }
        return data;
    }

    @Transactional
    public Map<String, Object> rejectRecommendation(Long workflowId, OrderApprovalRejectRequest request) {
        OrderApprovalWorkflowEntity workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (safe(request.getRejectReason()).isBlank()) {
            throw new IllegalArgumentException("reject_reason is required");
        }
        if (workflow.getCurrentStage() == OrderApprovalWorkflowStageType.ORDER_EXECUTED) {
            throw new IllegalArgumentException("executed workflow cannot be rejected");
        }
        if (workflow.getCurrentStage() == OrderApprovalWorkflowStageType.REJECTED) {
            return workflowDetailMap(workflow, true);
        }

        OrderApprovalWorkflowStageType fromStage = workflow.getCurrentStage();
        workflow.setRejectedBy(actorOrDefault(request.getRejectedBy()));
        workflow.setRejectedAt(OffsetDateTime.now());
        workflow.setRejectReason(safe(request.getRejectReason()));
        workflow.setCurrentStage(OrderApprovalWorkflowStageType.REJECTED);
        workflow.setTraceId(pipelineTraceId(workflow));
        workflow = workflowRepository.save(workflow);

        logEvent(workflow, OrderApprovalEventType.REJECTED, fromStage, OrderApprovalWorkflowStageType.REJECTED,
                actorOrDefault(request.getRejectedBy()), "ADMIN", safe(request.getRejectReason()),
                true, null, Map.of("workflow_id", workflowId), Map.of("rejected", true));

        return workflowDetailMap(workflow, true);
    }

    @Transactional
    public Map<String, Object> requestPaperOrder(Long workflowId, OrderApprovalOrderRequest request) {
        OrderApprovalWorkflowEntity workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        requestPaperOrderInternal(
                workflow,
                actorOrDefault(request.getRequestedBy()),
                safe(request.getRequestReason()),
                true,
                Boolean.TRUE.equals(request.getForcePaper()));
        return workflowDetailMap(workflowRepository.findById(workflowId).orElse(workflow), true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> traceSummary(String traceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<OrderApprovalWorkflowEntity> workflows = workflowRepository.findTop100ByTraceIdOrderByUpdatedAtDesc(traceId).stream()
                .limit(safeLimit)
                .toList();
        List<OrderApprovalEventLogEntity> events = eventLogRepository.findTop300ByTraceIdOrderByCreatedAtDesc(traceId).stream()
                .limit(safeLimit)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trace_id", traceId);
        data.put("workflow_count", workflows.size());
        data.put("event_count", events.size());
        data.put("workflows", workflows.stream().map(this::workflowListItem).toList());
        data.put("events", events.stream().map(this::eventItem).toList());
        return data;
    }

    private void requestPaperOrderInternal(
            OrderApprovalWorkflowEntity workflow,
            String actor,
            String reason,
            boolean manualRequest,
            boolean forcePaper) {
        if (workflow.getCurrentStage() != OrderApprovalWorkflowStageType.APPROVE
                && workflow.getCurrentStage() != OrderApprovalWorkflowStageType.ORDER_FAILED) {
            throw new IllegalArgumentException("workflow is not in approvable order-request stage: " + workflow.getCurrentStage());
        }

        OrderApprovalWorkflowStageType fromStage = workflow.getCurrentStage();
        workflow.setOrderRequestedBy(actor);
        workflow.setOrderRequestedAt(OffsetDateTime.now());
        workflow.setOrderRequestReason(reason);
        workflow.setCurrentStage(OrderApprovalWorkflowStageType.ORDER_REQUESTED);
        workflow.setTraceId(pipelineTraceId(workflow));

        boolean liveTradeToggle = systemFeatureToggleService.isFeatureEnabled(
                "LIVE_TRADE", workflow.getCountry(), workflow.getTheme(), workflow.getAssetCode());
        boolean liveTradeConfigured = liveTradeEnabledProperty && liveTradeToggle;
        boolean liveTradeRequested = !forcePaper && liveTradeConfigured;
        workflow.setLiveTradeRequested(false);
        workflow.setOrderExecutionMode("PAPER_ONLY");
        workflow.setLiveTradeBlockedReason(liveTradeRequested
                ? "LIVE_TRADE_FORCED_OFF_PREPARATION_STAGE"
                : "LIVE_TRADE_DISABLED_DEFAULT_OFF");
        workflow = workflowRepository.save(workflow);

        logEvent(workflow, OrderApprovalEventType.ORDER_REQUESTED, fromStage, OrderApprovalWorkflowStageType.ORDER_REQUESTED,
                actor, manualRequest ? "ADMIN" : "SYSTEM", reason,
                true, null,
                Map.of(
                        "force_paper", forcePaper,
                        "live_trade_enabled_property", liveTradeEnabledProperty,
                        "live_trade_toggle", liveTradeToggle,
                        "live_trade_configured", liveTradeConfigured,
                        "requested_live_trade", liveTradeRequested,
                        "api_request_trace_id", currentRequestTraceId()),
                Map.of(
                        "execution_mode", workflow.getOrderExecutionMode(),
                        "live_trade_blocked_reason", workflow.getLiveTradeBlockedReason()));

        try {
            PaperTradeOrderRequestDto orderRequest = new PaperTradeOrderRequestDto();
            orderRequest.setAssetCode(workflow.getAssetCode());
            orderRequest.setSignalId(workflow.getSignalId());
            orderRequest.setOrderSide(workflow.getOrderSide());
            PaperTradeOrderResultDto result = paperTradeSimulationService.execute(orderRequest);
            alignPaperOrderTraceId(result == null ? null : result.getOrderId(), workflow.getTraceId());
            workflow.setPaperOrderId(result.getOrderId());
            workflow.setPaperOrderStatus(result.getStatus());
            workflow.setPaperOrderResultJson(toJson(result));
            workflow.setOrderExecutedAt(OffsetDateTime.now());
            workflow.setCurrentStage(OrderApprovalWorkflowStageType.ORDER_EXECUTED);
            if (result.getStatus() == OrderStatusType.BLOCKED) {
                workflow.setOrderErrorCode("PAPER_ORDER_BLOCKED");
                workflow.setOrderErrorMessage(safe(result.getBlockedReason()));
            } else {
                workflow.setOrderErrorCode(null);
                workflow.setOrderErrorMessage(null);
            }
            workflowRepository.save(workflow);

            logEvent(workflow, OrderApprovalEventType.ORDER_EXECUTED,
                    OrderApprovalWorkflowStageType.ORDER_REQUESTED,
                    OrderApprovalWorkflowStageType.ORDER_EXECUTED,
                    actor, manualRequest ? "ADMIN" : "SYSTEM", reason,
                    true, null,
                    Map.of("workflow_id", workflow.getId()),
                    Map.of(
                            "paper_order_id", result.getOrderId(),
                            "paper_order_status", result.getStatus() == null ? "" : result.getStatus().name(),
                            "blocked_reason", safe(result.getBlockedReason())));
        } catch (Exception ex) {
            workflow.setCurrentStage(OrderApprovalWorkflowStageType.ORDER_FAILED);
            workflow.setOrderErrorCode("PAPER_ORDER_EXECUTION_ERROR");
            workflow.setOrderErrorMessage(trim(ex.getMessage(), 400));
            workflow.setOrderExecutedAt(OffsetDateTime.now());
            workflowRepository.save(workflow);

            logEvent(workflow, OrderApprovalEventType.ORDER_FAILED,
                    OrderApprovalWorkflowStageType.ORDER_REQUESTED,
                    OrderApprovalWorkflowStageType.ORDER_FAILED,
                    actor, manualRequest ? "ADMIN" : "SYSTEM", reason,
                    false, "PAPER_ORDER_EXECUTION_ERROR",
                    Map.of("workflow_id", workflow.getId()),
                    Map.of("error_message", trim(ex.getMessage(), 400)));
        }
    }

    private void ensureApprovable(OrderApprovalWorkflowEntity workflow) {
        if (workflow.getCurrentStage() == OrderApprovalWorkflowStageType.REJECTED) {
            throw new IllegalArgumentException("rejected workflow cannot be approved");
        }
        if (workflow.getCurrentStage() == OrderApprovalWorkflowStageType.ORDER_EXECUTED) {
            throw new IllegalArgumentException("executed workflow cannot be approved again");
        }
        if (workflow.getCurrentStage() == OrderApprovalWorkflowStageType.ORDER_REQUESTED) {
            throw new IllegalArgumentException("order already requested");
        }
        if (workflow.getCurrentStage() != OrderApprovalWorkflowStageType.RECOMMEND
                && workflow.getCurrentStage() != OrderApprovalWorkflowStageType.APPROVE) {
            throw new IllegalArgumentException("workflow stage not approvable: " + workflow.getCurrentStage());
        }
    }

    private OrderSideType resolveOrderSide(TradingSignalEntity signal, OrderSideType requested) {
        if (requested != null) {
            return requested;
        }
        if (signal.getAction() == SignalActionType.BUY_CANDIDATE || signal.getAction() == SignalActionType.BUY_LOCK) {
            return OrderSideType.BUY;
        }
        if (signal.getAction() == SignalActionType.SELL_CANDIDATE) {
            return OrderSideType.SELL;
        }
        throw new IllegalArgumentException("order_side is required for signal action: " + signal.getAction());
    }

    private String buildRecommendationReason(SignalDetailDto detail, String requestReason) {
        String base = detail == null ? "" : safe(detail.getDecisionWhy());
        if (base.isBlank()) {
            base = detail == null ? "" : safe(detail.getExplainText());
        }
        if (base.isBlank()) {
            base = "시그널 기반 관리자 승인 후보";
        }
        String req = safe(requestReason);
        if (!req.isBlank()) {
            return trim(base + " | 요청메모: " + req, 500);
        }
        return trim(base, 500);
    }

    private Map<String, Object> signalSummarySnapshot(TradingSignalEntity signal) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("signal_id", signal.getId());
        snapshot.put("asset_code", signal.getAssetCode());
        snapshot.put("country", safe(signal.getCountry()));
        snapshot.put("theme", safe(signal.getTheme()));
        snapshot.put("action", signal.getAction() == null ? "" : signal.getAction().name());
        snapshot.put("combined_confidence", scale(signal.getCombinedConfidence(), 4));
        snapshot.put("good_news_probability", scale(signal.getGoodNewsProbability(), 4));
        snapshot.put("bad_news_probability", scale(signal.getBadNewsProbability(), 4));
        snapshot.put("news_confidence", scale(signal.getNewsConfidence(), 4));
        snapshot.put("blocked_reason", safe(signal.getBlockedReason()));
        snapshot.put("generated_at", signal.getGeneratedAt());
        snapshot.put("trace_id", safe(signal.getTraceId()));
        return snapshot;
    }

    private Map<String, Object> quoteSnapshot(MarketQuoteSnapshotEntity quote) {
        if (quote == null) {
            return Map.of("exists", false);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("exists", true);
        snapshot.put("asset_code", safe(quote.getAssetCode()));
        snapshot.put("provider_name", safe(quote.getProviderName()));
        snapshot.put("snapshot_utc", quote.getSnapshotUtc());
        snapshot.put("quote_time_utc", quote.getQuoteTimeUtc());
        snapshot.put("last_price", quote.getLastPrice());
        snapshot.put("change_pct", quote.getChangePct());
        snapshot.put("volume", quote.getVolume());
        snapshot.put("bid_price", quote.getBidPrice());
        snapshot.put("ask_price", quote.getAskPrice());
        return snapshot;
    }

    private Map<String, Object> workflowDetailMap(OrderApprovalWorkflowEntity workflow, boolean includeEvents) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflow", workflowCoreMap(workflow));
        data.put("snapshots", Map.of(
                "signal", parseJsonObject(workflow.getSignalSnapshotJson()),
                "signal_detail", parseJsonObject(workflow.getSignalDetailSnapshotJson()),
                "quote", parseJsonObject(workflow.getQuoteSnapshotJson()),
                "risk", parseJsonObject(workflow.getRiskSnapshotJson()),
                "assistant", parseJsonObject(workflow.getAssistantSnapshotJson()),
                "news_context_refs", parseJsonArray(workflow.getNewsContextRefsJson())));
        if (workflow.getPaperOrderId() != null) {
            PaperTradeOrderEntity order = paperTradeOrderRepository.findById(workflow.getPaperOrderId()).orElse(null);
            data.put("paper_order", order == null ? Map.of() : paperOrderMap(order));
        } else {
            data.put("paper_order", Map.of());
        }
        if (includeEvents) {
            data.put("events", eventLogRepository.findTop300ByWorkflowIdOrderByCreatedAtDesc(workflow.getId())
                    .stream().map(this::eventItem).toList());
        }
        data.put("pipeline_state_machine", List.of("ANALYZE", "RECOMMEND", "APPROVE", "ORDER_REQUESTED", "ORDER_EXECUTED"));
        return data;
    }

    private Map<String, Object> workflowListItem(OrderApprovalWorkflowEntity row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("signal_id", safe(row.getSignalId()));
        item.put("asset_code", safe(row.getAssetCode()));
        item.put("asset_name", row.getAsset() == null ? "" : safe(row.getAsset().getAssetName()));
        item.put("country", safe(row.getCountry()));
        item.put("theme", safe(row.getTheme()));
        item.put("order_side", row.getOrderSide() == null ? "" : row.getOrderSide().name());
        item.put("recommended_action", row.getRecommendedAction() == null ? "" : row.getRecommendedAction().name());
        item.put("current_stage", row.getCurrentStage() == null ? "" : row.getCurrentStage().name());
        item.put("recommendation_confidence", row.getRecommendationConfidence());
        item.put("recommendation_reason", safe(row.getRecommendationReason()));
        item.put("approved_by", safe(row.getApprovedBy()));
        item.put("approved_at", row.getApprovedAt());
        item.put("paper_order_id", row.getPaperOrderId());
        item.put("paper_order_status", row.getPaperOrderStatus() == null ? "" : row.getPaperOrderStatus().name());
        item.put("order_execution_mode", safe(row.getOrderExecutionMode()));
        item.put("order_error_code", safe(row.getOrderErrorCode()));
        item.put("trace_id", safe(row.getTraceId()));
        item.put("updated_at", row.getUpdatedAt());
        return item;
    }

    private Map<String, Object> workflowCoreMap(OrderApprovalWorkflowEntity row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("signal_id", safe(row.getSignalId()));
        item.put("asset_code", safe(row.getAssetCode()));
        item.put("asset_name", row.getAsset() == null ? "" : safe(row.getAsset().getAssetName()));
        item.put("country", safe(row.getCountry()));
        item.put("theme", safe(row.getTheme()));
        item.put("order_side", row.getOrderSide() == null ? "" : row.getOrderSide().name());
        item.put("recommended_action", row.getRecommendedAction() == null ? "" : row.getRecommendedAction().name());
        item.put("current_stage", row.getCurrentStage() == null ? "" : row.getCurrentStage().name());
        item.put("recommendation_confidence", row.getRecommendationConfidence());
        item.put("recommendation_reason", safe(row.getRecommendationReason()));
        item.put("analyze_at", row.getAnalyzeAt());
        item.put("recommended_at", row.getRecommendedAt());
        item.put("approved_by", safe(row.getApprovedBy()));
        item.put("approved_at", row.getApprovedAt());
        item.put("approval_reason", safe(row.getApprovalReason()));
        item.put("rejected_by", safe(row.getRejectedBy()));
        item.put("rejected_at", row.getRejectedAt());
        item.put("reject_reason", safe(row.getRejectReason()));
        item.put("order_requested_by", safe(row.getOrderRequestedBy()));
        item.put("order_requested_at", row.getOrderRequestedAt());
        item.put("order_request_reason", safe(row.getOrderRequestReason()));
        item.put("order_executed_at", row.getOrderExecutedAt());
        item.put("order_execution_mode", safe(row.getOrderExecutionMode()));
        item.put("paper_order_id", row.getPaperOrderId());
        item.put("paper_order_status", row.getPaperOrderStatus() == null ? "" : row.getPaperOrderStatus().name());
        item.put("order_error_code", safe(row.getOrderErrorCode()));
        item.put("order_error_message", safe(row.getOrderErrorMessage()));
        item.put("live_trade_requested", Boolean.TRUE.equals(row.getLiveTradeRequested()));
        item.put("live_trade_blocked_reason", safe(row.getLiveTradeBlockedReason()));
        item.put("trace_id", safe(row.getTraceId()));
        item.put("created_at", row.getCreatedAt());
        item.put("updated_at", row.getUpdatedAt());
        return item;
    }

    private Map<String, Object> paperOrderMap(PaperTradeOrderEntity order) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", order.getId());
        item.put("asset_code", safe(order.getAssetCode()));
        item.put("asset_name", order.getAsset() == null ? "" : safe(order.getAsset().getAssetName()));
        item.put("signal_id", safe(order.getSignalId()));
        item.put("order_side", order.getOrderSide() == null ? "" : order.getOrderSide().name());
        item.put("order_type", order.getOrderType() == null ? "" : order.getOrderType().name());
        item.put("status", order.getStatus() == null ? "" : order.getStatus().name());
        item.put("blocked_reason", safe(order.getBlockedReason()));
        item.put("request_ratio", order.getRequestRatio());
        item.put("request_amount", order.getRequestAmount());
        item.put("request_price", order.getRequestPrice());
        item.put("executed_price", order.getExecutedPrice());
        item.put("executed_amount", order.getExecutedAmount());
        item.put("executed_at", order.getExecutedAt());
        item.put("risk_checks", parseJsonArray(order.getRiskChecks()));
        item.put("trace_id", safe(order.getTraceId()));
        item.put("created_at", order.getCreatedAt());
        return item;
    }

    private Map<String, Object> eventItem(OrderApprovalEventLogEntity row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("workflow_id", row.getWorkflowId());
        item.put("event_type", row.getEventType() == null ? "" : row.getEventType().name());
        item.put("from_stage", row.getFromStage() == null ? "" : row.getFromStage().name());
        item.put("to_stage", row.getToStage() == null ? "" : row.getToStage().name());
        item.put("actor", safe(row.getActor()));
        item.put("actor_role", safe(row.getActorRole()));
        item.put("reason", safe(row.getReason()));
        item.put("success", Boolean.TRUE.equals(row.getSuccess()));
        item.put("error_code", safe(row.getErrorCode()));
        item.put("request", parseJsonObject(row.getRequestJson()));
        item.put("response", parseJsonObject(row.getResponseJson()));
        item.put("trace_id", safe(row.getTraceId()));
        item.put("created_at", row.getCreatedAt());
        return item;
    }

    private void logEvent(
            OrderApprovalWorkflowEntity workflow,
            OrderApprovalEventType eventType,
            OrderApprovalWorkflowStageType fromStage,
            OrderApprovalWorkflowStageType toStage,
            String actor,
            String actorRole,
            String reason,
            boolean success,
            String errorCode,
            Map<String, Object> request,
            Map<String, Object> response) {
        OrderApprovalEventLogEntity event = new OrderApprovalEventLogEntity();
        event.setWorkflowId(workflow.getId());
        event.setEventType(eventType);
        event.setFromStage(fromStage);
        event.setToStage(toStage);
        event.setActor(actorOrDefault(actor));
        event.setActorRole(trim(actorRole, 40));
        event.setReason(trim(reason, 1000));
        event.setSuccess(success);
        event.setErrorCode(trim(errorCode, 80));
        event.setRequestJson(toJson(request == null ? Map.of() : request));
        event.setResponseJson(toJson(response == null ? Map.of() : response));
        event.setTraceId(pipelineTraceId(workflow));
        eventLogRepository.save(event);
    }

    private String pipelineTraceId(OrderApprovalWorkflowEntity workflow) {
        if (workflow != null) {
            String existing = safe(workflow.getTraceId());
            if (!existing.isBlank()) {
                return existing;
            }
        }
        return currentRequestTraceId();
    }

    private void alignPaperOrderTraceId(Long paperOrderId, String pipelineTraceId) {
        if (paperOrderId == null) {
            return;
        }
        String normalizedTraceId = safe(pipelineTraceId);
        if (normalizedTraceId.isBlank()) {
            return;
        }
        paperTradeOrderRepository.findById(paperOrderId).ifPresent(order -> {
            if (!normalizedTraceId.equals(safe(order.getTraceId()))) {
                order.setTraceId(normalizedTraceId);
                paperTradeOrderRepository.save(order);
            }
        });
    }

    private OrderApprovalWorkflowStageType parseStage(String stage) {
        String normalized = safe(stage).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return OrderApprovalWorkflowStageType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid workflow stage: " + stage);
        }
    }

    private Map<String, Object> parseJsonObject(String raw) {
        String json = safe(raw);
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_parse_error", "invalid_json_object");
            fallback.put("_raw", trim(json, 1000));
            return fallback;
        }
    }

    private List<Object> parseJsonArray(String raw) {
        String json = safe(raw);
        if (json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception ex) {
            List<Object> fallback = new ArrayList<>();
            fallback.add(Map.of("_parse_error", "invalid_json_array", "_raw", trim(json, 1000)));
            return fallback;
        }
    }

    private String safeJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        try {
            List<Object> list = objectMapper.readValue(raw, new TypeReference<List<Object>>() {});
            return objectMapper.writeValueAsString(list);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            if (value instanceof List<?>) {
                return "[]";
            }
            return "{}";
        }
    }

    private BigDecimal scale(BigDecimal value, int digits) {
        if (value == null) {
            return null;
        }
        return value.setScale(Math.max(0, digits), RoundingMode.HALF_UP);
    }

    private String actorOrDefault(String actor) {
        String normalized = trim(actor, 80);
        return normalized.isBlank() ? "admin-ui" : normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= Math.max(0, max)) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, max));
    }

    private String currentRequestTraceId() {
        String trace = MDC.get("trace_id");
        if (trace != null && !trace.isBlank()) {
            return trace;
        }
        return UUID.randomUUID().toString();
    }

    private String traceId() {
        return currentRequestTraceId();
    }
}

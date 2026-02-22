package com.wangbyul.gnd.api.service.orderapproval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.OrderApprovalApproveRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalOrderRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalRejectRequest;
import com.wangbyul.gnd.api.dto.PaperTradeOrderResultDto;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.api.service.signal.PaperTradeSimulationService;
import com.wangbyul.gnd.api.service.signal.RiskPolicyService;
import com.wangbyul.gnd.api.service.signal.TradingSignalEngineService;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 주문 승인 파이프라인 서비스 테스트(준비 단계).
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class OrderApprovalPipelineServiceTest {

    @Mock
    private OrderApprovalWorkflowRepository workflowRepository;
    @Mock
    private OrderApprovalEventLogRepository eventLogRepository;
    @Mock
    private TradingSignalRepository tradingSignalRepository;
    @Mock
    private TradingSignalEngineService tradingSignalEngineService;
    @Mock
    private MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    @Mock
    private PaperTradeOrderRepository paperTradeOrderRepository;
    @Mock
    private PaperTradeSimulationService paperTradeSimulationService;
    @Mock
    private RiskPolicyService riskPolicyService;
    @Mock
    private SystemFeatureToggleService systemFeatureToggleService;

    private OrderApprovalPipelineService service;

    @BeforeEach
    void setUp() {
        service = new OrderApprovalPipelineService(
                workflowRepository,
                eventLogRepository,
                tradingSignalRepository,
                tradingSignalEngineService,
                marketQuoteSnapshotRepository,
                paperTradeOrderRepository,
                paperTradeSimulationService,
                riskPolicyService,
                systemFeatureToggleService,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "liveTradeEnabledProperty", false);

        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventLogRepository.findTop300ByWorkflowIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(paperTradeOrderRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void approveAndRejectShouldUpdateStageAndWriteAuditLog() {
        OrderApprovalWorkflowEntity workflow = baseWorkflow(101L, OrderApprovalWorkflowStageType.RECOMMEND);
        when(workflowRepository.findById(101L)).thenReturn(Optional.of(workflow));
        when(systemFeatureToggleService.isFeatureEnabled(eq("AUTO_ORDER_WITH_ADMIN_APPROVAL"), any(), any(), any()))
                .thenReturn(false);

        OrderApprovalApproveRequest approve = new OrderApprovalApproveRequest();
        approve.setApprovedBy("admin1");
        approve.setApprovalReason("근거 검토 후 승인");
        approve.setAutoRequestPaperOrder(false);

        Map<String, Object> approved = service.approveRecommendation(101L, approve);
        Map<?, ?> approvedWorkflow = (Map<?, ?>) approved.get("workflow");
        assertThat(approvedWorkflow.get("current_stage")).isEqualTo("APPROVE");
        assertThat(approvedWorkflow.get("approved_by")).isEqualTo("admin1");
        assertThat(approved.get("auto_order_with_admin_approval_enabled")).isEqualTo(false);

        OrderApprovalWorkflowEntity rejectWorkflow = baseWorkflow(202L, OrderApprovalWorkflowStageType.RECOMMEND);
        when(workflowRepository.findById(202L)).thenReturn(Optional.of(rejectWorkflow));

        OrderApprovalRejectRequest reject = new OrderApprovalRejectRequest();
        reject.setRejectedBy("admin2");
        reject.setRejectReason("리스크 과다");
        Map<String, Object> rejected = service.rejectRecommendation(202L, reject);
        Map<?, ?> rejectedWorkflow = (Map<?, ?>) rejected.get("workflow");
        assertThat(rejectedWorkflow.get("current_stage")).isEqualTo("REJECTED");
        assertThat(rejectedWorkflow.get("rejected_by")).isEqualTo("admin2");

        verify(eventLogRepository, atLeastOnce()).save(any());
    }

    @Test
    void requestPaperOrderShouldStayPaperModeWhenLiveTradeOff() {
        OrderApprovalWorkflowEntity workflow = baseWorkflow(303L, OrderApprovalWorkflowStageType.APPROVE);
        workflow.setSignalId("sig-303");
        workflow.setAssetCode("AAA1");
        workflow.setOrderSide(OrderSideType.BUY);
        when(workflowRepository.findById(303L)).thenReturn(Optional.of(workflow));
        when(systemFeatureToggleService.isFeatureEnabled(eq("LIVE_TRADE"), any(), any(), any())).thenReturn(false);

        PaperTradeOrderResultDto paperResult = PaperTradeOrderResultDto.builder()
                .orderId(700L)
                .assetCode("AAA1")
                .status(OrderStatusType.FILLED)
                .requestRatio(BigDecimal.valueOf(0.2d))
                .requestAmount(BigDecimal.valueOf(100000))
                .executedPrice(BigDecimal.valueOf(50000))
                .executedAmount(BigDecimal.valueOf(100000))
                .riskChecks(List.of("BUY_LOCK:PASS"))
                .blockedReason(null)
                .createdAt(OffsetDateTime.now())
                .build();
        when(paperTradeSimulationService.execute(any())).thenReturn(paperResult);

        PaperTradeOrderEntity paperOrder = new PaperTradeOrderEntity();
        paperOrder.setId(700L);
        paperOrder.setAssetCode("AAA1");
        paperOrder.setSignalId("sig-303");
        paperOrder.setOrderSide(OrderSideType.BUY);
        paperOrder.setStatus(OrderStatusType.FILLED);
        paperOrder.setRiskChecks("[]");
        paperOrder.setCreatedAt(OffsetDateTime.now());
        when(paperTradeOrderRepository.findById(700L)).thenReturn(Optional.of(paperOrder));

        OrderApprovalOrderRequest request = new OrderApprovalOrderRequest();
        request.setRequestedBy("admin-ui");
        request.setRequestReason("승인 완료 후 paper 실행");
        request.setForcePaper(true);

        Map<String, Object> result = service.requestPaperOrder(303L, request);
        Map<?, ?> workflowMap = (Map<?, ?>) result.get("workflow");
        assertThat(workflowMap.get("current_stage")).isEqualTo("ORDER_EXECUTED");
        assertThat(workflowMap.get("order_execution_mode")).isEqualTo("PAPER_ONLY");
        assertThat(workflowMap.get("live_trade_requested")).isEqualTo(false);
        assertThat(workflowMap.get("paper_order_id")).isEqualTo(700L);
        assertThat(workflowMap.get("live_trade_blocked_reason")).isEqualTo("LIVE_TRADE_DISABLED_DEFAULT_OFF");
        verify(paperTradeSimulationService).execute(any());
        verify(eventLogRepository, atLeastOnce()).save(any());
    }

    private OrderApprovalWorkflowEntity baseWorkflow(Long id, OrderApprovalWorkflowStageType stage) {
        OrderApprovalWorkflowEntity workflow = new OrderApprovalWorkflowEntity();
        workflow.setId(id);
        workflow.setSignalId("sig-" + id);
        workflow.setAssetCode("AAA1");
        workflow.setCountry("KR");
        workflow.setTheme("AI");
        workflow.setOrderSide(OrderSideType.BUY);
        workflow.setRecommendedAction(SignalActionType.BUY_CANDIDATE);
        workflow.setCurrentStage(stage);
        workflow.setRecommendationConfidence(BigDecimal.valueOf(0.73d));
        workflow.setRecommendationReason("테스트 추천");
        workflow.setSignalSnapshotJson("{}");
        workflow.setSignalDetailSnapshotJson("{}");
        workflow.setQuoteSnapshotJson("{}");
        workflow.setRiskSnapshotJson("{}");
        workflow.setNewsContextRefsJson("[]");
        workflow.setAssistantSnapshotJson("{}");
        workflow.setPaperOrderResultJson("{}");
        workflow.setOrderExecutionMode("PAPER_ONLY");
        workflow.setLiveTradeRequested(false);
        workflow.setTraceId("trace-test-" + id);
        workflow.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
        workflow.setUpdatedAt(OffsetDateTime.now());
        return workflow;
    }
}

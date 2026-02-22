package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.OrderApprovalApproveRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalCreateRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalOrderRequest;
import com.wangbyul.gnd.api.dto.OrderApprovalRejectRequest;
import com.wangbyul.gnd.api.service.orderapproval.OrderApprovalPipelineService;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 승인 기반 주문 파이프라인 컨트롤러.
 *
 * 실주문은 기본 OFF이며, 추천/승인/반려/모의주문 요청 흐름과 감사 추적 조회를 제공한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Validated
@RestController
@RequestMapping("/api/admin/order-approvals")
public class AdminOrderApprovalController {

    private final OrderApprovalPipelineService orderApprovalPipelineService;

    public AdminOrderApprovalController(OrderApprovalPipelineService orderApprovalPipelineService) {
        this.orderApprovalPipelineService = orderApprovalPipelineService;
    }

    @GetMapping
    public ApiEnvelope<Map<String, Object>> list(
            @RequestParam(name = "country", required = false) String country,
            @RequestParam(name = "stage", required = false) String stage,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        Map<String, Object> data = orderApprovalPipelineService.listRecommendations(country, stage, limit);
        return envelope(data, Map.of("scope", "order-approval-list", "limit", limit));
    }

    @GetMapping("/{workflowId}")
    public ApiEnvelope<Map<String, Object>> detail(@PathVariable("workflowId") Long workflowId) {
        Map<String, Object> data = orderApprovalPipelineService.getRecommendationDetail(workflowId);
        return envelope(data, Map.of("scope", "order-approval-detail", "workflow_id", workflowId));
    }

    @PostMapping("/recommendations")
    public ApiEnvelope<Map<String, Object>> createRecommendation(@RequestBody @Valid OrderApprovalCreateRequest request) {
        Map<String, Object> data = orderApprovalPipelineService.createRecommendation(request);
        return envelope(data, Map.of("scope", "order-approval-create"));
    }

    @PostMapping("/{workflowId}/approve")
    public ApiEnvelope<Map<String, Object>> approve(
            @PathVariable("workflowId") Long workflowId,
            @RequestBody @Valid OrderApprovalApproveRequest request) {
        Map<String, Object> data = orderApprovalPipelineService.approveRecommendation(workflowId, request);
        return envelope(data, Map.of("scope", "order-approval-approve", "workflow_id", workflowId));
    }

    @PostMapping("/{workflowId}/reject")
    public ApiEnvelope<Map<String, Object>> reject(
            @PathVariable("workflowId") Long workflowId,
            @RequestBody @Valid OrderApprovalRejectRequest request) {
        Map<String, Object> data = orderApprovalPipelineService.rejectRecommendation(workflowId, request);
        return envelope(data, Map.of("scope", "order-approval-reject", "workflow_id", workflowId));
    }

    @PostMapping("/{workflowId}/order-request")
    public ApiEnvelope<Map<String, Object>> requestPaperOrder(
            @PathVariable("workflowId") Long workflowId,
            @RequestBody @Valid OrderApprovalOrderRequest request) {
        Map<String, Object> data = orderApprovalPipelineService.requestPaperOrder(workflowId, request);
        return envelope(data, Map.of("scope", "order-approval-order-request", "workflow_id", workflowId));
    }

    @GetMapping("/trace")
    public ApiEnvelope<Map<String, Object>> traceSummary(
            @RequestParam(name = "trace_id") String traceId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        Map<String, Object> data = orderApprovalPipelineService.traceSummary(traceId, limit);
        return envelope(data, Map.of("scope", "order-approval-trace", "limit", limit));
    }

    private ApiEnvelope<Map<String, Object>> envelope(Map<String, Object> data, Map<String, Object> meta) {
        return ApiEnvelope.<Map<String, Object>>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }
}

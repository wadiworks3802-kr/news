package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.AdminCapitalConfigRequest;
import com.wangbyul.gnd.api.dto.AdminRiskPolicyRequest;
import com.wangbyul.gnd.api.dto.PaperTradeOrderRequestDto;
import com.wangbyul.gnd.api.dto.PaperTradeOrderResultDto;
import com.wangbyul.gnd.api.service.PaperTradeConfigService;
import com.wangbyul.gnd.api.service.signal.PaperTradeSimulationService;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모의매매 정책 설정 ADMIN 컨트롤러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Validated
@RestController
@RequestMapping("/api/admin/paper-trade")
public class AdminPaperTradeController {

    private final PaperTradeConfigService paperTradeConfigService;
    private final PaperTradeSimulationService paperTradeSimulationService;

    public AdminPaperTradeController(
            PaperTradeConfigService paperTradeConfigService,
            PaperTradeSimulationService paperTradeSimulationService) {
        this.paperTradeConfigService = paperTradeConfigService;
        this.paperTradeSimulationService = paperTradeSimulationService;
    }

    @PostMapping("/risk-policy")
    public ApiEnvelope<Map<String, Object>> updateRiskPolicy(@RequestBody @Valid AdminRiskPolicyRequest request) {
        Map<String, Object> result = paperTradeConfigService.applyRiskPolicy(request);
        return ApiEnvelope.<Map<String, Object>>builder()
                .data(result)
                .meta(Map.of("scope", "risk-policy"))
                .traceId(traceId())
                .build();
    }

    @PostMapping("/capital-config")
    public ApiEnvelope<Map<String, Object>> updateCapitalConfig(@RequestBody @Valid AdminCapitalConfigRequest request) {
        Map<String, Object> result = paperTradeConfigService.applyCapitalConfig(request);
        return ApiEnvelope.<Map<String, Object>>builder()
                .data(result)
                .meta(Map.of("scope", "capital-config"))
                .traceId(traceId())
                .build();
    }

    @PostMapping("/orders/simulate")
    public ApiEnvelope<PaperTradeOrderResultDto> simulateOrder(@RequestBody @Valid PaperTradeOrderRequestDto request) {
        PaperTradeOrderResultDto result = paperTradeSimulationService.execute(request);
        return ApiEnvelope.<PaperTradeOrderResultDto>builder()
                .data(result)
                .meta(Map.of("scope", "paper-trade-order", "status", result.getStatus().name()))
                .traceId(traceId())
                .build();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }
}

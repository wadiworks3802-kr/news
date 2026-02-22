package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.BuyLockStatusDto;
import com.wangbyul.gnd.api.dto.PaperTradeRiskDto;
import com.wangbyul.gnd.api.service.signal.RiskPolicyService;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모의매매 리스크/잠금 조회 컨트롤러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@RestController
@RequestMapping("/api/paper-trade")
public class PaperTradeController {

    private final RiskPolicyService riskPolicyService;

    public PaperTradeController(RiskPolicyService riskPolicyService) {
        this.riskPolicyService = riskPolicyService;
    }

    @GetMapping("/risk/portfolio")
    public ApiEnvelope<PaperTradeRiskDto> getPortfolioRisk(
            @RequestParam(name = "capital_total", required = false) BigDecimal capitalTotal) {
        return ApiEnvelope.<PaperTradeRiskDto>builder()
                .data(riskPolicyService.portfolioRisk(capitalTotal))
                .meta(java.util.Map.of(
                        "policy_mode", "NO_ALL_IN",
                        "reference_only", true,
                        "capital_total_input_applied", capitalTotal != null))
                .traceId(traceId())
                .build();
    }

    @GetMapping("/locks")
    public ApiEnvelope<List<BuyLockStatusDto>> getBuyLocks() {
        return ApiEnvelope.<List<BuyLockStatusDto>>builder()
                .data(riskPolicyService.buyLocks())
                .meta(java.util.Map.of("lock_type", "BUY_LOCK"))
                .traceId(traceId())
                .build();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }
}

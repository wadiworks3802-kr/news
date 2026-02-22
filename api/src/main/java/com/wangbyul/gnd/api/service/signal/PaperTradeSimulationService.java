package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.PaperTradeOrderRequestDto;
import com.wangbyul.gnd.api.dto.PaperTradeOrderResultDto;
import com.wangbyul.gnd.api.service.signal.model.RiskDecision;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.OrderSideType;
import com.wangbyul.gnd.core.domain.OrderStatusType;
import com.wangbyul.gnd.core.domain.OrderType;
import com.wangbyul.gnd.core.domain.PaperTradeOrderEntity;
import com.wangbyul.gnd.core.domain.PaperTradePositionEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.PaperTradeOrderRepository;
import com.wangbyul.gnd.core.repository.PaperTradePositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모의매매 실행 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class PaperTradeSimulationService {

    private final AssetUniverseRepository assetUniverseRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final PaperTradeOrderRepository paperTradeOrderRepository;
    private final PaperTradePositionRepository paperTradePositionRepository;
    private final RiskPolicyService riskPolicyService;
    private final ReanalysisLockService reanalysisLockService;
    private final ObjectMapper objectMapper;

    public PaperTradeSimulationService(
            AssetUniverseRepository assetUniverseRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            PaperTradeOrderRepository paperTradeOrderRepository,
            PaperTradePositionRepository paperTradePositionRepository,
            RiskPolicyService riskPolicyService,
            ReanalysisLockService reanalysisLockService,
            ObjectMapper objectMapper) {
        this.assetUniverseRepository = assetUniverseRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.paperTradeOrderRepository = paperTradeOrderRepository;
        this.paperTradePositionRepository = paperTradePositionRepository;
        this.riskPolicyService = riskPolicyService;
        this.reanalysisLockService = reanalysisLockService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaperTradeOrderResultDto execute(PaperTradeOrderRequestDto request) {
        AssetUniverseEntity asset = assetUniverseRepository.findById(request.getAssetCode())
                .orElseThrow(() -> new IllegalArgumentException("asset not found: " + request.getAssetCode()));

        BigDecimal marketPrice = marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(asset.getAssetCode())
                .map(quote -> quote.getLastPrice() == null ? BigDecimal.ZERO : quote.getLastPrice())
                .orElse(BigDecimal.ZERO);
        if (marketPrice.compareTo(BigDecimal.ZERO) <= 0) {
            marketPrice = BigDecimal.ONE;
        }

        return request.getOrderSide() == OrderSideType.BUY
                ? executeBuy(asset, request, marketPrice)
                : executeSell(asset, request, marketPrice);
    }

    private PaperTradeOrderResultDto executeBuy(AssetUniverseEntity asset, PaperTradeOrderRequestDto request, BigDecimal marketPrice) {
        BigDecimal ratio = resolveBuyRatio(asset.getAssetCode());
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.BUY_CANDIDATE, ratio);
        if (!decision.allowed()) {
            PaperTradeOrderEntity blocked = saveBlockedOrder(asset, request, ratio, decision);
            return toResult(blocked, decision.riskChecks());
        }

        BigDecimal capital = riskPolicyService.effectiveCapitalTotal();
        BigDecimal amount = capital.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal qty = amount.divide(marketPrice, 6, RoundingMode.HALF_UP);

        PaperTradeOrderEntity order = new PaperTradeOrderEntity();
        order.setAssetCode(asset.getAssetCode());
        order.setSignalId(request.getSignalId());
        order.setOrderSide(OrderSideType.BUY);
        order.setOrderType(OrderType.SPLIT);
        order.setRequestRatio(ratio);
        order.setRequestAmount(amount);
        order.setRequestPrice(marketPrice);
        order.setStatus(OrderStatusType.FILLED);
        order.setRiskChecks(toJson(decision.riskChecks()));
        order.setExecutedPrice(marketPrice);
        order.setExecutedAmount(amount);
        order.setExecutedAt(OffsetDateTime.now());
        order.setTraceId(traceId());
        PaperTradeOrderEntity savedOrder = paperTradeOrderRepository.save(order);

        PaperTradePositionEntity position = paperTradePositionRepository.findByAssetCode(asset.getAssetCode())
                .orElseGet(() -> {
                    PaperTradePositionEntity p = new PaperTradePositionEntity();
                    p.setAssetCode(asset.getAssetCode());
                    return p;
                });
        BigDecimal prevQty = nvl(position.getQuantity());
        BigDecimal prevAvg = nvl(position.getAvgPrice());
        BigDecimal newQty = prevQty.add(qty);
        BigDecimal newAvg = newQty.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : prevAvg.multiply(prevQty).add(marketPrice.multiply(qty)).divide(newQty, 6, RoundingMode.HALF_UP);
        position.setQuantity(newQty);
        position.setAvgPrice(newAvg);
        position.setInvestedAmount(newQty.multiply(newAvg).setScale(2, RoundingMode.HALF_UP));
        position.setCurrentPrice(marketPrice);
        position.setAvgDownStage(Math.min(position.getAvgDownStage() + 1, riskPolicyService.effectiveBuySplitRules().size()));
        position.setPnlPct(calcPnlPct(newAvg, marketPrice));
        paperTradePositionRepository.save(position);

        return toResult(savedOrder, decision.riskChecks());
    }

    private PaperTradeOrderResultDto executeSell(AssetUniverseEntity asset, PaperTradeOrderRequestDto request, BigDecimal marketPrice) {
        PaperTradePositionEntity position = paperTradePositionRepository.findByAssetCode(asset.getAssetCode()).orElse(null);
        if (position == null || nvl(position.getQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
            RiskDecision decision = new RiskDecision(false, SignalActionType.HOLD, List.of("NO_POSITION:FAIL"), "보유 포지션 없음");
            PaperTradeOrderEntity blocked = saveBlockedOrder(asset, request, BigDecimal.ZERO, decision);
            return toResult(blocked, decision.riskChecks());
        }

        BigDecimal ratio = resolveSellRatio();
        BigDecimal sellQty = nvl(position.getQuantity()).multiply(ratio).setScale(6, RoundingMode.HALF_UP);
        if (sellQty.compareTo(BigDecimal.ZERO) <= 0) {
            sellQty = position.getQuantity();
        }
        if (sellQty.compareTo(position.getQuantity()) > 0) {
            sellQty = position.getQuantity();
        }

        BigDecimal amount = sellQty.multiply(marketPrice).setScale(2, RoundingMode.HALF_UP);
        PaperTradeOrderEntity order = new PaperTradeOrderEntity();
        order.setAssetCode(asset.getAssetCode());
        order.setSignalId(request.getSignalId());
        order.setOrderSide(OrderSideType.SELL);
        order.setOrderType(OrderType.SPLIT);
        order.setRequestRatio(ratio);
        order.setRequestAmount(amount);
        order.setRequestPrice(marketPrice);
        order.setStatus(OrderStatusType.FILLED);
        order.setRiskChecks(toJson(List.of("SELL_POSITION:PASS")));
        order.setExecutedPrice(marketPrice);
        order.setExecutedAmount(amount);
        order.setExecutedAt(OffsetDateTime.now());
        order.setTraceId(traceId());
        PaperTradeOrderEntity savedOrder = paperTradeOrderRepository.save(order);

        BigDecimal newQty = position.getQuantity().subtract(sellQty);
        if (newQty.compareTo(BigDecimal.ZERO) < 0) {
            newQty = BigDecimal.ZERO;
        }
        position.setQuantity(newQty);
        position.setCurrentPrice(marketPrice);
        position.setInvestedAmount(newQty.multiply(position.getAvgPrice()).setScale(2, RoundingMode.HALF_UP));
        position.setPnlPct(calcPnlPct(position.getAvgPrice(), marketPrice));
        if (newQty.compareTo(BigDecimal.ZERO) == 0) {
            position.setAvgDownStage(0);
        }
        applyTpSlLock(position);
        paperTradePositionRepository.save(position);

        return toResult(savedOrder, List.of("SELL_POSITION:PASS"));
    }

    private void applyTpSlLock(PaperTradePositionEntity position) {
        BigDecimal pnlPct = nvl(position.getPnlPct()).multiply(BigDecimal.valueOf(100d));
        BigDecimal tp = riskPolicyService.effectiveTakeProfitPct();
        BigDecimal sl = riskPolicyService.effectiveStopLossPct().multiply(BigDecimal.valueOf(-1d));
        boolean lockOn = Boolean.TRUE.equals(riskPolicyService.effectiveReanalysisLockAfterTpSl());
        if (!lockOn) {
            return;
        }
        if (pnlPct.compareTo(tp) >= 0) {
            reanalysisLockService.lockAfterTpSl(position.getAssetCode(), "TAKE_PROFIT");
        } else if (pnlPct.compareTo(sl) <= 0) {
            reanalysisLockService.lockAfterTpSl(position.getAssetCode(), "STOP_LOSS");
        }
    }

    private PaperTradeOrderEntity saveBlockedOrder(
            AssetUniverseEntity asset,
            PaperTradeOrderRequestDto request,
            BigDecimal ratio,
            RiskDecision decision) {
        PaperTradeOrderEntity order = new PaperTradeOrderEntity();
        order.setAssetCode(asset.getAssetCode());
        order.setSignalId(request.getSignalId());
        order.setOrderSide(request.getOrderSide());
        order.setOrderType(OrderType.SPLIT);
        order.setRequestRatio(ratio);
        order.setStatus(OrderStatusType.BLOCKED);
        order.setBlockedReason(decision.blockedReason());
        order.setRiskChecks(toJson(decision.riskChecks()));
        order.setTraceId(traceId());
        return paperTradeOrderRepository.save(order);
    }

    private PaperTradeOrderResultDto toResult(PaperTradeOrderEntity order, List<String> checks) {
        return PaperTradeOrderResultDto.builder()
                .orderId(order.getId())
                .assetCode(order.getAssetCode())
                .status(order.getStatus())
                .requestRatio(order.getRequestRatio())
                .requestAmount(order.getRequestAmount())
                .executedPrice(order.getExecutedPrice())
                .executedAmount(order.getExecutedAmount())
                .riskChecks(checks)
                .blockedReason(order.getBlockedReason())
                .createdAt(order.getCreatedAt())
                .build();
    }

    private BigDecimal resolveBuyRatio(String assetCode) {
        PaperTradePositionEntity position = paperTradePositionRepository.findByAssetCode(assetCode).orElse(null);
        int stage = position == null ? 0 : Math.max(0, position.getAvgDownStage());
        List<Integer> rules = riskPolicyService.effectiveBuySplitRules();
        if (rules.isEmpty()) {
            return BigDecimal.valueOf(0.2d);
        }
        int index = Math.min(stage, rules.size() - 1);
        return BigDecimal.valueOf(rules.get(index)).divide(BigDecimal.valueOf(100d), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveSellRatio() {
        List<Integer> rules = riskPolicyService.effectiveSellSplitRules();
        if (rules.isEmpty()) {
            return BigDecimal.valueOf(0.5d);
        }
        return BigDecimal.valueOf(rules.get(0)).divide(BigDecimal.valueOf(100d), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calcPnlPct(BigDecimal avgPrice, BigDecimal marketPrice) {
        if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return marketPrice.subtract(avgPrice).divide(avgPrice, 6, RoundingMode.HALF_UP);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }
}


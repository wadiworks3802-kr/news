package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.config.RiskPolicyProperties;
import com.wangbyul.gnd.api.service.StrategyConfigService;
import com.wangbyul.gnd.core.domain.PaperTradePositionEntity;
import com.wangbyul.gnd.core.repository.PaperTradePositionRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 익절/손절 후 재진입 잠금(BUY_LOCK) 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class ReanalysisLockService {

    private final PaperTradePositionRepository paperTradePositionRepository;
    private final RiskPolicyProperties riskPolicyProperties;
    private final StrategyConfigService strategyConfigService;

    public ReanalysisLockService(
            PaperTradePositionRepository paperTradePositionRepository,
            RiskPolicyProperties riskPolicyProperties,
            StrategyConfigService strategyConfigService) {
        this.paperTradePositionRepository = paperTradePositionRepository;
        this.riskPolicyProperties = riskPolicyProperties;
        this.strategyConfigService = strategyConfigService;
    }

    public boolean isBuyLocked(String assetCode) {
        PaperTradePositionEntity position = paperTradePositionRepository.findByAssetCode(assetCode).orElse(null);
        if (position == null || !Boolean.TRUE.equals(position.getBuyLock())) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (position.getLockUntil() != null && now.isAfter(position.getLockUntil())) {
            // 시간 경과 + 재분석 완료 조건이 충족돼야 잠금 해제
            return position.getLastReanalysisAt() == null || position.getLastReanalysisAt().isBefore(position.getLockUntil());
        }
        return true;
    }

    @Transactional
    public void markReanalysisCompleted(String assetCode) {
        paperTradePositionRepository.findByAssetCode(assetCode).ifPresent(position -> {
            position.setLastReanalysisAt(OffsetDateTime.now());
            if (Boolean.TRUE.equals(position.getBuyLock())
                    && position.getLockUntil() != null
                    && position.getLastReanalysisAt().isAfter(position.getLockUntil())) {
                position.setBuyLock(false);
                position.setLockReason(null);
            }
            paperTradePositionRepository.save(position);
        });
    }

    @Transactional
    public void lockAfterTpSl(String assetCode, String reason) {
        int configured = strategyConfigService.getInteger(
                "app.risk.reanalysis-lock-minutes",
                riskPolicyProperties.getReanalysisLockMinutes());
        int lockMinutes = Math.max(1, configured);
        PaperTradePositionEntity position = paperTradePositionRepository.findByAssetCode(assetCode)
                .orElseGet(() -> {
                    PaperTradePositionEntity created = new PaperTradePositionEntity();
                    created.setAssetCode(assetCode);
                    return created;
                });
        position.setBuyLock(Boolean.TRUE);
        position.setLockReason(reason == null || reason.isBlank() ? "TP/SL-triggered" : reason);
        position.setLockUntil(OffsetDateTime.now().plusMinutes(lockMinutes));
        paperTradePositionRepository.save(position);
    }
}

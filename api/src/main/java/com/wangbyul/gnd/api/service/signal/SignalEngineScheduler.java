package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.core.domain.StrategyRunType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시그널 자동 생성 스케줄러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Slf4j
@Component
public class SignalEngineScheduler {

    private final AssetUniverseRepository assetUniverseRepository;
    private final TradingSignalEngineService tradingSignalEngineService;

    @Value("${app.signal.auto-generate-enabled:true}")
    private boolean autoGenerateEnabled;

    @Value("${app.signal.auto-generate-interval-ms:60000}")
    private long autoGenerateIntervalMs;

    public SignalEngineScheduler(
            AssetUniverseRepository assetUniverseRepository,
            TradingSignalEngineService tradingSignalEngineService) {
        this.assetUniverseRepository = assetUniverseRepository;
        this.tradingSignalEngineService = tradingSignalEngineService;
    }

    @Scheduled(fixedDelayString = "${app.signal.auto-generate-interval-ms:60000}")
    public void generateByCountry() {
        if (!autoGenerateEnabled || autoGenerateIntervalMs < 1000) {
            return;
        }
        Set<String> countries = assetUniverseRepository.findAll().stream()
                .map(asset -> asset.getCountry() == null ? "" : asset.getCountry())
                .filter(country -> !country.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        for (String country : countries) {
            try {
                tradingSignalEngineService.generateSignals(country, null, 20, StrategyRunType.SWING);
            } catch (Exception e) {
                log.warn("signal auto-generate failed country={} reason={}", country, e.getMessage());
            }
        }
    }
}


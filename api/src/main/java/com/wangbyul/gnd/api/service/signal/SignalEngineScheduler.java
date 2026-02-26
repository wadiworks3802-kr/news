package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.core.domain.StrategyRunType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final List<StrategyRunType> AUTO_RUN_TYPES = List.of(
            StrategyRunType.SCALP,
            StrategyRunType.SWING,
            StrategyRunType.DISCOVERY);

    private final AssetUniverseRepository assetUniverseRepository;
    private final TradingSignalEngineService tradingSignalEngineService;
    private final AtomicInteger runTypeCursor = new AtomicInteger(0);

    @Value("${app.signal.auto-generate-enabled:true}")
    private boolean autoGenerateEnabled;

    @Value("${app.signal.auto-generate-interval-ms:60000}")
    private long autoGenerateIntervalMs;

    @Value("${app.signal.auto-generate-limit-scalp:12}")
    private int autoGenerateLimitScalp;

    @Value("${app.signal.auto-generate-limit-swing:18}")
    private int autoGenerateLimitSwing;

    @Value("${app.signal.auto-generate-limit-discovery:16}")
    private int autoGenerateLimitDiscovery;

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
        StrategyRunType runType = nextRunType();
        int limit = limitByRunType(runType);
        Set<String> countries = assetUniverseRepository.findAll().stream()
                .map(asset -> asset.getCountry() == null ? "" : asset.getCountry())
                .filter(country -> !country.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        for (String country : countries) {
            try {
                tradingSignalEngineService.generateSignals(country, null, limit, runType);
            } catch (Exception e) {
                log.warn(
                        "signal auto-generate failed country={} runType={} reason={}",
                        country,
                        runType.name(),
                        e.getMessage());
            }
        }
    }

    private StrategyRunType nextRunType() {
        int idx = Math.floorMod(runTypeCursor.getAndIncrement(), AUTO_RUN_TYPES.size());
        return AUTO_RUN_TYPES.get(idx);
    }

    private int limitByRunType(StrategyRunType runType) {
        int resolved = switch (runType) {
            case SCALP -> autoGenerateLimitScalp;
            case SWING -> autoGenerateLimitSwing;
            case DISCOVERY -> autoGenerateLimitDiscovery;
            default -> 12;
        };
        return Math.max(4, resolved);
    }
}

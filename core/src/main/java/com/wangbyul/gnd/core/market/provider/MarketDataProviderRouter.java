package com.wangbyul.gnd.core.market.provider;

import com.wangbyul.gnd.core.config.MarketProviderProperties;
import com.wangbyul.gnd.core.market.MarketDataProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 설정값 기반 Provider 선택/조회 라우터.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Component
public class MarketDataProviderRouter {

    private final Map<String, MarketDataProvider> providers;
    private final MarketProviderProperties properties;

    public MarketDataProviderRouter(List<MarketDataProvider> providers, MarketProviderProperties properties) {
        this.providers = new LinkedHashMap<>();
        for (MarketDataProvider provider : providers) {
            this.providers.put(normalize(provider.providerId()), provider);
        }
        this.properties = properties;
    }

    public String activeProviderId() {
        return normalize(properties.getActive());
    }

    public boolean fallbackToMockOnFailure() {
        return properties.isFallbackToMockOnFailure();
    }

    public boolean allowMock() {
        return properties.isAllowMock();
    }

    public int mockBarsPerAsset() {
        return Math.max(1, properties.getMockBarsPerAsset());
    }

    public MarketDataProvider resolveActiveProvider() {
        return resolveRequired(activeProviderId());
    }

    public MarketDataProvider resolveMockProvider() {
        return resolveRequired("mock");
    }

    public MarketDataProvider resolveRequired(String providerId) {
        String normalized = normalize(providerId);
        MarketDataProvider provider = providers.get(normalized);
        if (provider != null) {
            return provider;
        }
        throw new IllegalStateException("MarketDataProvider not registered: " + normalized);
    }

    public boolean isMockProviderId(String providerId) {
        return "mock".equals(normalize(providerId));
    }

    public MarketDataProvider resolveOrMock(String providerId) {
        MarketDataProvider provider = providers.get(normalize(providerId));
        if (provider != null) {
            return provider;
        }
        MarketDataProvider mock = providers.get("mock");
        if (mock == null) {
            throw new IllegalStateException("MockMarketDataProvider is required but not registered");
        }
        return mock;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "mock";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

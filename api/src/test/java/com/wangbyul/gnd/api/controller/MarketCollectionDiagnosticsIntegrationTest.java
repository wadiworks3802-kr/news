package com.wangbyul.gnd.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wangbyul.gnd.api.GndApiApplication;
import com.wangbyul.gnd.core.config.MarketProviderProperties;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.repository.ApiResponseAuditRepository;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketProviderJobRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.service.MarketDataCollectionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 시장데이터 수집 기반(Mock/Stub fallback 포함) 통합 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@SpringBootTest(classes = GndApiApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:market-collection-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.security.admin-api-key=test-admin-key",
        "app.security.cors-allowlist=http://localhost:8081",
        "app.translation.ko.prefetch-enabled=false",
        "app.signal.auto-generate-enabled=false",
        "app.market.provider.active=mock",
        "app.market.provider.fallback-to-mock-on-failure=true",
        "app.market.collection.quote-asset-limit=5",
        "app.market.collection.bar-asset-limit=5",
        "app.market.collection.bar-points-per-asset=2",
        "app.market.collection.bar-timeframe=1m"
})
class MarketCollectionDiagnosticsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MarketDataCollectionService marketDataCollectionService;

    @Autowired
    private MarketProviderProperties marketProviderProperties;

    @Autowired
    private AssetUniverseRepository assetUniverseRepository;

    @Autowired
    private MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;

    @Autowired
    private MarketPriceBarRepository marketPriceBarRepository;

    @Autowired
    private MarketProviderJobRepository marketProviderJobRepository;

    @Autowired
    private ApiResponseAuditRepository apiResponseAuditRepository;

    @BeforeEach
    void setUp() {
        marketQuoteSnapshotRepository.deleteAll();
        marketPriceBarRepository.deleteAll();
        marketProviderJobRepository.deleteAll();
        apiResponseAuditRepository.deleteAll();
        assetUniverseRepository.deleteAll();
        seedAssets();
        marketProviderProperties.setFallbackToMockOnFailure(true);
        marketProviderProperties.setActive("mock");
    }

    @Test
    void mockProviderShouldCollectPersistAndExposeDiagnosticsApi() throws Exception {
        var quoteResult = marketDataCollectionService.collectQuotes("test");
        var barResult = marketDataCollectionService.collectBars("1m", "test");
        var healthResult = marketDataCollectionService.runProviderHealthCheck("test");

        assertThat(quoteResult.successCount()).isGreaterThan(0);
        assertThat(barResult.successCount()).isGreaterThan(0);
        assertThat(healthResult.status()).isEqualTo("SUCCESS");
        assertThat(marketQuoteSnapshotRepository.count()).isGreaterThan(0L);
        assertThat(marketPriceBarRepository.count()).isGreaterThan(0L);
        assertThat(marketProviderJobRepository.count()).isGreaterThanOrEqualTo(3L);
        assertThat(apiResponseAuditRepository.count()).isGreaterThanOrEqualTo(3L);

        mockMvc.perform(get("/api/admin/diagnostics/market-collection/summary")
                        .header("X-API-KEY", "test-admin-key")
                        .queryParam("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quote_snapshot_count").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.price_bar_count").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.provider_job_count").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.provider_health").exists())
                .andExpect(jsonPath("$.trace_id").exists());

        mockMvc.perform(get("/api/admin/diagnostics/market-collection/provider-audit")
                        .header("X-API-KEY", "test-admin-key")
                        .queryParam("provider", "mock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void providerFailureAndEmptyResponseShouldFallbackToMock() throws Exception {
        marketProviderProperties.setActive("toss");
        var tossResult = marketDataCollectionService.collectQuotes("test");
        assertThat(tossResult.fallbackUsed()).isTrue();
        assertThat(tossResult.providerName()).isEqualToIgnoringCase("mock");
        assertThat(tossResult.successCount()).isGreaterThan(0);

        marketProviderProperties.setActive("kiwoom");
        var kiwoomResult = marketDataCollectionService.collectBars("1m", "test");
        assertThat(kiwoomResult.fallbackUsed()).isTrue();
        assertThat(kiwoomResult.providerName()).isEqualToIgnoringCase("mock");
        assertThat(kiwoomResult.successCount()).isGreaterThan(0);

        mockMvc.perform(get("/api/admin/diagnostics/market-collection/provider-audit")
                        .header("X-API-KEY", "test-admin-key")
                        .queryParam("provider", "toss")
                        .queryParam("success", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failed_count").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/api/admin/diagnostics/market-collection/provider-audit")
                        .header("X-API-KEY", "test-admin-key")
                        .queryParam("provider", "kiwoom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    private void seedAssets() {
        assetUniverseRepository.saveAll(List.of(
                asset("KR_005930", "삼성전자", "KR", "SEMICONDUCTOR"),
                asset("US_NVDA", "NVIDIA", "US", "AI"),
                asset("US_TSLA", "Tesla", "US", "EV")));
    }

    private AssetUniverseEntity asset(String code, String name, String country, String theme) {
        AssetUniverseEntity entity = new AssetUniverseEntity();
        entity.setAssetCode(code);
        entity.setAssetName(name);
        entity.setCountry(country);
        entity.setTheme(theme);
        entity.setAssetType(AssetType.STOCK);
        entity.setActive(true);
        return entity;
    }
}

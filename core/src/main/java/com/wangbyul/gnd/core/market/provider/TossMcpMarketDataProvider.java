package com.wangbyul.gnd.core.market.provider;

import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.market.MarketDataProvider;
import com.wangbyul.gnd.core.market.dto.MarketPriceBarDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderFetchResult;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthDto;
import com.wangbyul.gnd.core.market.dto.MarketQuoteDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Toss MCP 기반 시장데이터 Provider 스텁.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 실제 API 연동 전 단계에서는 표준 실패 응답을 반환하여 fallback 경로를 검증한다.
 */
@Component
public class TossMcpMarketDataProvider implements MarketDataProvider {

    @Override
    public String providerId() {
        return "toss";
    }

    @Override
    public MarketProviderFetchResult<MarketQuoteDto> fetchQuotes(List<AssetUniverseEntity> assets) {
        return notImplemented("quotes");
    }

    @Override
    public MarketProviderFetchResult<MarketPriceBarDto> fetchBars(
            List<AssetUniverseEntity> assets,
            String timeframe,
            int barsPerAsset) {
        return notImplemented("bars");
    }

    @Override
    public MarketProviderFetchResult<MarketProviderHealthDto> healthCheck() {
        return notImplemented("health-check");
    }

    private <T> MarketProviderFetchResult<T> notImplemented(String apiName) {
        OffsetDateTime now = OffsetDateTime.now();
        return MarketProviderFetchResult.failure(
                providerId(),
                apiName,
                503,
                now,
                now,
                "{\"provider\":\"toss\",\"error\":\"not_implemented\"}",
                "PROVIDER_STUB_NOT_IMPLEMENTED",
                Map.of("provider_stub", true));
    }
}

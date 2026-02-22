package com.wangbyul.gnd.core.market.provider;

import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.market.MarketDataProvider;
import com.wangbyul.gnd.core.market.dto.MarketPriceBarDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderFetchResult;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthStatus;
import com.wangbyul.gnd.core.market.dto.MarketQuoteDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 키움 REST 기반 시장데이터 Provider 스텁.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 실제 인증/요청 규격 확정 전에는 빈응답 성공 스텁을 반환하여 empty-response fallback 경로를 검증한다.
 */
@Component
public class KiwoomRestMarketDataProvider implements MarketDataProvider {

    @Override
    public String providerId() {
        return "kiwoom";
    }

    @Override
    public MarketProviderFetchResult<MarketQuoteDto> fetchQuotes(List<AssetUniverseEntity> assets) {
        OffsetDateTime now = OffsetDateTime.now();
        return MarketProviderFetchResult.success(
                providerId(),
                "quotes",
                List.of(),
                200,
                now,
                now,
                "{\"provider\":\"kiwoom\",\"status\":\"empty_stub\"}",
                Map.of("provider_stub", true, "empty_response", true));
    }

    @Override
    public MarketProviderFetchResult<MarketPriceBarDto> fetchBars(
            List<AssetUniverseEntity> assets,
            String timeframe,
            int barsPerAsset) {
        OffsetDateTime now = OffsetDateTime.now();
        return MarketProviderFetchResult.success(
                providerId(),
                "bars",
                List.of(),
                200,
                now,
                now,
                "{\"provider\":\"kiwoom\",\"status\":\"empty_stub\"}",
                Map.of("provider_stub", true, "empty_response", true));
    }

    @Override
    public MarketProviderFetchResult<MarketProviderHealthDto> healthCheck() {
        OffsetDateTime now = OffsetDateTime.now();
        MarketProviderHealthDto health = new MarketProviderHealthDto(
                providerId(),
                MarketProviderHealthStatus.WARN,
                "kiwoom stub returns empty payload",
                0L,
                now);
        return MarketProviderFetchResult.success(
                providerId(),
                "health-check",
                List.of(health),
                200,
                now,
                now,
                "{\"provider\":\"kiwoom\",\"status\":\"WARN\",\"reason\":\"empty_stub\"}",
                Map.of("provider_stub", true, "empty_response", true));
    }

    private <T> MarketProviderFetchResult<T> notImplemented(String apiName) {
        OffsetDateTime now = OffsetDateTime.now();
        return MarketProviderFetchResult.failure(
                providerId(),
                apiName,
                503,
                now,
                now,
                "{\"provider\":\"kiwoom\",\"error\":\"not_implemented\"}",
                "PROVIDER_STUB_NOT_IMPLEMENTED",
                Map.of("provider_stub", true));
    }
}

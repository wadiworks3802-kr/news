package com.wangbyul.gnd.core.market;

import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.market.dto.MarketPriceBarDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderFetchResult;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthDto;
import com.wangbyul.gnd.core.market.dto.MarketQuoteDto;
import java.util.List;

/**
 * 시장 데이터 공급자 추상화 인터페이스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 공급자별 응답 포맷 차이는 구현체 내부에서 표준 DTO로 정규화하여 반환한다.
 */
public interface MarketDataProvider {

    /**
     * 공급자 식별자 (mock/toss/kiwoom).
     */
    String providerId();

    /**
     * 시세 스냅샷(quote) 표준 DTO 수집.
     */
    MarketProviderFetchResult<MarketQuoteDto> fetchQuotes(List<AssetUniverseEntity> assets);

    /**
     * OHLCV 바 데이터 표준 DTO 수집.
     */
    MarketProviderFetchResult<MarketPriceBarDto> fetchBars(List<AssetUniverseEntity> assets, String timeframe, int barsPerAsset);

    /**
     * 공급자 헬스체크 표준 DTO 수집.
     */
    MarketProviderFetchResult<MarketProviderHealthDto> healthCheck();
}

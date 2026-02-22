package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OHLCV 바 데이터 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface MarketPriceBarRepository extends JpaRepository<MarketPriceBarEntity, Long> {

    List<MarketPriceBarEntity> findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(String assetCode, String timeframe);

    Optional<MarketPriceBarEntity> findTop1ByAssetCodeAndTimeframeOrderByBarTimeDesc(String assetCode, String timeframe);

    List<MarketPriceBarEntity> findTop2ByAssetCodeAndTimeframeOrderByBarTimeDesc(String assetCode, String timeframe);

    long countByCreatedAtAfter(OffsetDateTime since);

    long countByProviderNameAndCreatedAtAfter(String providerName, OffsetDateTime since);
}

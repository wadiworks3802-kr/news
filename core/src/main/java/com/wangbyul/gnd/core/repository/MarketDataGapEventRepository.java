package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.MarketDataGapEventEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 시장데이터 갭 이벤트 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface MarketDataGapEventRepository extends JpaRepository<MarketDataGapEventEntity, Long> {

    List<MarketDataGapEventEntity> findTop300ByResolvedFalseOrderByEventTimeUtcDesc();

    List<MarketDataGapEventEntity> findTop200ByOrderByEventTimeUtcDesc();

    List<MarketDataGapEventEntity> findTop200ByAssetCodeOrderByEventTimeUtcDesc(String assetCode);

    List<MarketDataGapEventEntity> findTop200ByTraceIdOrderByEventTimeUtcDesc(String traceId);

    List<MarketDataGapEventEntity> findTop200ByProviderOrderByEventTimeUtcDesc(String provider);

    long countByResolvedFalse();

    long deleteByResolvedTrueAndResolvedAtBefore(OffsetDateTime cutoff);
}

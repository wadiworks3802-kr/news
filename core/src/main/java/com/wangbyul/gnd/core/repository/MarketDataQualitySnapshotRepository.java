package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.MarketDataQualitySnapshotEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 시장데이터 품질 스냅샷 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface MarketDataQualitySnapshotRepository extends JpaRepository<MarketDataQualitySnapshotEntity, Long> {

    List<MarketDataQualitySnapshotEntity> findTop200ByOrderBySnapshotTimeUtcDesc();

    List<MarketDataQualitySnapshotEntity> findTop200ByProviderAndCountryOrderBySnapshotTimeUtcDesc(String provider, String country);

    List<MarketDataQualitySnapshotEntity> findTop200ByProviderOrderBySnapshotTimeUtcDesc(String provider);

    List<MarketDataQualitySnapshotEntity> findTop200ByTraceIdOrderBySnapshotTimeUtcDesc(String traceId);

    List<MarketDataQualitySnapshotEntity> findBySnapshotTimeUtcAfterOrderBySnapshotTimeUtcDesc(OffsetDateTime since);
}

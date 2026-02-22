package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 시세 스냅샷 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface MarketQuoteSnapshotRepository extends JpaRepository<MarketQuoteSnapshotEntity, Long> {

    Optional<MarketQuoteSnapshotEntity> findTop1ByAssetCodeOrderBySnapshotUtcDesc(String assetCode);

    List<MarketQuoteSnapshotEntity> findTop2ByAssetCodeOrderBySnapshotUtcDesc(String assetCode);

    List<MarketQuoteSnapshotEntity> findTop120ByAssetCodeOrderBySnapshotUtcDesc(String assetCode);

    long countByCreatedAtAfter(OffsetDateTime since);

    long countByProviderNameAndCreatedAtAfter(String providerName, OffsetDateTime since);

    List<MarketQuoteSnapshotEntity> findTop500ByCreatedAtAfterOrderByCreatedAtDesc(OffsetDateTime since);

    List<MarketQuoteSnapshotEntity> findTop500ByProviderNameAndCreatedAtAfterOrderByCreatedAtDesc(
            String providerName,
            OffsetDateTime since);
}

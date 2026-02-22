package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.NewsAssetLinkEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 뉴스-자산 매핑 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface NewsAssetLinkRepository extends JpaRepository<NewsAssetLinkEntity, Long> {

    List<NewsAssetLinkEntity> findTop200ByAssetCodeOrderByCreatedAtDesc(String assetCode);

    List<NewsAssetLinkEntity> findTop200ByNewsIdOrderByConfidenceDesc(String newsId);

    List<NewsAssetLinkEntity> findTop5000ByCreatedAtAfterOrderByCreatedAtDesc(OffsetDateTime since);

    long countByAssetCodeAndCreatedAtAfter(String assetCode, OffsetDateTime since);
}

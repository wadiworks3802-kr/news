package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.CategoryType;
import com.wangbyul.gnd.core.domain.NewsEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
/**
 * NewsRepository 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

public interface NewsRepository extends JpaRepository<NewsEntity, String> {

    Page<NewsEntity> findByCountryAndCategoryAndPubUtcBetween(
            String country,
            CategoryType category,
            OffsetDateTime from,
            OffsetDateTime to,
            Pageable pageable);

    Optional<NewsEntity> findByUrlNorm(String urlNorm);

    Optional<NewsEntity> findTop1ByContentHash(String contentHash);

    List<NewsEntity> findTop200ByCountryOrderByPubUtcDesc(String country);

    List<NewsEntity> findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(String country, OffsetDateTime since);

    List<NewsEntity> findTop500ByCountryAndPublishedAtUtcAfterOrderByPublishedAtUtcDesc(String country, OffsetDateTime since);

    List<NewsEntity> findTop100BySummaryKoIsNullOrderByPubUtcDesc();

    List<NewsEntity> findTop300ByOrderByPubUtcDesc();

    List<NewsEntity> findTop500ByOrderByCreatedAtAsc();

    long deleteByCreatedAtBefore(OffsetDateTime cutoff);
}

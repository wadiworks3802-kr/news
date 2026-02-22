package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.JobStatus;
import com.wangbyul.gnd.core.domain.MarketProviderJobEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 시장 데이터 잡 이력 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface MarketProviderJobRepository extends JpaRepository<MarketProviderJobEntity, Long> {

    List<MarketProviderJobEntity> findTop100ByProviderNameAndStatusOrderByScheduledAtDesc(String providerName, JobStatus status);

    List<MarketProviderJobEntity> findTop100ByScheduledAtAfterOrderByScheduledAtDesc(OffsetDateTime since);

    List<MarketProviderJobEntity> findTop100ByProviderNameAndScheduledAtAfterOrderByScheduledAtDesc(
            String providerName,
            OffsetDateTime since);

    List<MarketProviderJobEntity> findTop300ByScheduledAtAfterOrderByScheduledAtDesc(OffsetDateTime since);

    List<MarketProviderJobEntity> findTop300ByProviderNameAndScheduledAtAfterOrderByScheduledAtDesc(
            String providerName,
            OffsetDateTime since);

    List<MarketProviderJobEntity> findTop100ByProviderNameOrderByScheduledAtDesc(String providerName);
}

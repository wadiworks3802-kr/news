package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.StrategyRunEntity;
import com.wangbyul.gnd.core.domain.StrategyRunType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 전략 실행 이력 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface StrategyRunRepository extends JpaRepository<StrategyRunEntity, Long> {

    List<StrategyRunEntity> findTop100ByRunTypeOrderByStartedAtDesc(StrategyRunType runType);

    List<StrategyRunEntity> findTop100ByTraceIdOrderByStartedAtDesc(String traceId);

    Page<StrategyRunEntity> findByRunTypeAndScopeCountryOrderByStartedAtDesc(
            StrategyRunType runType,
            String scopeCountry,
            Pageable pageable);
}

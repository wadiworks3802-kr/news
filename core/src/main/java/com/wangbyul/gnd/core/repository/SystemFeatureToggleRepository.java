package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.FeatureScopeType;
import com.wangbyul.gnd.core.domain.SystemFeatureToggleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 기능 토글 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface SystemFeatureToggleRepository extends JpaRepository<SystemFeatureToggleEntity, Long> {

    Optional<SystemFeatureToggleEntity> findByFeatureKeyAndScopeTypeAndScopeValue(
            String featureKey,
            FeatureScopeType scopeType,
            String scopeValue);

    Optional<SystemFeatureToggleEntity> findByFeatureKeyAndScopeTypeAndScopeValueIsNull(
            String featureKey,
            FeatureScopeType scopeType);

    List<SystemFeatureToggleEntity> findByFeatureKeyOrderByUpdatedAtDesc(String featureKey, Pageable pageable);

    List<SystemFeatureToggleEntity> findByTraceIdOrderByUpdatedAtDesc(String traceId, Pageable pageable);

    List<SystemFeatureToggleEntity> findByEnabledOrderByUpdatedAtDesc(Boolean enabled, Pageable pageable);
}

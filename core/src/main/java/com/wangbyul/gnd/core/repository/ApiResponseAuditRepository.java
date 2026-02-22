package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.ApiResponseAuditEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 외부 API 응답 감사 로그 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface ApiResponseAuditRepository extends JpaRepository<ApiResponseAuditEntity, Long> {

    List<ApiResponseAuditEntity> findTop300ByOrderByRequestTimeUtcDesc();

    List<ApiResponseAuditEntity> findTop300ByProviderOrderByRequestTimeUtcDesc(String provider);

    List<ApiResponseAuditEntity> findTop300ByTraceIdOrderByRequestTimeUtcDesc(String traceId);

    long deleteByRequestTimeUtcBefore(OffsetDateTime cutoff);
}

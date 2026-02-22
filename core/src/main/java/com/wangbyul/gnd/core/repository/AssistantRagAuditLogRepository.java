package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.AssistantRagAuditLogEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 경량 RAG/LLM 보조 분석 감사 로그 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface AssistantRagAuditLogRepository extends JpaRepository<AssistantRagAuditLogEntity, Long> {

    List<AssistantRagAuditLogEntity> findTop200ByTraceIdOrderByCreatedAtDesc(String traceId);

    List<AssistantRagAuditLogEntity> findTop100BySignalIdOrderByCreatedAtDesc(String signalId);

    List<AssistantRagAuditLogEntity> findTop100ByAssetCodeOrderByCreatedAtDesc(String assetCode);

    List<AssistantRagAuditLogEntity> findTop200ByRequestScopeOrderByCreatedAtDesc(String requestScope);

    long countByCreatedAtAfter(OffsetDateTime since);

    long countByRequestScopeAndCreatedAtAfter(String requestScope, OffsetDateTime since);

    long countBySuccessFalseAndCreatedAtAfter(OffsetDateTime since);
}

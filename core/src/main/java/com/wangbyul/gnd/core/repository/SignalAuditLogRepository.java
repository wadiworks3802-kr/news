package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.SignalAuditLogEntity;
import com.wangbyul.gnd.core.domain.SignalAuditEngineType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 시그널 감사 로그 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface SignalAuditLogRepository extends JpaRepository<SignalAuditLogEntity, Long> {

    List<SignalAuditLogEntity> findTop500ByOrderByAuditTimeUtcDesc();

    List<SignalAuditLogEntity> findTop500ByEngineTypeOrderByAuditTimeUtcDesc(SignalAuditEngineType engineType);

    List<SignalAuditLogEntity> findTop200ByAssetCodeOrderByAuditTimeUtcDesc(String assetCode);

    List<SignalAuditLogEntity> findTop200BySignalIdOrderByAuditTimeUtcDesc(String signalId);

    List<SignalAuditLogEntity> findTop200ByTraceIdOrderByAuditTimeUtcDesc(String traceId);
}

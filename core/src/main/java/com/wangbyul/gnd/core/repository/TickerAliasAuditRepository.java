package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.TickerAliasAuditEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 티커 별칭 검증 로그 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface TickerAliasAuditRepository extends JpaRepository<TickerAliasAuditEntity, Long> {

    List<TickerAliasAuditEntity> findTop300ByOrderByAuditTimeUtcDesc();

    List<TickerAliasAuditEntity> findTop300ByAssetCodeOrderByAuditTimeUtcDesc(String assetCode);

    List<TickerAliasAuditEntity> findByAuditTimeUtcAfterOrderByAuditTimeUtcDesc(OffsetDateTime since);
}

package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.OrderApprovalEventLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 승인 파이프라인 이벤트 감사 로그 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface OrderApprovalEventLogRepository extends JpaRepository<OrderApprovalEventLogEntity, Long> {

    List<OrderApprovalEventLogEntity> findTop300ByWorkflowIdOrderByCreatedAtDesc(Long workflowId);

    List<OrderApprovalEventLogEntity> findTop300ByTraceIdOrderByCreatedAtDesc(String traceId);
}

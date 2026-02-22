package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.OrderApprovalWorkflowEntity;
import com.wangbyul.gnd.core.domain.OrderApprovalWorkflowStageType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 승인 파이프라인 워크플로 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface OrderApprovalWorkflowRepository extends JpaRepository<OrderApprovalWorkflowEntity, Long> {

    List<OrderApprovalWorkflowEntity> findByCurrentStageOrderByUpdatedAtDesc(
            OrderApprovalWorkflowStageType currentStage,
            Pageable pageable);

    List<OrderApprovalWorkflowEntity> findByCountryOrderByUpdatedAtDesc(String country, Pageable pageable);

    List<OrderApprovalWorkflowEntity> findByCountryAndCurrentStageOrderByUpdatedAtDesc(
            String country,
            OrderApprovalWorkflowStageType currentStage,
            Pageable pageable);

    List<OrderApprovalWorkflowEntity> findByOrderByUpdatedAtDesc(Pageable pageable);

    List<OrderApprovalWorkflowEntity> findTop100ByTraceIdOrderByUpdatedAtDesc(String traceId);

    Optional<OrderApprovalWorkflowEntity> findTop1BySignalIdOrderByCreatedAtDesc(String signalId);
}

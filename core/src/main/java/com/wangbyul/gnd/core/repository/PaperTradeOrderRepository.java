package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.OrderStatusType;
import com.wangbyul.gnd.core.domain.PaperTradeOrderEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 모의매매 주문 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface PaperTradeOrderRepository extends JpaRepository<PaperTradeOrderEntity, Long> {

    List<PaperTradeOrderEntity> findTop300ByStatusOrderByCreatedAtDesc(OrderStatusType status);

    List<PaperTradeOrderEntity> findTop300ByCreatedAtAfterOrderByCreatedAtDesc(OffsetDateTime since);
}


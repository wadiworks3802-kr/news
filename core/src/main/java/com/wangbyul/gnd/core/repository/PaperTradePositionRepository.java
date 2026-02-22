package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.PaperTradePositionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 모의매매 포지션 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface PaperTradePositionRepository extends JpaRepository<PaperTradePositionEntity, Long> {

    Optional<PaperTradePositionEntity> findByAssetCode(String assetCode);

    List<PaperTradePositionEntity> findTop300ByBuyLockTrueOrderByLockUntilAsc();
}


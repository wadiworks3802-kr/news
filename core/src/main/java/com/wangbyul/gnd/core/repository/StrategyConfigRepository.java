package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.StrategyConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 전략 설정 저장소 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface StrategyConfigRepository extends JpaRepository<StrategyConfigEntity, String> {
}


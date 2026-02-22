package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.TickerAliasDictionaryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 티커 별칭 사전 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface TickerAliasDictionaryRepository extends JpaRepository<TickerAliasDictionaryEntity, Long> {

    List<TickerAliasDictionaryEntity> findByActiveTrueOrderByAliasValueAsc();

    List<TickerAliasDictionaryEntity> findByAssetCodeAndActiveTrueOrderByAliasTypeAsc(String assetCode);

    List<TickerAliasDictionaryEntity> findByAliasValueIgnoreCaseAndActiveTrue(String aliasValue);
}

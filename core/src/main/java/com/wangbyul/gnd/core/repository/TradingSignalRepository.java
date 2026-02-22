package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 통합 시그널 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface TradingSignalRepository extends JpaRepository<TradingSignalEntity, String> {

    List<TradingSignalEntity> findByCountryAndActionInOrderByGeneratedAtDesc(
            String country,
            List<SignalActionType> actions,
            Pageable pageable);

    List<TradingSignalEntity> findByCountryAndSignalWindowAndActionInOrderByGeneratedAtDesc(
            String country,
            String signalWindow,
            List<SignalActionType> actions,
            Pageable pageable);

    List<TradingSignalEntity> findByCountryAndThemeAndActionInOrderByGeneratedAtDesc(
            String country,
            String theme,
            List<SignalActionType> actions,
            Pageable pageable);

    List<TradingSignalEntity> findByCountryAndThemeAndSignalWindowAndActionInOrderByGeneratedAtDesc(
            String country,
            String theme,
            String signalWindow,
            List<SignalActionType> actions,
            Pageable pageable);

    List<TradingSignalEntity> findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
            String country,
            OffsetDateTime since,
            Pageable pageable);

    List<TradingSignalEntity> findByCountryAndSignalWindowAndGeneratedAtAfterOrderByGeneratedAtDesc(
            String country,
            String signalWindow,
            OffsetDateTime since,
            Pageable pageable);

    List<TradingSignalEntity> findByGeneratedAtAfterOrderByGeneratedAtDesc(
            OffsetDateTime since,
            Pageable pageable);

    List<TradingSignalEntity> findTop200ByAssetCodeOrderByGeneratedAtDesc(String assetCode);

    Optional<TradingSignalEntity> findTop1ByAssetCodeOrderByGeneratedAtDesc(String assetCode);

    Optional<TradingSignalEntity> findTop1ByAssetCodeAndSignalWindowOrderByGeneratedAtDesc(String assetCode, String signalWindow);

    long countByAssetCodeAndGeneratedAtAfter(String assetCode, OffsetDateTime since);
}

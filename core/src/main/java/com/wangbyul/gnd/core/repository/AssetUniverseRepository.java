package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 자산 마스터 조회 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface AssetUniverseRepository extends JpaRepository<AssetUniverseEntity, String> {

    List<AssetUniverseEntity> findByActiveTrueOrderByUpdatedAtDesc();

    List<AssetUniverseEntity> findByCountryAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(String country);

    List<AssetUniverseEntity> findByCountryAndThemeAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(
            String country,
            String theme);

    List<AssetUniverseEntity> findByCountryAndThemeCodeAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(
            String country,
            String themeCode);

    List<AssetUniverseEntity> findByCountryOrderBySelectionScoreDesc(String country);

    List<AssetUniverseEntity> findByCountryAndThemeOrderBySelectionScoreDesc(String country, String theme);

    List<AssetUniverseEntity> findTop200ByCountryAndActiveTrueOrderByUpdatedAtDesc(String country);

    List<AssetUniverseEntity> findTop200ByThemeAndActiveTrueOrderByUpdatedAtDesc(String theme);

    List<AssetUniverseEntity> findTop200ByCountryAndThemeAndActiveTrueOrderByUpdatedAtDesc(String country, String theme);

    List<AssetUniverseEntity> findTop200ByCountryAndThemeCodeAndActiveTrueOrderByUpdatedAtDesc(String country, String themeCode);
}

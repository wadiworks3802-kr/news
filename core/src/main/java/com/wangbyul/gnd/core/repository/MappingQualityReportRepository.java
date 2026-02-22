package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.MappingQualityReportEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 뉴스-자산 매핑 품질 리포트 리포지토리.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface MappingQualityReportRepository extends JpaRepository<MappingQualityReportEntity, Long> {

    Optional<MappingQualityReportEntity> findTop1ByCountryAndThemeOrderByReportTimeUtcDesc(String country, String theme);

    List<MappingQualityReportEntity> findTop200ByOrderByReportTimeUtcDesc();
}

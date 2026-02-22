package com.wangbyul.gnd.core.repository;

import com.wangbyul.gnd.core.domain.FetchJobEntity;
import com.wangbyul.gnd.core.domain.JobStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
/**
 * FetchJobRepository 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

public interface FetchJobRepository extends JpaRepository<FetchJobEntity, Long> {

    List<FetchJobEntity> findTop100ByStatusOrderByScheduledAtAsc(JobStatus status);
}

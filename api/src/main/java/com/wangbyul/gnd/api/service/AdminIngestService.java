package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.dto.AdminIngestRunRequest;
import com.wangbyul.gnd.api.dto.AdminIngestRunResultDto;
/**
 * AdminIngestService 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

public interface AdminIngestService {

    AdminIngestRunResultDto runNow(AdminIngestRunRequest request);
}

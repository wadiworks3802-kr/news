package com.wangbyul.gnd.core.service;

import com.wangbyul.gnd.core.domain.SourceEntity;
/**
 * FetchService 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

public interface FetchService {

    void fetch(SourceEntity source);
}

package com.wangbyul.gnd.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
/**
 * NewsCacheKeyFactoryTest 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

class NewsCacheKeyFactoryTest {

    @Test
    void build_shouldIncludeAllDimensions() {
        String key = NewsCacheKeyFactory.build("KR", "ECO", "latest", "24h", 1, 4, "ko", "inflation");
        assertThat(key).startsWith("news:list:KR:ECO:latest:24h:1:4:ko:");
    }
}

package com.wangbyul.gnd.collector.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
/**
 * UrlNormalizerTest 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer();

    @Test
    void normalize_shouldLowerCaseHostAndDropDefaultPort() {
        String out = normalizer.normalize("HTTPS://EXAMPLE.COM:443/news?id=1");
        assertThat(out).isEqualTo("https://example.com/news?id=1");
    }
}

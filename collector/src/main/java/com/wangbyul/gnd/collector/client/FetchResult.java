package com.wangbyul.gnd.collector.client;

import java.time.OffsetDateTime;
/**
 * FetchResult 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

public record FetchResult(
        int statusCode,
        String body,
        String etag,
        String lastModified,
        OffsetDateTime fetchedAt) {

    public boolean notModified() {
        return statusCode == 304;
    }
}

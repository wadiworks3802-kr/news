package com.wangbyul.gnd.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 시장 데이터 Provider 라우팅/수집 공통 설정.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Component
@ConfigurationProperties(prefix = "app.market.provider")
public class MarketProviderProperties {

    /**
     * 활성 Provider ID (mock|toss|kiwoom).
     */
    private String active = "mock";

    /**
     * 활성 Provider 실패/빈응답 시 mock provider fallback 허용 여부.
     */
    private boolean fallbackToMockOnFailure = true;

    /**
     * 운영 환경에서 mock provider 사용 허용 여부.
     *
     * false 인 경우 mock 활성화/자동 fallback 모두 차단한다.
     */
    private boolean allowMock = false;

    /**
     * Mock Provider 기본 바 생성 개수(자산당).
     */
    private int mockBarsPerAsset = 3;

    /**
     * Mock Provider 기본 지연(ms) 시뮬레이션.
     */
    private int mockLatencyMs = 25;

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public boolean isFallbackToMockOnFailure() {
        return fallbackToMockOnFailure;
    }

    public void setFallbackToMockOnFailure(boolean fallbackToMockOnFailure) {
        this.fallbackToMockOnFailure = fallbackToMockOnFailure;
    }

    public boolean isAllowMock() {
        return allowMock;
    }

    public void setAllowMock(boolean allowMock) {
        this.allowMock = allowMock;
    }

    public int getMockBarsPerAsset() {
        return mockBarsPerAsset;
    }

    public void setMockBarsPerAsset(int mockBarsPerAsset) {
        this.mockBarsPerAsset = mockBarsPerAsset;
    }

    public int getMockLatencyMs() {
        return mockLatencyMs;
    }

    public void setMockLatencyMs(int mockLatencyMs) {
        this.mockLatencyMs = mockLatencyMs;
    }
}

package com.wangbyul.gnd.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 키움 REST provider 설정.
 */
@Component
@ConfigurationProperties(prefix = "app.market.provider.kiwoom")
public class KiwoomProviderProperties {

    /**
     * 키움 API base URL.
     */
    private String baseUrl = "https://api.kiwoom.com";

    /**
     * OAuth2 토큰 발급 경로.
     */
    private String tokenPath = "/oauth2/token";

    /**
     * 현재가/종목정보 조회 경로.
     */
    private String quotePath = "/api/dostk/stkinfo";

    /**
     * 분봉/차트 조회 경로.
     */
    private String chartPath = "/api/dostk/chart";

    /**
     * 현재가 조회 api-id.
     */
    private String quoteApiId = "ka10099";

    /**
     * 분봉 조회 api-id.
     */
    private String chartApiId = "ka10080";

    /**
     * 키움 appkey (직접 주입용).
     */
    private String appKey;

    /**
     * 키움 secretkey (직접 주입용).
     */
    private String secretKey;

    /**
     * appkey 파일 경로.
     */
    private String appKeyFile = "appkey.txt";

    /**
     * secretkey 파일 경로.
     */
    private String secretKeyFile = "secretkey.txt";

    /**
     * 연결 타임아웃(ms).
     */
    private int connectTimeoutMillis = 3000;

    /**
     * 요청 타임아웃(ms).
     */
    private int requestTimeoutMillis = 8000;

    /**
     * 차트 요청 분 단위 기본값.
     */
    private int chartDefaultIntervalMinutes = 1;

    /**
     * 차트 interval 필드명(base_tp/tic_scope).
     */
    private String chartIntervalField = "tic_scope";

    /**
     * 수정주가 반영 구분.
     */
    private String chartAdjustedPriceType = "1";

    /**
     * 헬스체크용 종목코드(키움 코드).
     */
    private String healthCheckStockCode = "005930";

    /**
     * 시장 구분값(예: KR 주식 0).
     */
    private String marketType = "0";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTokenPath() {
        return tokenPath;
    }

    public void setTokenPath(String tokenPath) {
        this.tokenPath = tokenPath;
    }

    public String getQuotePath() {
        return quotePath;
    }

    public void setQuotePath(String quotePath) {
        this.quotePath = quotePath;
    }

    public String getChartPath() {
        return chartPath;
    }

    public void setChartPath(String chartPath) {
        this.chartPath = chartPath;
    }

    public String getQuoteApiId() {
        return quoteApiId;
    }

    public void setQuoteApiId(String quoteApiId) {
        this.quoteApiId = quoteApiId;
    }

    public String getChartApiId() {
        return chartApiId;
    }

    public void setChartApiId(String chartApiId) {
        this.chartApiId = chartApiId;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getAppKeyFile() {
        return appKeyFile;
    }

    public void setAppKeyFile(String appKeyFile) {
        this.appKeyFile = appKeyFile;
    }

    public String getSecretKeyFile() {
        return secretKeyFile;
    }

    public void setSecretKeyFile(String secretKeyFile) {
        this.secretKeyFile = secretKeyFile;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public int getChartDefaultIntervalMinutes() {
        return chartDefaultIntervalMinutes;
    }

    public void setChartDefaultIntervalMinutes(int chartDefaultIntervalMinutes) {
        this.chartDefaultIntervalMinutes = chartDefaultIntervalMinutes;
    }

    public String getChartIntervalField() {
        return chartIntervalField;
    }

    public void setChartIntervalField(String chartIntervalField) {
        this.chartIntervalField = chartIntervalField;
    }

    public String getChartAdjustedPriceType() {
        return chartAdjustedPriceType;
    }

    public void setChartAdjustedPriceType(String chartAdjustedPriceType) {
        this.chartAdjustedPriceType = chartAdjustedPriceType;
    }

    public String getHealthCheckStockCode() {
        return healthCheckStockCode;
    }

    public void setHealthCheckStockCode(String healthCheckStockCode) {
        this.healthCheckStockCode = healthCheckStockCode;
    }

    public String getMarketType() {
        return marketType;
    }

    public void setMarketType(String marketType) {
        this.marketType = marketType;
    }
}

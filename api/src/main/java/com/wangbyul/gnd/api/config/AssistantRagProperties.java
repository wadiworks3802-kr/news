package com.wangbyul.gnd.api.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 경량 RAG/LLM 보조 계층 설정.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.assistant-rag")
public class AssistantRagProperties {

    /**
     * 전역 기본 활성화 플래그(추가로 feature toggle RAG_ASSISTANT가 적용됨).
     */
    private boolean enabled = true;

    /**
     * 종목 상세 API 보조 설명 활성화.
     */
    private boolean signalDetailEnabled = true;

    /**
     * 관리자 trace 상세 요약 활성화.
     */
    private boolean traceDetailEnabled = true;

    /**
     * 경량 모델 모드(template/mock 등).
     */
    private String modelMode = "template";

    /**
     * 보조 분석 최대 허용 지연(ms). 초과 시 timeout fallback.
     */
    private long timeoutMillis = 450L;

    /**
     * 지연 경고 임계(ms). 초과 누적 비율이 높으면 회로 차단을 연다.
     */
    private long latencyWarningMillis = 250L;

    /**
     * 회로 차단 에러율 임계치(0~1).
     */
    private double circuitErrorRateThreshold = 0.50d;

    /**
     * 회로 차단 지연율 임계치(0~1).
     */
    private double circuitLatencyRateThreshold = 0.60d;

    /**
     * 회로 차단 평가 윈도우 크기.
     */
    private int circuitWindowSize = 20;

    /**
     * 연속 실패 임계. 초과 시 즉시 회로 차단.
     */
    private int circuitConsecutiveFailureThreshold = 3;

    /**
     * 회로 차단 유지 시간(초).
     */
    private int circuitOpenSeconds = 60;

    /**
     * RAG 컨텍스트에 포함할 최근 뉴스 최대 건수.
     */
    private int maxRecentNews = 8;

    /**
     * RAG 컨텍스트 뉴스 링크 조회 범위(시간).
     */
    private int newsLookbackHours = 48;

    /**
     * RAG 컨텍스트에 포함할 바 데이터 최대 건수.
     */
    private int maxBars = 30;

    /**
     * RAG 컨텍스트 바 조회 timeframe.
     */
    private String barTimeframe = "1m";

    /**
     * RAG 컨텍스트에 포함할 시그널 감사로그 최대 건수.
     */
    private int maxSignalAudits = 5;

    /**
     * 데이터 부족 상태 코드 목록. 해당 상태에서는 보류/주의 문구를 강제한다.
     */
    private List<String> dataGapStates = new ArrayList<>(List.of("NO_MATCHED_NEWS", "INSUFFICIENT_DATA"));

    /**
     * 출력 스키마 버전(감사로그 기록용).
     */
    private String promptVersion = "RAG_ASSISTANT_PROMPT_V1";

    /**
     * 경량 템플릿 모델 버전 표기.
     */
    private String modelVersion = "rag-lite-template-v1";
}

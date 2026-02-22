package com.wangbyul.gnd.api.service.assistant;

import java.util.List;
import java.util.Map;

/**
 * 경량 RAG/LLM 보조 모델 클라이언트 계약.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public interface AssistantRagModelClient {

    SignalDetailModelOutput generateSignalDetailSummary(AssistantRagContextBuilderService.SignalDetailRagContext context);

    TraceDetailModelOutput generateTraceDetailSummary(AssistantRagContextBuilderService.TraceDetailRagContext context);

    /**
     * 종목 상세 보조 분석 구조화 출력.
     */
    record SignalDetailModelOutput(
            String summary,
            List<String> evidenceBullets,
            List<String> cautionBullets,
            List<String> missingDataBullets,
            List<String> changeTriggers,
            String explainText,
            String ruleEngineAction,
            Boolean ruleEngineActionLocked,
            Map<String, Object> meta) {
    }

    /**
     * 관리자 trace 상세 보조 요약 구조화 출력.
     */
    record TraceDetailModelOutput(
            String summary,
            List<String> highlights,
            List<String> cautions,
            String explainText,
            Map<String, Object> meta) {
    }
}

package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 종목 상세/추후 확장 UI에서 사용하는 경량 RAG 보조 설명 DTO.
 *
 * 규칙 엔진 판단(action 등)을 덮어쓰지 않고 설명/주의/근거 요약 보조로만 사용한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class AssistantRagInsightDto {

    private Boolean enabled;

    private Boolean applied;

    @JsonProperty("fallback_applied")
    private Boolean fallbackApplied;

    @JsonProperty("circuit_open")
    private Boolean circuitOpen;

    private String source;

    private String summary;

    @JsonProperty("evidence_bullets")
    private List<String> evidenceBullets;

    @JsonProperty("caution_bullets")
    private List<String> cautionBullets;

    @JsonProperty("missing_data_bullets")
    private List<String> missingDataBullets;

    @JsonProperty("change_triggers")
    private List<String> changeTriggers;

    @JsonProperty("explain_text")
    private String explainText;

    @JsonProperty("rule_engine_action")
    private String ruleEngineAction;

    @JsonProperty("rule_engine_action_locked")
    private Boolean ruleEngineActionLocked;

    @JsonProperty("prompt_version")
    private String promptVersion;

    @JsonProperty("model_version")
    private String modelVersion;

    @JsonProperty("latency_ms_total")
    private Long latencyMsTotal;

    @JsonProperty("error_code")
    private String errorCode;
}

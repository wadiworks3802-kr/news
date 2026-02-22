package com.wangbyul.gnd.core.policy;

import lombok.Builder;
import lombok.Getter;
/**
 * PolicyDecision 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Builder
public class PolicyDecision {

    private boolean allowFetch;
    private boolean allowStoreRaw;
    private boolean allowStoreDerived;
    private boolean robotsAllowed;
    private boolean ttlValid;
    private String reason;
}

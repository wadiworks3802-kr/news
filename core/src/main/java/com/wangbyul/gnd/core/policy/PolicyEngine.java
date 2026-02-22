package com.wangbyul.gnd.core.policy;

import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.domain.SourceGrade;
import org.springframework.stereotype.Component;
/**
 * PolicyEngine 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Component
public class PolicyEngine {

    public PolicyDecision evaluate(SourceEntity source, NewsEntity news) {
        boolean robotsAllowed = Boolean.TRUE.equals(news.getRobots());
        boolean ttlValid = news.getTtl() != null && news.getTtl() > 0;
        boolean rawAllowed = Boolean.TRUE.equals(source.getAllowStoreRaw());

        // P1~P3 defaults deny raw storage unless explicit override.
        if (source.getSourceGrade() != SourceGrade.P0 && source.getAllowStoreRaw() == null) {
            rawAllowed = false;
        }

        boolean allowFetch = Boolean.TRUE.equals(source.getAllowFetch()) && robotsAllowed;
        boolean allowDerived = Boolean.TRUE.equals(source.getAllowStoreDerived()) && ttlValid;

        String reason = "OK";
        if (!robotsAllowed) {
            reason = "robots=false";
        } else if (!ttlValid) {
            reason = "ttl_expired";
        }

        return PolicyDecision.builder()
                .allowFetch(allowFetch)
                .allowStoreRaw(rawAllowed)
                .allowStoreDerived(allowDerived)
                .robotsAllowed(robotsAllowed)
                .ttlValid(ttlValid)
                .reason(reason)
                .build();
    }
}

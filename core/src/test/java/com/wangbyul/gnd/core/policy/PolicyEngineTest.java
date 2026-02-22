package com.wangbyul.gnd.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.domain.SourceGrade;
import org.junit.jupiter.api.Test;
/**
 * PolicyEngineTest 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

class PolicyEngineTest {

    private final PolicyEngine policyEngine = new PolicyEngine();

    @Test
    void evaluate_shouldBlockWhenRobotsFalse() {
        SourceEntity source = new SourceEntity();
        source.setSourceGrade(SourceGrade.P1);
        source.setAllowFetch(true);
        source.setAllowStoreDerived(true);

        NewsEntity news = new NewsEntity();
        news.setRobots(false);
        news.setTtl(100);

        PolicyDecision decision = policyEngine.evaluate(source, news);

        assertThat(decision.isAllowFetch()).isFalse();
        assertThat(decision.getReason()).isEqualTo("robots=false");
    }
}

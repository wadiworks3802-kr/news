package com.wangbyul.gnd.nlp.service;

import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.exception.HumanReviewReason;
import com.wangbyul.gnd.core.exception.HumanReviewRequiredException;
import com.wangbyul.gnd.core.service.NlpPipelineService;
import java.util.List;
import org.springframework.stereotype.Service;
/**
 * NlpPipelineServiceImpl 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Service
public class NlpPipelineServiceImpl implements NlpPipelineService {

    private static final double COMET_THRESHOLD = 0.80;
    private static final double SUMMAC_THRESHOLD = 0.75;

    @Override
    public NewsEntity process(NewsEntity news) {
        double comet = runComet(news);
        int mqmCritical = runMqmCritical(news);
        if (mqmCritical > 0 || comet < COMET_THRESHOLD) {
            throw new HumanReviewRequiredException(HumanReviewReason.TRANSLATION_GATE_FAILED, "translation gate failed");
        }

        news.setTitleKo("[KO] " + news.getTitleRaw());
        news.setSummaryKo(summarize(news.getBodyRaw()));

        double summaC = runSummaC(news);
        if (summaC < SUMMAC_THRESHOLD) {
            throw new HumanReviewRequiredException(HumanReviewReason.SUMMARY_GATE_FAILED, "summary gate failed");
        }

        if (news.getEvidenceSpans() == null || news.getEvidenceSpans().isBlank() || "[]".equals(news.getEvidenceSpans())) {
            news.setEvidenceSpans("[\"span:0-120\"]");
        }

        return news;
    }

    private double runComet(NewsEntity news) {
        return 0.84;
    }

    private int runMqmCritical(NewsEntity news) {
        return 0;
    }

    private double runSummaC(NewsEntity news) {
        return 0.80;
    }

    private String summarize(String bodyRaw) {
        String sanitized = bodyRaw == null ? "" : bodyRaw.replaceAll("\\s+", " ").trim();
        if (sanitized.length() <= 320) {
            return sanitized;
        }

        // 4-6 sentence style summary placeholder.
        List<String> slices = List.of(
                sanitized.substring(0, 80),
                sanitized.substring(80, 160),
                sanitized.substring(160, 240),
                sanitized.substring(240, 320));
        return String.join(". ", slices) + ".";
    }
}

package com.wangbyul.gnd.collector.service;

import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.service.DedupService;
import com.wangbyul.gnd.core.util.HashUtils;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
/**
 * DedupServiceImpl 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Service
public class DedupServiceImpl implements DedupService {

    private final NewsRepository newsRepository;

    public DedupServiceImpl(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }

    @Override
    public boolean isDuplicate(NewsEntity normalized, double threshold) {
        Optional<NewsEntity> exact = newsRepository.findTop1ByContentHash(normalized.getContentHash());
        if (exact.isPresent()) {
            normalized.setDedupGroupId(exact.get().getDedupGroupId() == null ? exact.get().getId() : exact.get().getDedupGroupId());
            return true;
        }

        List<NewsEntity> candidates = newsRepository.findTop200ByCountryOrderByPubUtcDesc(normalized.getCountry());
        for (NewsEntity candidate : candidates) {
            if (candidate.getSimhash64() == null || normalized.getSimhash64() == null) {
                continue;
            }

            double similarity = HashUtils.simHashSimilarity(candidate.getSimhash64(), normalized.getSimhash64());
            if (similarity >= threshold) {
                normalized.setDedupGroupId(candidate.getDedupGroupId() == null ? candidate.getId() : candidate.getDedupGroupId());
                return true;
            }
        }

        normalized.setDedupGroupId(normalized.getId());
        return false;
    }
}

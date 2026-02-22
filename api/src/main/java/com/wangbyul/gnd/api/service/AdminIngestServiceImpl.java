package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.dto.AdminIngestRunRequest;
import com.wangbyul.gnd.api.dto.AdminIngestRunResultDto;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.domain.SourceGrade;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.repository.SourceRepository;
import com.wangbyul.gnd.core.service.FetchService;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
/**
 * 관리자 수동 수집 실행 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 설계 의도:
 * - 소스별 부분 성공/실패를 집계해 응답
 * - 일부 소스 실패가 전체 요청 500으로 번지지 않도록 분리 처리
 */
public class AdminIngestServiceImpl implements AdminIngestService {

    private final SourceRepository sourceRepository;
    private final NewsRepository newsRepository;
    private final FetchService fetchService;

    public AdminIngestServiceImpl(
            SourceRepository sourceRepository,
            NewsRepository newsRepository,
            FetchService fetchService) {
        this.sourceRepository = sourceRepository;
        this.newsRepository = newsRepository;
        this.fetchService = fetchService;
    }

    @Override
    public AdminIngestRunResultDto runNow(AdminIngestRunRequest request) {
        int limit = request.getLimit() == null ? 50 : request.getLimit();
        // sid/grade/allowFetch 기준으로 실제 실행 소스 확정
        List<SourceEntity> sources = resolveSources(request, limit);

        int requested = sources.size();
        int processed = 0;
        int succeeded = 0;
        int failed = 0;
        long created = 0;

        for (SourceEntity source : sources) {
            long before = newsRepository.count();
            processed++;
            try {
                fetchService.fetch(source);
                // 단건 소스 실행 전/후 건수 차이로 생성 건수 산정
                long after = newsRepository.count();
                if (after > before) {
                    created += (after - before);
                }
                succeeded++;
            } catch (Exception ignored) {
                // 실패는 집계만 하고 다음 소스 계속 진행
                failed++;
            }
        }

        return AdminIngestRunResultDto.builder()
                .requested(requested)
                .processed(processed)
                .succeeded(succeeded)
                .failed(failed)
                .newsCreated(created)
                .build();
    }

    private List<SourceEntity> resolveSources(AdminIngestRunRequest request, int limit) {
        // sid 지정 시 단일 소스 실행
        if (request.getSid() != null && !request.getSid().isBlank()) {
            SourceEntity source = sourceRepository.findById(request.getSid())
                    .orElseThrow(() -> new EntityNotFoundException("source not found: " + request.getSid()));
            if (!Boolean.TRUE.equals(source.getAllowFetch())) {
                return List.of();
            }
            return List.of(source);
        }

        List<SourceEntity> resolved;
        // grade 지정 시 해당 등급만, 미지정 시 전체 allowFetch 소스
        if (request.getGrade() != null && !request.getGrade().isBlank()) {
            resolved = sourceRepository.findBySourceGradeAndAllowFetchTrueOrderByPriorityAsc(SourceGrade.valueOf(request.getGrade()));
        } else {
            resolved = sourceRepository.findByAllowFetchTrueOrderByPriorityAsc();
        }

        // 과도한 실행 방지를 위해 limit 적용
        return resolved.stream().limit(limit).toList();
    }
}

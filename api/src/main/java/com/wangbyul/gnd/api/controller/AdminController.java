package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.AdminSourceRequest;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.domain.SourceGrade;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import com.wangbyul.gnd.core.repository.SourceRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * AdminController 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@RestController
@RequestMapping("/api/admin/sources")
public class AdminController {

    private final SourceRepository sourceRepository;

    public AdminController(SourceRepository sourceRepository) {
        this.sourceRepository = sourceRepository;
    }

    @GetMapping
    public ApiEnvelope<List<SourceEntity>> list() {
        return envelope(sourceRepository.findAll(), Map.of("count", sourceRepository.count()));
    }

    @GetMapping("/{sid}")
    public ApiEnvelope<SourceEntity> get(@PathVariable String sid) {
        SourceEntity row = sourceRepository.findById(sid)
                .orElseThrow(() -> new EntityNotFoundException("source not found: " + sid));
        return envelope(row, Map.of());
    }

    @PostMapping
    public ApiEnvelope<SourceEntity> create(@RequestBody @Valid AdminSourceRequest request) {
        SourceEntity source = map(request, new SourceEntity());
        source = sourceRepository.save(source);
        return envelope(source, Map.of("created", true));
    }

    @PutMapping("/{sid}")
    public ApiEnvelope<SourceEntity> update(@PathVariable String sid, @RequestBody @Valid AdminSourceRequest request) {
        SourceEntity existing = sourceRepository.findById(sid)
                .orElseThrow(() -> new EntityNotFoundException("source not found: " + sid));
        SourceEntity updated = map(request, existing);
        updated = sourceRepository.save(updated);
        return envelope(updated, Map.of("updated", true));
    }

    @DeleteMapping("/{sid}")
    public ApiEnvelope<Map<String, Object>> delete(@PathVariable String sid) {
        if (!sourceRepository.existsById(sid)) {
            throw new EntityNotFoundException("source not found: " + sid);
        }
        sourceRepository.deleteById(sid);
        return envelope(Map.of("deleted", true, "sid", sid), Map.of());
    }

    private SourceEntity map(AdminSourceRequest request, SourceEntity source) {
        source.setSid(request.getSid());
        source.setCountry(request.getCountry());
        source.setSourceGrade(request.getSourceGrade());
        source.setPriority(request.getPriority());
        source.setAllowFetch(request.getAllowFetch());
        source.setAllowStoreRaw(request.getSourceGrade() == SourceGrade.P0 && request.getAllowStoreRaw());
        source.setAllowStoreDerived(request.getAllowStoreDerived());
        source.setCacheTtlSeconds(request.getCacheTtlSeconds());
        source.setLicensePolicy(request.getLicensePolicy());
        source.setRobotsPolicy(request.getRobotsPolicy());
        source.setEndpointUrl(request.getEndpointUrl());
        return source;
    }

    private <T> ApiEnvelope<T> envelope(T data, Map<String, Object> meta) {
        return ApiEnvelope.<T>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }
}

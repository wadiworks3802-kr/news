package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.AdminIngestRunRequest;
import com.wangbyul.gnd.api.dto.AdminIngestRunResultDto;
import com.wangbyul.gnd.api.service.AdminIngestService;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * AdminIngestController 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@RestController
@RequestMapping("/api/admin/ingest")
public class AdminIngestController {

    private final AdminIngestService adminIngestService;

    public AdminIngestController(AdminIngestService adminIngestService) {
        this.adminIngestService = adminIngestService;
    }

    @PostMapping("/run")
    public ApiEnvelope<AdminIngestRunResultDto> runIngest(@RequestBody @Valid AdminIngestRunRequest request) {
        AdminIngestRunResultDto result = adminIngestService.runNow(request);
        return ApiEnvelope.<AdminIngestRunResultDto>builder()
                .data(result)
                .meta(Map.of("mode", "manual"))
                .traceId(traceId())
                .build();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }
}

package com.wangbyul.gnd.api.otel;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/**
 * OtelFilter 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Component
public class OtelFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceparent = request.getHeader("traceparent");
        String traceId = extractTraceId(traceparent);
        MDC.put("trace_id", traceId);
        response.setHeader("trace_id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("trace_id");
        }
    }

    private String extractTraceId(String traceparent) {
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}

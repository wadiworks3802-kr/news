package com.wangbyul.gnd.batch.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
/**
 * OtelBatchTracer 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Component
public class OtelBatchTracer {

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("com.wangbyul.gnd.batch");

    public void trace(String spanName, Runnable runnable) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        String traceId = span.getSpanContext().getTraceId();
        MDC.put("trace_id", traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        try {
            runnable.run();
        } finally {
            span.end();
            MDC.remove("trace_id");
        }
    }
}

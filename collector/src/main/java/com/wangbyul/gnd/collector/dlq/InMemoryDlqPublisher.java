package com.wangbyul.gnd.collector.dlq;

import com.wangbyul.gnd.core.service.DlqPublisher;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
/**
 * InMemoryDlqPublisher 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Slf4j
@Component
public class InMemoryDlqPublisher implements DlqPublisher {

    private final ConcurrentMap<String, String> dlqStore = new ConcurrentHashMap<>();

    @Override
    public void publish(String key, String payload, String reason) {
        dlqStore.put(key, payload);
        log.error("DLQ published: key={}, reason={}", key, reason);
    }
}

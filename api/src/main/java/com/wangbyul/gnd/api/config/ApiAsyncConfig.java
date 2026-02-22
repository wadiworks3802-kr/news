package com.wangbyul.gnd.api.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * API 비동기 실행기 설정.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Configuration
public class ApiAsyncConfig {

    @Bean(name = "newsTranslationExecutor")
    public Executor newsTranslationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 번역 API 병목을 피하기 위해 소규모 고정 풀로 제한
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(400);
        executor.setThreadNamePrefix("news-ko-");
        executor.initialize();
        return executor;
    }
}

package com.wangbyul.gnd.collector.config;

import com.wangbyul.gnd.collector.client.UpstreamThrottleException;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
/**
 * CollectorConfig 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Configuration
public class CollectorConfig {

    @Bean
    public WebClient collectorWebClient(WebClient.Builder builder) {
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                .responseTimeout(Duration.ofSeconds(10));

        return builder
                .clientConnector(new ReactorClientHttpConnector(client))
                .build();
    }

    @Bean
    public RetryTemplate upstreamRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // Initial call + 3 retries => 1m, 4m, 16m backoff.
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                4,
                Map.of(UpstreamThrottleException.class, true));

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(Duration.ofMinutes(1).toMillis());
        backOffPolicy.setMultiplier(4.0);
        backOffPolicy.setMaxInterval(Duration.ofMinutes(16).toMillis());

        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }
}

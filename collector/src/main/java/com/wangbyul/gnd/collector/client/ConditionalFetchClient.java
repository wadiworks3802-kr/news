package com.wangbyul.gnd.collector.client;

import java.time.OffsetDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
/**
 * ConditionalFetchClient 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Component
public class ConditionalFetchClient {

    private final WebClient webClient;

    public ConditionalFetchClient(WebClient collectorWebClient) {
        this.webClient = collectorWebClient;
    }

    public FetchResult fetch(String url, String etag, String ifModifiedSince) {
        return webClient.get()
                .uri(url)
                .headers(headers -> {
                    if (etag != null && !etag.isBlank()) {
                        headers.setIfNoneMatch(etag);
                    }
                    if (ifModifiedSince != null && !ifModifiedSince.isBlank()) {
                        headers.add(HttpHeaders.IF_MODIFIED_SINCE, ifModifiedSince);
                    }
                })
                .exchangeToMono(response -> toResult(response.statusCode(), response.headers().asHttpHeaders(), response.bodyToMono(String.class)))
                .block();
    }

    private Mono<FetchResult> toResult(HttpStatusCode status, HttpHeaders headers, Mono<String> bodyMono) {
        int code = status.value();
        if (code == 429) {
            return Mono.error(new UpstreamThrottleException("HTTP429: retry required"));
        }
        if (code == 304) {
            return Mono.just(new FetchResult(code, "", headers.getETag(), headers.getFirst(HttpHeaders.LAST_MODIFIED), OffsetDateTime.now()));
        }
        if (status.isError()) {
            return bodyMono.defaultIfEmpty("")
                    .flatMap(body -> Mono.error(new IllegalStateException("Upstream error: " + code + " / " + body)));
        }
        return bodyMono.defaultIfEmpty("")
                .map(body -> new FetchResult(code, body, headers.getETag(), headers.getFirst(HttpHeaders.LAST_MODIFIED), OffsetDateTime.now()));
    }
}

package com.wangbyul.gnd.collector.service;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.stereotype.Component;
/**
 * UrlNormalizer 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Component
public class UrlNormalizer {

    public String normalize(String rawUrl) {
        try {
            URI uri = new URI(rawUrl).normalize();
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            int port = uri.getPort();
            boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            String normalizedPort = (port == -1 || defaultPort) ? "" : ":" + port;
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
            return scheme + "://" + host + normalizedPort + path + query;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + rawUrl, e);
        }
    }
}

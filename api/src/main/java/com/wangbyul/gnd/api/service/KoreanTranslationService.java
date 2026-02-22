package com.wangbyul.gnd.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 한국어 번역 유틸 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 주요 역할:
 * - 원문이 한국어가 아닐 때 한국어 번역 시도
 * - 실패 시 원문 fallback
 * - 동일 텍스트 재번역을 줄이기 위한 메모리 캐시
 */
@Service
public class KoreanTranslationService {

    private static final Pattern HANGUL_PATTERN = Pattern.compile("[\\uAC00-\\uD7A3]");
    private static final int MAX_CACHE_SIZE = 5000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Value("${app.translation.ko.enabled:true}")
    private boolean enabled;

    @Value("${app.translation.ko.timeout-seconds:4}")
    private int timeoutSeconds;

    @Value("${app.translation.ko.max-chars:500}")
    private int maxChars;

    public KoreanTranslationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 한국어가 아닌 텍스트를 한국어로 변환한다.
     * - 번역 실패/비활성/한국어 텍스트는 원문 반환
     */
    public String toKorean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = normalizeText(text);
        if (normalized.isBlank() || !enabled || containsHangul(normalized)) {
            return normalized;
        }
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
        return cache.computeIfAbsent(normalized, this::translateSafely);
    }

    /**
     * 입력 텍스트가 한국어(한글 포함)인지 빠르게 판별한다.
     */
    public boolean isKoreanText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsHangul(text);
    }

    private String translateSafely(String input) {
        try {
            String query = URLEncoder.encode(input, StandardCharsets.UTF_8);
            String endpoint = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ko&dt=t&q=" + query;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return input;
            }
            String translated = parseTranslatedText(response.body());
            return translated.isBlank() ? input : translated;
        } catch (Exception ignored) {
            return input;
        }
    }

    private String parseTranslatedText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode sentenceNodes = root.path(0);
            if (!sentenceNodes.isArray()) {
                return "";
            }
            StringBuilder translated = new StringBuilder();
            for (JsonNode sentenceNode : sentenceNodes) {
                JsonNode fragmentNode = sentenceNode.path(0);
                if (fragmentNode.isTextual()) {
                    translated.append(fragmentNode.asText());
                }
            }
            return translated.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean containsHangul(String text) {
        return HANGUL_PATTERN.matcher(text).find();
    }

    private String normalizeText(String text) {
        String collapsed = text
                .replaceAll("\\s+", " ")
                .trim();
        int safeMaxChars = Math.max(64, Math.min(maxChars, 1000));
        if (collapsed.length() > safeMaxChars) {
            return collapsed.substring(0, safeMaxChars);
        }
        return collapsed;
    }
}

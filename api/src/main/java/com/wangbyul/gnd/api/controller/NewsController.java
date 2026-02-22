package com.wangbyul.gnd.api.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.NewsDetailDto;
import com.wangbyul.gnd.api.dto.NewsListItemDto;
import com.wangbyul.gnd.api.service.NewsLocalizationService;
import com.wangbyul.gnd.api.service.NewsCacheKeyFactory;
import com.wangbyul.gnd.core.domain.CategoryType;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import com.wangbyul.gnd.core.repository.NewsRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
/**
 * 뉴스 조회 API 컨트롤러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 주요 역할:
 * - 목록/상세 조회
 * - 기간/입력값 검증
 * - Redis 캐시 조회/저장(옵션)
 * - 근거스팬/썸네일 응답 포맷 정규화
 */
public class NewsController {

    /** body_raw 에서 첫 이미지 src를 찾기 위한 정규식 */
    private static final java.util.regex.Pattern IMG_SRC_PATTERN = java.util.regex.Pattern.compile(
            "<img[^>]*src\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final NewsRepository newsRepository;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final NewsLocalizationService newsLocalizationService;

    @Value("${app.cache.news-list-ttl-seconds:30}")
    private long newsListCacheTtlSeconds;

    public NewsController(
            NewsRepository newsRepository,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            NewsLocalizationService newsLocalizationService) {
        this.newsRepository = newsRepository;
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.newsLocalizationService = newsLocalizationService;
    }

    @GetMapping("/news")
    public ApiEnvelope<List<NewsListItemDto>> getNews(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam @NotBlank @Pattern(regexp = "^(BRK|POL|ECO|MKT|DEV|IND)$") String category,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "24h") String period,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(name = "view_lang", defaultValue = "ko") @Pattern(regexp = "^(ko|raw)$") String viewLang,
            @RequestParam(required = false) String q) {

        // 동일 질의 재요청 최적화를 위한 캐시 키 구성
        String cacheKey = NewsCacheKeyFactory.build(country, category, sort, period, page, size, viewLang, q);
        String cached = null;
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            try {
                cached = redisTemplate.opsForValue().get(cacheKey);
            } catch (Exception ignored) {
            }
        }
        if (cached != null && !cached.isBlank()) {
            try {
                CachedNewsList cachedPayload = objectMapper.readValue(cached, CachedNewsList.class);
                if (cachedPayload != null && cachedPayload.data() != null) {
                    return envelope(cachedPayload.data(), Map.of(
                            "page", page,
                            "size", size,
                            "view_lang", viewLang,
                            "total", cachedPayload.total(),
                            "cache_key", cacheKey,
                            "cached", true));
                }
            } catch (Exception ignored) {
            }
            try {
                // 하위호환: 과거 캐시 포맷(List only) 처리
                List<NewsListItemDto> legacyItems = objectMapper.readValue(cached, new TypeReference<>() {});
                return envelope(legacyItems, Map.of(
                        "page", page,
                        "size", size,
                        "view_lang", viewLang,
                        "total", legacyItems.size(),
                        "cache_key", cacheKey,
                        "cached", true));
            } catch (Exception ignored) {
            }
        }

        // 기간 문자열을 조회 범위로 변환
        OffsetDateTime[] range = resolvePeriod(period);
        Pageable pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(
                        Sort.Order.desc("pubUtc"),
                        Sort.Order.desc("fetchUtc"),
                        Sort.Order.desc("createdAt")));
        Page<NewsEntity> rows = newsRepository.findByCountryAndCategoryAndPubUtcBetween(
                country,
                CategoryType.valueOf(category),
                range[0],
                range[1],
                pageable);

        List<NewsEntity> filteredRows = rows.stream()
                .filter(entity -> q == null || q.isBlank() || containsQuery(entity, q, viewLang))
                .toList();
        int prefetchQueued = "ko".equalsIgnoreCase(viewLang)
                ? newsLocalizationService.prefetchTranslations(filteredRows)
                : 0;

        int translationPending = 0;
        List<NewsListItemDto> items = filteredRows.stream()
                .map(entity -> toListDto(entity, viewLang))
                .toList();
        if ("ko".equalsIgnoreCase(viewLang)) {
            translationPending = (int) items.stream()
                    .filter(item -> !looksKorean(item.getTitleKo()) || !looksKorean(item.getSummaryKo()))
                    .count();
        }

        // Redis가 살아있으면 결과 캐시 저장
        if (redisTemplate != null) {
            try {
                long ttlSeconds = Math.max(5, newsListCacheTtlSeconds);
                redisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(new CachedNewsList(items, rows.getTotalElements())),
                        Duration.ofSeconds(ttlSeconds));
            } catch (Exception ignored) {
            }
        }

        return envelope(items, Map.of(
                "page", page,
                "size", size,
                "view_lang", viewLang,
                "total", rows.getTotalElements(),
                "translation_pending", translationPending,
                "translation_prefetch_queued", prefetchQueued,
                "cache_key", cacheKey,
                "cached", false));
    }

    @GetMapping("/news/{id}")
    public ApiEnvelope<NewsDetailDto> getNewsById(
            @PathVariable String id,
            @RequestParam(name = "view_lang", defaultValue = "ko") @Pattern(regexp = "^(ko|raw)$") String viewLang) {
        NewsEntity news = newsRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("news not found: " + id));
        NewsDetailDto dto = toDetailDto(news, viewLang);
        boolean translationPending = "ko".equalsIgnoreCase(viewLang)
                && (!looksKorean(dto.getTitleKo()) || !looksKorean(dto.getSummaryKo()));
        return envelope(dto, Map.of(
                "view_lang", viewLang,
                "translation_pending", translationPending));
    }

    private OffsetDateTime[] resolvePeriod(String period) {
        OffsetDateTime now = OffsetDateTime.now();
        return switch (period) {
            case "24h" -> new OffsetDateTime[]{now.minusHours(24), now};
            case "7d" -> new OffsetDateTime[]{now.minusDays(7), now};
            case "30d" -> new OffsetDateTime[]{now.minusDays(30), now};
            default -> {
                // RFC3339 구간 포맷: 2026-01-01T00:00:00Z,2026-01-02T00:00:00Z
                String[] tokens = period.split(",");
                if (tokens.length != 2) {
                    throw new IllegalArgumentException("period must be 24h/7d/30d or RFC3339 range");
                }
                try {
                    yield new OffsetDateTime[]{OffsetDateTime.parse(tokens[0]), OffsetDateTime.parse(tokens[1])};
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("RFC3339 parse failed", e);
                }
            }
        };
    }

    private boolean containsQuery(NewsEntity entity, String q, String viewLang) {
        String query = q.toLowerCase();
        if ("raw".equalsIgnoreCase(viewLang)) {
            return (entity.getTitleRaw() != null && entity.getTitleRaw().toLowerCase().contains(query))
                    || (entity.getBodyRaw() != null && entity.getBodyRaw().toLowerCase().contains(query));
        }
        return (entity.getTitleKo() != null && entity.getTitleKo().toLowerCase().contains(query))
                || (entity.getSummaryKo() != null && entity.getSummaryKo().toLowerCase().contains(query))
                || (entity.getTitleRaw() != null && entity.getTitleRaw().toLowerCase().contains(query));
    }

    private NewsListItemDto toListDto(NewsEntity entity, String viewLang) {
        String articleUrl = entity.getUrl();
        String sourceIconUrl = buildFaviconUrl(articleUrl);
        NewsLocalizationService.LocalizedContent localized = newsLocalizationService.localizeForView(entity, viewLang);
        return NewsListItemDto.builder()
                .id(entity.getId())
                .country(entity.getCountry())
                .source(entity.getSource() == null ? "" : entity.getSource().getSid())
                .url(articleUrl)
                .category(List.of(entity.getCategory().name()))
                .titleKo(localized.title())
                .summaryKo(localized.summary())
                .pubUtc(entity.getPubUtc())
                .trustScore(entity.getTrustScore())
                .evidenceSpans(parseEvidenceSpans(entity.getEvidenceSpans()))
                .thumbnailUrl(extractFirstImageUrl(entity.getBodyRaw()))
                .sourceIconUrl(sourceIconUrl)
                .build();
    }

    private NewsDetailDto toDetailDto(NewsEntity entity, String viewLang) {
        NewsLocalizationService.LocalizedContent localized = newsLocalizationService.localizeForView(entity, viewLang);
        return NewsDetailDto.builder()
                .id(entity.getId())
                .country(entity.getCountry())
                .lang(entity.getLang())
                .category(List.of(entity.getCategory().name()))
                .url(entity.getUrl())
                .titleRaw(entity.getTitleRaw())
                .bodyRaw(entity.getBodyRaw())
                .titleKo(localized.title())
                .summaryKo(localized.summary())
                .pubUtc(entity.getPubUtc())
                .trustScore(entity.getTrustScore())
                .evidenceSpans(parseEvidenceSpans(entity.getEvidenceSpans()))
                .build();
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private boolean looksKorean(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '\uAC00' && ch <= '\uD7A3') {
                return true;
            }
        }
        return false;
    }

    /**
     * evidence_spans가 문자열/중첩 문자열로 들어오는 경우까지 고려해
     * 안정적으로 List<String> 형태로 반환한다.
     */
    private List<String> parseEvidenceSpans(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        String normalized = rawJson;
        for (int i = 0; i < 2; i++) {
            try {
                JsonNode node = objectMapper.readTree(normalized);
                if (node.isArray()) {
                    return objectMapper.convertValue(node, new TypeReference<>() {});
                }
                if (node.isTextual()) {
                    normalized = node.asText();
                    continue;
                }
            } catch (Exception ignored) {
                break;
            }
            break;
        }
        return List.of(normalized);
    }

    /** 본문 HTML 내 첫 이미지 URL 추출 */
    private String extractFirstImageUrl(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Matcher matcher = IMG_SRC_PATTERN.matcher(html);
        if (matcher.find()) {
            String url = matcher.group(1);
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                return url;
            }
        }
        return "";
    }

    /** 이미지가 없을 때 도메인 favicon으로 썸네일 대체 */
    private String buildFaviconUrl(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(articleUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String domainUrl = (uri.getScheme() == null ? "https" : uri.getScheme()) + "://" + host;
            return "https://www.google.com/s2/favicons?sz=128&domain_url="
                    + URLEncoder.encode(domainUrl, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 공통 성공 응답 포맷 래퍼 */
    private <T> ApiEnvelope<T> envelope(T data, Map<String, Object> meta) {
        return ApiEnvelope.<T>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    /** OTel/MDC trace_id를 응답으로 노출 */
    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }

    /**
     * 목록 캐시 저장 포맷.
     * total을 같이 보관해 홈 화면의 더보기(+N) 계산 정확도를 보장한다.
     */
    private record CachedNewsList(List<NewsListItemDto> data, long total) {
    }
}

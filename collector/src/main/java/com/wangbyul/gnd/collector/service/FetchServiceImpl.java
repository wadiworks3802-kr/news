package com.wangbyul.gnd.collector.service;

import com.wangbyul.gnd.collector.client.ConditionalFetchClient;
import com.wangbyul.gnd.collector.client.FetchResult;
import com.wangbyul.gnd.core.domain.CategoryType;
import com.wangbyul.gnd.core.domain.EventTimeSourceType;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.NewsThumbnailSourceType;
import com.wangbyul.gnd.core.domain.NewsThumbnailStatusType;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.exception.HumanReviewReason;
import com.wangbyul.gnd.core.exception.HumanReviewRequiredException;
import com.wangbyul.gnd.core.policy.PolicyDecision;
import com.wangbyul.gnd.core.policy.PolicyEngine;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.service.DedupService;
import com.wangbyul.gnd.core.service.DlqPublisher;
import com.wangbyul.gnd.core.service.FetchService;
import com.wangbyul.gnd.core.service.NormalizeService;
import com.wangbyul.gnd.core.util.NewsThumbnailParser;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Slf4j
@Service
/**
 * 수집 파이프라인의 핵심 구현체.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 주요 역할:
 * - 외부 RSS/Atom 소스에서 원문 수집
 * - 정책(robots/license/ttl) 검사
 * - 정규화/중복 제거/저장
 * - 카테고리 분류 및 DLQ 발행
 */
public class FetchServiceImpl implements FetchService {

    /** SimHash 중복 판정 임계치 */
    private static final double DEDUP_THRESHOLD = 0.85d;
    /** 소스 1회 호출 당 최대 적재 기사 수 */
    private static final int MAX_ITEMS_PER_SOURCE = 20;
    /** 뉴스 최대 보관 상한(7일) */
    private static final int MAX_RETENTION_SECONDS = 7 * 24 * 60 * 60;

    private final ConditionalFetchClient fetchClient;
    private final RetryTemplate retryTemplate;
    private final NormalizeService normalizeService;
    private final DedupService dedupService;
    private final PolicyEngine policyEngine;
    private final NewsRepository newsRepository;
    private final DlqPublisher dlqPublisher;

    public FetchServiceImpl(
            ConditionalFetchClient fetchClient,
            RetryTemplate upstreamRetryTemplate,
            NormalizeService normalizeService,
            DedupService dedupService,
            PolicyEngine policyEngine,
            NewsRepository newsRepository,
            DlqPublisher dlqPublisher) {
        this.fetchClient = fetchClient;
        this.retryTemplate = upstreamRetryTemplate;
        this.normalizeService = normalizeService;
        this.dedupService = dedupService;
        this.policyEngine = policyEngine;
        this.newsRepository = newsRepository;
        this.dlqPublisher = dlqPublisher;
    }

    @Override
    public void fetch(SourceEntity source) {
        // endpoint가 없으면 수집 대상이 아니므로 종료
        if (source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            return;
        }

        try {
            // 429 등 재시도 정책을 포함한 외부 호출
            FetchResult result = retryTemplate.execute(ctx -> fetchClient.fetch(source.getEndpointUrl(), null, null));
            if (result == null || result.notModified() || result.body().isBlank()) {
                return;
            }

            // 피드 본문을 기사 단위 엔티티로 매핑
            List<NewsEntity> candidates = mapRaw(source, result);
            for (NewsEntity raw : candidates) {
                // 수집 정책 검사 (robots/license/ttl)
                PolicyDecision decision = policyEngine.evaluate(source, raw);
                if (!decision.isAllowFetch()) {
                    throw new HumanReviewRequiredException(HumanReviewReason.ROBOTS_DISALLOWED, decision.getReason());
                }

                // URL/해시 정규화 및 발행시각 보정
                NewsEntity normalized = normalizeService.normalize(raw);
                if (dedupService.isDuplicate(normalized, DEDUP_THRESHOLD)) {
                    // 기존 BRK 기사가 중복으로 판정된 경우, 더 구체 카테고리로 승격 시도
                    promoteCategoryForDuplicate(normalized);
                    log.info("Duplicate skipped: {}", normalized.getUrlNorm());
                    continue;
                }
                if (normalized.getUrlNorm() != null
                        && newsRepository.findByUrlNorm(normalized.getUrlNorm()).isPresent()) {
                    log.info("Duplicate url skipped before insert: {}", normalized.getUrlNorm());
                    continue;
                }

                try {
                    // flush를 즉시 수행해 unique(url_norm) 충돌을 같은 루프에서 처리
                    newsRepository.saveAndFlush(normalized);
                } catch (DataIntegrityViolationException e) {
                    // 동시성/근접 중복으로 unique(url_norm) 충돌이 발생할 수 있음
                    log.info("Duplicate url skipped by DB constraint: {}", normalized.getUrlNorm());
                }
            }
        } catch (HumanReviewRequiredException e) {
            // 정책 위반은 명시 사유로 DLQ 기록
            dlqPublisher.publish(source.getSid(), source.getEndpointUrl(), e.getReason().name());
            throw e;
        } catch (Exception e) {
            // 예외는 최종 실패로 DLQ 기록
            dlqPublisher.publish(source.getSid(), source.getEndpointUrl(), "FINAL_FAILURE");
            throw new IllegalStateException("fetch pipeline failed", e);
        }
    }

    /**
     * 원시 fetch 결과를 기사 목록으로 변환한다.
     * - RSS/Atom 파싱 성공 시 항목별 엔티티 생성
     * - 파싱 실패 시 단일 fallback 기사 생성
     */
    private List<NewsEntity> mapRaw(SourceEntity source, FetchResult result) {
        List<FeedItem> feedItems = extractFeedItems(result.body());
        OffsetDateTime fetchedAt = result.fetchedAt() == null ? OffsetDateTime.now() : result.fetchedAt();
        if (feedItems.isEmpty()) {
            return List.of(buildEntity(
                    source,
                    source.getEndpointUrl(),
                    "Fetched headline",
                    result.body(),
                    fetchedAt,
                    fetchedAt,
                    ""));
        }

        List<NewsEntity> entities = new ArrayList<>();
        for (FeedItem feedItem : feedItems) {
            entities.add(buildEntity(
                    source,
                    safeUrl(feedItem.link(), source.getEndpointUrl()),
                    defaultIfBlank(feedItem.title(), "Fetched headline"),
                    defaultIfBlank(feedItem.body(), result.body()),
                    feedItem.pubUtc(),
                    fetchedAt,
                    feedItem.thumbnailUrl()));
            if (entities.size() >= MAX_ITEMS_PER_SOURCE) {
                break;
            }
        }
        return entities;
    }

    /**
     * DB 저장 가능한 뉴스 엔티티를 구성한다.
     * 분류/요약/근거 메타를 이 단계에서 기본 세팅한다.
     */
    private NewsEntity buildEntity(
            SourceEntity source,
            String url,
            String title,
            String bodyRaw,
            OffsetDateTime pubUtc,
            OffsetDateTime fetchedAt,
            String rssThumbnailUrl) {
        String rawTitle = defaultIfBlank(title, "Fetched headline");
        String normalizedTitle = decodeHtmlEntities(rawTitle)
                .replaceAll("\\s+", " ")
                .trim();
        NewsEntity entity = new NewsEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setSource(source);
        entity.setCountry(source.getCountry());
        entity.setLang("en");
        entity.setCategory(classifyCategory(title, bodyRaw));
        entity.setUrl(url);
        entity.setTitleRaw(rawTitle);
        entity.setBodyRaw(bodyRaw);
        entity.setPubUtc(pubUtc);
        entity.setFetchUtc(fetchedAt);
        entity.setPublishedAtUtc(pubUtc == null ? fetchedAt : pubUtc);
        entity.setFetchedAtUtc(fetchedAt);
        entity.setEventTimeSource(pubUtc == null ? EventTimeSourceType.FETCH_UTC : EventTimeSourceType.PUBLISHED_AT_UTC);
        entity.setLicense(source.getLicensePolicy());
        entity.setRobots(true);
        entity.setTtl(clampRetentionSeconds(source.getCacheTtlSeconds()));
        entity.setTitleKo(normalizedTitle.isBlank() ? rawTitle : normalizedTitle);
        entity.setSummaryKo(summarize(bodyRaw));
        entity.setEvidenceSpans("[\"source:" + source.getSid() + "\",\"mode:live-fetch\"]");
        applyThumbnailMetadata(entity, bodyRaw, rssThumbnailUrl);
        return entity;
    }

    /**
     * 썸네일 메타데이터를 구조화 저장한다.
     * 우선순위: RSS enclosure/media:* > OG/TWITTER/BODY 파서 > EMPTY/FAILED
     */
    private void applyThumbnailMetadata(NewsEntity entity, String bodyRaw, String rssThumbnailUrl) {
        String rssImage = safeUrl(defaultIfBlank(rssThumbnailUrl, ""), "");
        if (!rssImage.isBlank()) {
            entity.setThumbnailUrl(rssImage);
            entity.setThumbnailSource(NewsThumbnailSourceType.RSS);
            entity.setThumbnailStatus(NewsThumbnailStatusType.SUCCESS);
            return;
        }

        var parsed = NewsThumbnailParser.parse(bodyRaw);
        entity.setThumbnailUrl(parsed.thumbnailUrl());
        entity.setThumbnailSource(parsed.source());
        entity.setThumbnailStatus(parsed.status());
    }

    /**
     * RSS/Atom XML 본문을 안전하게 파싱해 피드 항목 리스트로 변환한다.
     * 외부 엔티티 확장을 비활성화하여 XXE 위험을 줄인다.
     */
    private List<FeedItem> extractFeedItems(String body) {
        String raw = body == null ? "" : body.trim();
        if (raw.isBlank() || !raw.startsWith("<")) {
            return List.of();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            var doc = builder.parse(new InputSource(new StringReader(raw)));

            List<FeedItem> items = new ArrayList<>();
            NodeList rssItems = doc.getElementsByTagName("item");
            for (int i = 0; i < rssItems.getLength(); i++) {
                Node node = rssItems.item(i);
                if (!(node instanceof Element element)) {
                    continue;
                }
                String title = childText(element, "title");
                String link = childText(element, "link");
                String description = firstNonBlank(
                        childText(element, "description"),
                        childText(element, "content:encoded"));
                String feedImageUrl = extractFeedImageUrl(element);
                description = enrichDescriptionWithFeedImage(description, feedImageUrl);
                OffsetDateTime pubUtc = parseDate(firstNonBlank(
                        childText(element, "pubDate"),
                        childText(element, "dc:date"),
                        childText(element, "published"),
                        childText(element, "updated")));
                items.add(new FeedItem(title, link, description, pubUtc, feedImageUrl));
            }

            NodeList atomEntries = doc.getElementsByTagName("entry");
            for (int i = 0; i < atomEntries.getLength(); i++) {
                Node node = atomEntries.item(i);
                if (!(node instanceof Element element)) {
                    continue;
                }
                String title = childText(element, "title");
                String description = firstNonBlank(
                        childText(element, "summary"),
                        childText(element, "content"));
                String link = resolveAtomLink(element);
                String feedImageUrl = extractFeedImageUrl(element);
                description = enrichDescriptionWithFeedImage(description, feedImageUrl);
                OffsetDateTime pubUtc = parseDate(firstNonBlank(
                        childText(element, "published"),
                        childText(element, "updated")));
                items.add(new FeedItem(title, link, description, pubUtc, feedImageUrl));
            }

            return items.stream()
                    .filter(item -> !defaultIfBlank(item.title(), "").isBlank()
                            || !defaultIfBlank(item.body(), "").isBlank())
                    .toList();
        } catch (Exception e) {
            log.debug("feed parse skipped: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveAtomLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            Node linkNode = links.item(i);
            if (linkNode instanceof Element linkElement) {
                String href = defaultIfBlank(linkElement.getAttribute("href"), "");
                if (!href.isBlank()) {
                    return href;
                }
                String text = linkElement.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    /**
     * RSS/Atom 본문에 이미지 태그가 없더라도 media:thumbnail/enclosure의 URL을
     * 앞에 삽입해 이후 API 레이어에서 썸네일 추출이 가능하도록 보강한다.
     */
    private String enrichDescriptionWithFeedImage(String description, String imageUrl) {
        String body = defaultIfBlank(description, "");
        String safeImage = safeUrl(defaultIfBlank(imageUrl, ""), "");
        if (safeImage.isBlank()) {
            return body;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("<img") || lower.contains("&lt;img")) {
            return body;
        }
        return "<img src=\"" + safeImage + "\" alt=\"thumb\" /> " + body;
    }

    /**
     * RSS/Atom 엔트리에서 대표 이미지 URL 추출.
     * - media:thumbnail / media:content / enclosure 순으로 확인
     * - image/* 타입만 우선 허용하고, 타입 정보 없으면 URL만으로 보조 허용
     */
    private String extractFeedImageUrl(Element entry) {
        String fromMediaThumb = extractElementAttribute(entry, "media:thumbnail", "url", true);
        if (!fromMediaThumb.isBlank()) {
            return fromMediaThumb;
        }
        String fromMediaContent = extractElementAttribute(entry, "media:content", "url", true);
        if (!fromMediaContent.isBlank()) {
            return fromMediaContent;
        }
        String fromEnclosure = extractElementAttribute(entry, "enclosure", "url", true);
        if (!fromEnclosure.isBlank()) {
            return fromEnclosure;
        }
        return "";
    }

    private String extractElementAttribute(Element parent, String tagName, String attrName, boolean imageOnly) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String rawUrl = defaultIfBlank(element.getAttribute(attrName), "");
            String url = safeUrl(rawUrl, "");
            if (url.isBlank()) {
                continue;
            }
            if (!imageOnly) {
                return url;
            }
            String type = defaultIfBlank(element.getAttribute("type"), "").toLowerCase(Locale.ROOT);
            if (type.isBlank() || type.startsWith("image/")) {
                return url;
            }
        }
        return "";
    }

    private OffsetDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(value).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH);
            return ZonedDateTime.parse(value, formatter).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private String summarize(String text) {
        String normalized = stripTags(defaultIfBlank(text, ""));
        if (normalized.isBlank()) {
            return "(요약 없음)";
        }
        if (normalized.length() <= 280) {
            return normalized;
        }
        return normalized.substring(0, 277) + "...";
    }

    private String stripTags(String text) {
        return decodeHtmlEntities(defaultIfBlank(text, ""))
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String decodeHtmlEntities(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String decoded = input;
        for (int i = 0; i < 2; i++) {
            String next = decoded
                    .replace("&nbsp;", " ")
                    .replace("&#160;", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&amp;", "&");
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        return decoded;
    }

    private String safeUrl(String candidate, String fallback) {
        String value = defaultIfBlank(candidate, fallback);
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return fallback;
    }

    /**
     * 소스별 TTL이 과도하게 커지는 것을 방지하기 위해
     * 저장 TTL을 1분~7일 범위로 보정한다.
     */
    private int clampRetentionSeconds(Integer sourceTtlSeconds) {
        if (sourceTtlSeconds == null) {
            return MAX_RETENTION_SECONDS;
        }
        return Math.max(60, Math.min(sourceTtlSeconds, MAX_RETENTION_SECONDS));
    }

    /**
     * 제목/본문 기반 휴리스틱 카테고리 분류.
     * - 키워드 매칭 우선
     * - 미매칭 시 해시 버킷으로 분산해 특정 섹션만 비는 현상을 완화
     */
    private CategoryType classifyCategory(String title, String body) {
        String text = (defaultIfBlank(title, "") + " " + stripTags(defaultIfBlank(body, ""))).toLowerCase(Locale.ROOT);

        if (containsAny(text,
                "breaking", "urgent", "developing", "속보", "긴급")) {
            return CategoryType.BRK;
        }

        if (containsAny(text,
                "stock", "stocks", "market", "nasdaq", "dow", "s&p", "futures", "bond", "yield", "forex", "bitcoin", "crypto", "etf",
                "증시", "주가", "코스피", "코스닥", "환율", "채권")) {
            return CategoryType.MKT;
        }
        if (containsAny(text,
                "economy", "economic", "inflation", "cpi", "gdp", "interest rate", "rate cut", "recession", "unemployment", "trade",
                "경제", "물가", "인플레이션", "금리", "실업", "경기")) {
            return CategoryType.ECO;
        }
        if (containsAny(text,
                "election", "president", "government", "minister", "parliament", "policy", "senate", "congress", "white house", "diplomacy",
                "정치", "정부", "대통령", "총리", "선거", "국회", "외교")) {
            return CategoryType.POL;
        }
        if (containsAny(text,
                "ai", "technology", "tech", "software", "chip", "semiconductor", "startup", "cloud", "cyber", "robot",
                "기술", "인공지능", "반도체", "소프트웨어", "클라우드", "사이버")) {
            return CategoryType.DEV;
        }
        if (containsAny(text,
                "industry", "industrial", "manufacturing", "factory", "supply chain", "energy", "battery", "logistics", "shipping", "oil", "gas",
                "산업", "제조", "공장", "공급망", "에너지", "배터리", "원유", "가스")) {
            return CategoryType.IND;
        }

        int bucket = Math.floorMod(text.hashCode(), 5);
        return switch (bucket) {
            case 0 -> CategoryType.POL;
            case 1 -> CategoryType.ECO;
            case 2 -> CategoryType.MKT;
            case 3 -> CategoryType.DEV;
            default -> CategoryType.IND;
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 중복으로 스킵된 기사라도 기존 저장본이 BRK이면
     * 더 구체적인 카테고리(POL/ECO/MKT/DEV/IND)로 승격한다.
     */
    private void promoteCategoryForDuplicate(NewsEntity normalized) {
        if (normalized.getCategory() == null || normalized.getCategory() == CategoryType.BRK) {
            return;
        }
        newsRepository.findByUrlNorm(normalized.getUrlNorm())
                .or(() -> newsRepository.findTop1ByContentHash(normalized.getContentHash()))
                .ifPresent(existing -> {
                    if (existing.getCategory() == CategoryType.BRK) {
                        existing.setCategory(normalized.getCategory());
                        if (existing.getTitleKo() == null || existing.getTitleKo().isBlank()) {
                            existing.setTitleKo(normalized.getTitleKo());
                        }
                        if (existing.getSummaryKo() == null || existing.getSummaryKo().isBlank()) {
                            existing.setSummaryKo(normalized.getSummaryKo());
                        }
                        newsRepository.save(existing);
                    }
                });
    }

    private String firstNonBlank(String first, String second) {
        return !defaultIfBlank(first, "").isBlank() ? first : second;
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private record FeedItem(String title, String link, String body, OffsetDateTime pubUtc, String thumbnailUrl) {
        private FeedItem {
            title = title == null ? "" : title.trim();
            link = link == null ? "" : link.trim();
            body = body == null ? "" : body.trim();
            thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl.trim();
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}

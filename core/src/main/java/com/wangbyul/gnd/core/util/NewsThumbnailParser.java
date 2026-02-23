package com.wangbyul.gnd.core.util;

import com.wangbyul.gnd.core.domain.NewsThumbnailSourceType;
import com.wangbyul.gnd.core.domain.NewsThumbnailStatusType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 뉴스 본문/메타 HTML에서 대표 썸네일 URL을 추출한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public final class NewsThumbnailParser {

    /** 본문 <img src> */
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile(
            "<img[^>]*src\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
            Pattern.CASE_INSENSITIVE);
    /** lazy 속성(data-src/data-original/data-lazy-src) */
    private static final Pattern IMG_LAZY_SRC_PATTERN = Pattern.compile(
            "<img[^>]*(?:data-src|data-original|data-lazy-src)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
            Pattern.CASE_INSENSITIVE);
    /** srcset 첫 후보 URL 추출 */
    private static final Pattern IMG_SRCSET_PATTERN = Pattern.compile(
            "<img[^>]*srcset\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
            Pattern.CASE_INSENSITIVE);
    /** og:image 메타 */
    private static final Pattern OG_IMAGE_PATTERN = Pattern.compile(
            "<meta[^>]*(?:property|name)\\s*=\\s*['\\\"]og:image['\\\"][^>]*content\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]|"
                    + "<meta[^>]*content\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*(?:property|name)\\s*=\\s*['\\\"]og:image['\\\"]",
            Pattern.CASE_INSENSITIVE);
    /** twitter:image 메타 */
    private static final Pattern TWITTER_IMAGE_PATTERN = Pattern.compile(
            "<meta[^>]*(?:property|name)\\s*=\\s*['\\\"]twitter:image(?::src)?['\\\"][^>]*content\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]|"
                    + "<meta[^>]*content\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*(?:property|name)\\s*=\\s*['\\\"]twitter:image(?::src)?['\\\"]",
            Pattern.CASE_INSENSITIVE);
    /** HTML 외 일반 텍스트 내 이미지 URL */
    private static final Pattern PLAIN_IMAGE_URL_PATTERN = Pattern.compile(
            "(https?://[^\\s\\\"'<>]+\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\\s\\\"'<>]*)?)",
            Pattern.CASE_INSENSITIVE);

    private NewsThumbnailParser() {
    }

    /**
     * HTML 본문/메타에서 대표 썸네일 추출 결과를 반환한다.
     * 우선순위: OG > TWITTER > BODY(img/lazy/srcset/plain) > DEFAULT(EMPTY)
     */
    public static ThumbnailParseResult parse(String rawHtmlOrText) {
        if (rawHtmlOrText == null || rawHtmlOrText.isBlank()) {
            return ThumbnailParseResult.empty();
        }
        String normalized = decodeHtmlEntities(rawHtmlOrText);
        try {
            String ogImage = normalizeImageUrlCandidate(findFirstNonNullGroup(OG_IMAGE_PATTERN, normalized));
            if (!ogImage.isBlank()) {
                return ThumbnailParseResult.success(ogImage, NewsThumbnailSourceType.OG);
            }

            String twitterImage = normalizeImageUrlCandidate(findFirstNonNullGroup(TWITTER_IMAGE_PATTERN, normalized));
            if (!twitterImage.isBlank()) {
                return ThumbnailParseResult.success(twitterImage, NewsThumbnailSourceType.TWITTER);
            }

            String imgSrc = normalizeImageUrlCandidate(findFirstMatch(IMG_SRC_PATTERN, normalized));
            if (!imgSrc.isBlank()) {
                return ThumbnailParseResult.success(imgSrc, NewsThumbnailSourceType.BODY);
            }

            String lazySrc = normalizeImageUrlCandidate(findFirstMatch(IMG_LAZY_SRC_PATTERN, normalized));
            if (!lazySrc.isBlank()) {
                return ThumbnailParseResult.success(lazySrc, NewsThumbnailSourceType.BODY);
            }

            String srcset = normalizeSrcsetCandidate(findFirstMatch(IMG_SRCSET_PATTERN, normalized));
            if (!srcset.isBlank()) {
                return ThumbnailParseResult.success(srcset, NewsThumbnailSourceType.BODY);
            }

            String plainImage = normalizeImageUrlCandidate(findFirstMatch(PLAIN_IMAGE_URL_PATTERN, normalized));
            if (!plainImage.isBlank()) {
                return ThumbnailParseResult.success(plainImage, NewsThumbnailSourceType.BODY);
            }

            return ThumbnailParseResult.empty();
        } catch (Exception ignored) {
            return ThumbnailParseResult.failed();
        }
    }

    private static String findFirstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1) == null ? "" : matcher.group(1);
    }

    private static String findFirstNonNullGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return "";
        }
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            if (group != null && !group.isBlank()) {
                return group;
            }
        }
        return "";
    }

    private static String normalizeSrcsetCandidate(String srcset) {
        if (srcset == null || srcset.isBlank()) {
            return "";
        }
        String[] candidates = srcset.split(",");
        for (String candidate : candidates) {
            String[] tokens = candidate.trim().split("\\s+");
            if (tokens.length == 0) {
                continue;
            }
            String normalized = normalizeImageUrlCandidate(tokens[0]);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String normalizeImageUrlCandidate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = decodeHtmlEntities(value).trim();
        if (normalized.startsWith("//")) {
            normalized = "https:" + normalized;
        }
        if (normalized.startsWith("data:")) {
            return "";
        }
        int whitespace = normalized.indexOf(' ');
        if (whitespace > 0) {
            normalized = normalized.substring(0, whitespace);
        }
        return (normalized.startsWith("http://") || normalized.startsWith("https://")) ? normalized : "";
    }

    private static String decodeHtmlEntities(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String decoded = input;
        for (int i = 0; i < 2; i++) {
            String next = decoded
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

    public record ThumbnailParseResult(
            String thumbnailUrl,
            NewsThumbnailSourceType source,
            NewsThumbnailStatusType status) {

        public static ThumbnailParseResult success(String url, NewsThumbnailSourceType source) {
            return new ThumbnailParseResult(url, source, NewsThumbnailStatusType.SUCCESS);
        }

        public static ThumbnailParseResult empty() {
            return new ThumbnailParseResult("", NewsThumbnailSourceType.DEFAULT, NewsThumbnailStatusType.EMPTY);
        }

        public static ThumbnailParseResult failed() {
            return new ThumbnailParseResult("", NewsThumbnailSourceType.DEFAULT, NewsThumbnailStatusType.FAILED);
        }
    }
}

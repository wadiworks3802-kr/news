package com.wangbyul.gnd.core.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.config.KiwoomProviderProperties;
import com.wangbyul.gnd.core.config.MarketProviderProperties;
import com.wangbyul.gnd.core.domain.AssetSelectionSourceType;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AssetVerificationStatusType;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.service.TickerAliasVerificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 실데이터(키움) 기반으로 운영 유니버스가 비어 있을 때 자산 마스터를 자동 구성한다.
 */
@Slf4j
@Component
public class KiwoomUniverseBootstrapRunner implements ApplicationRunner {

    private final AssetUniverseRepository assetUniverseRepository;
    private final TickerAliasVerificationService tickerAliasVerificationService;
    private final MarketProviderProperties marketProviderProperties;
    private final KiwoomProviderProperties kiwoomProps;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean forceUpdate;
    private final int maxAssets;
    private final int coreCount;
    private final HttpClient httpClient;

    public KiwoomUniverseBootstrapRunner(
            AssetUniverseRepository assetUniverseRepository,
            TickerAliasVerificationService tickerAliasVerificationService,
            MarketProviderProperties marketProviderProperties,
            KiwoomProviderProperties kiwoomProps,
            ObjectMapper objectMapper,
            @Value("${app.market.provider.universe-bootstrap.enabled:true}") boolean enabled,
            @Value("${app.market.provider.universe-bootstrap.force-update:false}") boolean forceUpdate,
            @Value("${app.market.provider.universe-bootstrap.max-assets:200}") int maxAssets,
            @Value("${app.market.provider.universe-bootstrap.core-count:40}") int coreCount) {
        this.assetUniverseRepository = assetUniverseRepository;
        this.tickerAliasVerificationService = tickerAliasVerificationService;
        this.marketProviderProperties = marketProviderProperties;
        this.kiwoomProps = kiwoomProps;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.forceUpdate = forceUpdate;
        this.maxAssets = Math.max(20, maxAssets);
        this.coreCount = Math.max(5, coreCount);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1500, kiwoomProps.getConnectTimeoutMillis())))
                .build();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("kiwoom universe bootstrap disabled");
            return;
        }
        if (!"kiwoom".equalsIgnoreCase(safe(marketProviderProperties.getActive(), ""))) {
            log.info("kiwoom universe bootstrap skipped provider={}", marketProviderProperties.getActive());
            return;
        }

        long currentCount = assetUniverseRepository.count();
        if (currentCount > 0 && !forceUpdate) {
            log.info("kiwoom universe bootstrap skipped existing_assets={}", currentCount);
            return;
        }

        String appKey = firstNonBlank(
                kiwoomProps.getAppKey(),
                readKey(firstNonBlank(kiwoomProps.getAppKeyFile(), System.getenv("KIWOOM_APPKEY_FILE"))),
                System.getenv("KIWOOM_APPKEY"));
        String secretKey = firstNonBlank(
                kiwoomProps.getSecretKey(),
                readKey(firstNonBlank(kiwoomProps.getSecretKeyFile(), System.getenv("KIWOOM_SECRETKEY_FILE"))),
                System.getenv("KIWOOM_SECRETKEY"));
        if (blank(appKey) || blank(secretKey)) {
            log.warn("kiwoom universe bootstrap skipped: credentials missing");
            return;
        }

        String token = issueToken(appKey, secretKey);
        if (blank(token)) {
            log.warn("kiwoom universe bootstrap skipped: token issue failed");
            return;
        }

        ApiCall listCall = post(
                kiwoomProps.getQuotePath(),
                kiwoomProps.getQuoteApiId(),
                token,
                Map.of("mrkt_tp", safe(kiwoomProps.getMarketType(), "0")));
        if (!listCall.ok()) {
            log.warn("kiwoom universe bootstrap failed: http_status={} error={}", listCall.httpStatus(), listCall.error());
            return;
        }

        List<Candidate> candidates = toCandidates(listCall.mapBody());
        if (candidates.isEmpty()) {
            log.warn("kiwoom universe bootstrap failed: empty candidate list");
            return;
        }

        candidates.sort(Comparator
                .comparing(Candidate::rankMetric, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Candidate::code));
        int limit = Math.min(maxAssets, candidates.size());
        int safeCoreCount = Math.min(coreCount, limit);

        int inserted = 0;
        int updated = 0;
        List<AssetUniverseEntity> upserts = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Candidate candidate = candidates.get(i);
            String assetCode = candidate.code() + ".KS";
            AssetUniverseEntity entity = assetUniverseRepository.findById(assetCode).orElseGet(AssetUniverseEntity::new);
            boolean created = blank(entity.getAssetCode());

            entity.setAssetCode(assetCode);
            entity.setAssetName(truncate(safe(candidate.name(), "KR_STOCK_" + candidate.code()), 160));
            entity.setCountry("KR");
            entity.setCountryCode("KR");
            entity.setAssetType(AssetType.STOCK);
            entity.setActive(true);
            entity.setIsTradeEnabled(true);
            entity.setSelectionSource(AssetSelectionSourceType.MARKET_CAP);
            entity.setUniverseLayer(i < safeCoreCount ? UniverseLayerType.CORE : UniverseLayerType.DISCOVERY);
            entity.setIsCoreAsset(i < safeCoreCount);
            entity.setSelectionScore(selectionScore(i, limit));
            entity.setDisplayWeight(Math.max(1, limit - i));
            entity.setLiquidityScore(liquidityScore(i, limit));
            entity.setMarketCapRank(i + 1);
            entity.setStrategyScope("ALL");
            entity.setVerificationStatus(AssetVerificationStatusType.UNVERIFIED);

            String sector = truncate(safe(candidate.sector(), "KR_MARKET"), 64);
            entity.setSector(sector);
            entity.setTheme(sector);
            entity.setThemeCode(normalizeThemeCode(sector));

            upserts.add(entity);
            if (created) {
                inserted++;
            } else {
                updated++;
            }
        }
        assetUniverseRepository.saveAll(upserts);

        try {
            tickerAliasVerificationService.verifyAll("universe-bootstrap");
        } catch (Exception e) {
            log.warn("kiwoom universe bootstrap alias verify failed: {}", e.getMessage());
        }
        log.info("kiwoom universe bootstrap completed inserted={} updated={} total_candidates={} saved={}",
                inserted, updated, candidates.size(), upserts.size());
    }

    private String issueToken(String appKey, String secretKey) {
        ApiCall auth = postToken(Map.of(
                "grant_type", "client_credentials",
                "appkey", appKey,
                "secretkey", secretKey));
        if (!auth.ok()) {
            log.warn("kiwoom auth failed status={} error={}", auth.httpStatus(), auth.error());
            return null;
        }
        return firstNonBlank(str(auth.mapBody().get("token")), str(auth.mapBody().get("access_token")));
    }

    private ApiCall postToken(Map<String, Object> body) {
        return postInternal(kiwoomProps.getTokenPath(), null, null, body);
    }

    private ApiCall post(String path, String apiId, String token, Map<String, Object> body) {
        ApiCall call = postInternal(path, apiId, token, body);
        if (!call.ok()) {
            return call;
        }
        String returnCode = firstNonBlank(
                str(call.mapBody().get("return_code")),
                str(call.mapBody().get("rt_cd")),
                str(call.mapBody().get("rtn_cd")));
        if (!blank(returnCode) && !"0".equals(returnCode) && !"0000".equals(returnCode)) {
            String errMsg = firstNonBlank(
                    str(call.mapBody().get("return_msg")),
                    str(call.mapBody().get("msg1")),
                    "kiwoom api error");
            return ApiCall.fail(call.httpStatus(), errMsg, call.mapBody());
        }
        return call;
    }

    private ApiCall postInternal(String path, String apiId, String token, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(joinUrl(kiwoomProps.getBaseUrl(), path)))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .timeout(Duration.ofMillis(Math.max(1500, kiwoomProps.getRequestTimeoutMillis())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!blank(apiId)) {
                builder.header("api-id", apiId.trim());
                builder.header("cont-yn", "N");
                builder.header("next-key", "");
            }
            if (!blank(token)) {
                builder.header("authorization", "Bearer " + token.trim());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> mapBody = parseMap(response.body());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return ApiCall.ok(status, mapBody);
            }
            String error = firstNonBlank(
                    str(mapBody.get("return_msg")),
                    str(mapBody.get("msg1")),
                    "kiwoom http error");
            return ApiCall.fail(status, error, mapBody);
        } catch (Exception e) {
            return ApiCall.fail(500, safe(e.getMessage(), "kiwoom http call failed"), Map.of());
        }
    }

    private List<Candidate> toCandidates(Map<String, Object> mapBody) {
        Object rawList = mapBody.get("list");
        if (!(rawList instanceof List<?> rows) || rows.isEmpty()) {
            return List.of();
        }
        Map<String, Candidate> dedup = new LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> map = toStringMap(raw);
            String code = normalizeCode(firstNonBlank(
                    str(map.get("code")),
                    str(map.get("stk_cd")),
                    str(map.get("stck_shrn_iscd"))));
            if (!code.matches("\\d{6}")) {
                continue;
            }
            String marketName = safe(firstNonBlank(str(map.get("marketName")), str(map.get("market_name"))), "");
            if (marketName.toUpperCase(Locale.ROOT).contains("ETF")) {
                continue;
            }
            String name = firstNonBlank(str(map.get("name")), str(map.get("stk_nm")), "KR_" + code);
            String sector = firstNonBlank(
                    str(map.get("upName")),
                    str(map.get("up_name")),
                    str(map.get("idx_ind_nm")),
                    marketName,
                    "KR_MARKET");
            BigDecimal rankMetric = firstNumber(
                    map.get("listCount"),
                    map.get("list_count"),
                    map.get("mkt_cap"),
                    map.get("acml_vol"));
            Candidate candidate = new Candidate(code, name, sector, rankMetric == null ? BigDecimal.ZERO : rankMetric);
            Candidate existing = dedup.get(code);
            if (existing == null || candidate.rankMetric().compareTo(existing.rankMetric()) > 0) {
                dedup.put(code, candidate);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private Map<String, Object> parseMap(String rawBody) {
        if (blank(rawBody)) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(rawBody, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return toStringMap(map);
            }
            return Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return converted;
    }

    private BigDecimal selectionScore(int index, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        double ratio = (double) (Math.max(1, total - index)) / (double) total;
        return BigDecimal.valueOf(ratio * 100.0d).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal liquidityScore(int index, int total) {
        if (total <= 0) {
            return BigDecimal.valueOf(0.5d).setScale(4, RoundingMode.HALF_UP);
        }
        double ratio = 1.0d - ((double) index / (double) total) * 0.65d;
        return BigDecimal.valueOf(Math.max(0.1d, ratio)).setScale(4, RoundingMode.HALF_UP);
    }

    private String normalizeThemeCode(String raw) {
        String base = safe(raw, "KR_MARKET")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return truncate(base.isBlank() ? "KR_MARKET" : base, 64);
    }

    private String normalizeCode(String rawCode) {
        String code = safe(rawCode, "").toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        if (code.startsWith("A") && code.substring(1).matches("\\d+")) {
            code = code.substring(1);
        }
        if (code.matches("\\d{7,}")) {
            code = code.substring(code.length() - 6);
        }
        return code;
    }

    private BigDecimal firstNumber(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof Number n) {
                return BigDecimal.valueOf(n.doubleValue());
            }
            String normalized = String.valueOf(value).trim().replace(",", "").replace("+", "");
            if (normalized.isBlank() || "-".equals(normalized)) {
                continue;
            }
            try {
                return new BigDecimal(normalized);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String readKey(String pathValue) {
        if (blank(pathValue)) {
            return null;
        }
        try {
            Path path = Path.of(pathValue.trim());
            if (!path.isAbsolute()) {
                path = Path.of(System.getProperty("user.dir")).resolve(path).normalize();
            }
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return null;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (blank(content)) {
                return null;
            }
            String[] lines = content.replace("\r", "\n").trim().split("\n");
            return lines.length == 0 ? null : lines[0].trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String joinUrl(String base, String path) {
        String b = safe(base, "https://api.kiwoom.com");
        String p = safe(path, "");
        if (p.isBlank()) {
            return b;
        }
        if (b.endsWith("/") && p.startsWith("/")) {
            return b.substring(0, b.length() - 1) + p;
        }
        if (!b.endsWith("/") && !p.startsWith("/")) {
            return b + "/" + p;
        }
        return b + p;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        int safeLength = Math.max(1, maxLength);
        return value.length() <= safeLength ? value : value.substring(0, safeLength);
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private record Candidate(
            String code,
            String name,
            String sector,
            BigDecimal rankMetric) {
    }

    private record ApiCall(
            boolean ok,
            int httpStatus,
            String error,
            Map<String, Object> mapBody) {
        private static ApiCall ok(int httpStatus, Map<String, Object> mapBody) {
            return new ApiCall(true, httpStatus, "", mapBody == null ? Map.of() : mapBody);
        }

        private static ApiCall fail(int httpStatus, String error, Map<String, Object> mapBody) {
            return new ApiCall(false, httpStatus, error, mapBody == null ? Map.of() : mapBody);
        }
    }
}


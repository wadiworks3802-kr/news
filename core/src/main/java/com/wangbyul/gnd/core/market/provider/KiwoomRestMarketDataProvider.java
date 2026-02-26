package com.wangbyul.gnd.core.market.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.config.KiwoomProviderProperties;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.market.MarketDataProvider;
import com.wangbyul.gnd.core.market.dto.MarketAssetMetaDto;
import com.wangbyul.gnd.core.market.dto.MarketPriceBarDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderFetchResult;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthStatus;
import com.wangbyul.gnd.core.market.dto.MarketQuoteDto;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 키움 REST 기반 시장데이터 Provider.
 *
 * OAuth: /oauth2/token (grant_type/client_credentials + appkey/secretkey)
 * 현재가: /api/dostk/stkinfo (api-id=ka10099)
 * 분봉: /api/dostk/chart (api-id=ka10080)
 */
@Slf4j
@Component
public class KiwoomRestMarketDataProvider implements MarketDataProvider {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final DateTimeFormatter KST14 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");

    private final ObjectMapper objectMapper;
    private final KiwoomProviderProperties props;
    private final HttpClient client;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry;

    public KiwoomRestMarketDataProvider(ObjectMapper objectMapper, KiwoomProviderProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, props.getConnectTimeoutMillis())))
                .build();
    }

    @Override
    public String providerId() {
        return "kiwoom";
    }

    @Override
    public MarketProviderFetchResult<MarketQuoteDto> fetchQuotes(List<AssetUniverseEntity> assets) {
        OffsetDateTime requestAt = OffsetDateTime.now();
        List<AssetUniverseEntity> targets = kiwoomTargets(assets);
        int skipped = Math.max(0, (assets == null ? 0 : assets.size()) - targets.size());

        AuthToken token = ensureToken();
        if (!token.ok()) {
            OffsetDateTime responseAt = OffsetDateTime.now();
            return MarketProviderFetchResult.failure(
                    providerId(),
                    "quotes",
                    token.httpStatus(),
                    requestAt,
                    responseAt,
                    token.rawBody(),
                    token.errorCode(),
                    token.errorMessage(),
                    meta(assets == null ? 0 : assets.size(), targets.size(), skipped, 0, true));
        }

        List<MarketQuoteDto> rows = new ArrayList<>();
        int failed = 0;
        ApiResult firstFailure = null;
        String samplePayload = null;
        for (AssetUniverseEntity asset : targets) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("stk_cd", toKiwoomCode(asset.getAssetCode()));
            body.put("mrkt_tp", safe(props.getMarketType(), "0"));
            ApiResult call = post(
                    props.getQuotePath(),
                    props.getQuoteApiId(),
                    token.token(),
                    body);
            if (!call.ok()) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = call;
                }
                continue;
            }
            if (samplePayload == null) {
                samplePayload = call.rawBody();
            }
            MarketQuoteDto dto = toQuote(asset, call.mapBody());
            if (dto == null) {
                failed++;
                continue;
            }
            rows.add(dto);
        }

        OffsetDateTime responseAt = OffsetDateTime.now();
        if (rows.isEmpty() && !targets.isEmpty()) {
            ApiResult fail = firstFailure == null
                    ? ApiResult.fail(502, "", "KIWOOM_EMPTY_RESPONSE", "kiwoom quote response is empty")
                    : firstFailure;
            return MarketProviderFetchResult.failure(
                    providerId(),
                    "quotes",
                    fail.httpStatus(),
                    requestAt,
                    responseAt,
                    safeSnippet(fail.rawBody()),
                    fail.errorCode(),
                    fail.errorMessage(),
                    meta(assets == null ? 0 : assets.size(), targets.size(), skipped, failed, true));
        }

        Map<String, Object> meta = meta(assets == null ? 0 : assets.size(), targets.size(), skipped, failed, false);
        meta.put("partial_failure", failed > 0);
        return MarketProviderFetchResult.success(
                providerId(),
                "quotes",
                rows,
                200,
                requestAt,
                responseAt,
                safeSnippet(samplePayload),
                meta);
    }

    @Override
    public MarketProviderFetchResult<MarketPriceBarDto> fetchBars(
            List<AssetUniverseEntity> assets,
            String timeframe,
            int barsPerAsset) {
        OffsetDateTime requestAt = OffsetDateTime.now();
        List<AssetUniverseEntity> targets = kiwoomTargets(assets);
        int skipped = Math.max(0, (assets == null ? 0 : assets.size()) - targets.size());
        int safeBarsPerAsset = Math.max(1, barsPerAsset);
        int interval = resolveInterval(timeframe);

        AuthToken token = ensureToken();
        if (!token.ok()) {
            OffsetDateTime responseAt = OffsetDateTime.now();
            return MarketProviderFetchResult.failure(
                    providerId(),
                    "bars",
                    token.httpStatus(),
                    requestAt,
                    responseAt,
                    token.rawBody(),
                    token.errorCode(),
                    token.errorMessage(),
                    meta(assets == null ? 0 : assets.size(), targets.size(), skipped, 0, true));
        }

        List<MarketPriceBarDto> rows = new ArrayList<>();
        int failed = 0;
        ApiResult firstFailure = null;
        String samplePayload = null;
        for (AssetUniverseEntity asset : targets) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("stk_cd", toKiwoomCode(asset.getAssetCode()));
            body.put("mrkt_tp", safe(props.getMarketType(), "0"));
            body.put("base_dt", LocalDateTime.now(KST).format(KST14));
            body.put("upd_stkpc_tp", safe(props.getChartAdjustedPriceType(), "1"));
            body.put(safe(props.getChartIntervalField(), "tic_scope"), String.valueOf(interval));

            ApiResult call = post(props.getChartPath(), props.getChartApiId(), token.token(), body);
            if (!call.ok()) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = call;
                }
                continue;
            }
            if (samplePayload == null) {
                samplePayload = call.rawBody();
            }
            List<MarketPriceBarDto> parsed = toBars(asset, timeframe, safeBarsPerAsset, call.mapBody());
            if (parsed.isEmpty()) {
                failed++;
                continue;
            }
            rows.addAll(parsed);
        }

        OffsetDateTime responseAt = OffsetDateTime.now();
        if (rows.isEmpty() && !targets.isEmpty()) {
            ApiResult fail = firstFailure == null
                    ? ApiResult.fail(502, "", "KIWOOM_EMPTY_RESPONSE", "kiwoom bar response is empty")
                    : firstFailure;
            return MarketProviderFetchResult.failure(
                    providerId(),
                    "bars",
                    fail.httpStatus(),
                    requestAt,
                    responseAt,
                    safeSnippet(fail.rawBody()),
                    fail.errorCode(),
                    fail.errorMessage(),
                    meta(assets == null ? 0 : assets.size(), targets.size(), skipped, failed, true));
        }

        Map<String, Object> meta = meta(assets == null ? 0 : assets.size(), targets.size(), skipped, failed, false);
        meta.put("interval_minutes", interval);
        meta.put("bars_per_asset", safeBarsPerAsset);
        meta.put("partial_failure", failed > 0);
        return MarketProviderFetchResult.success(
                providerId(),
                "bars",
                rows,
                200,
                requestAt,
                responseAt,
                safeSnippet(samplePayload),
                meta);
    }

    @Override
    public MarketProviderFetchResult<MarketProviderHealthDto> healthCheck() {
        OffsetDateTime requestAt = OffsetDateTime.now();
        AuthToken token = ensureToken();
        OffsetDateTime responseAt = OffsetDateTime.now();
        if (!token.ok()) {
            MarketProviderHealthDto dto = new MarketProviderHealthDto(
                    providerId(),
                    MarketProviderHealthStatus.DOWN,
                    "kiwoom auth failed: " + safe(token.errorCode(), "AUTH_ERROR"),
                    Duration.between(requestAt, responseAt).toMillis(),
                    responseAt);
            return MarketProviderFetchResult.success(
                    providerId(),
                    "health-check",
                    List.of(dto),
                    token.httpStatus(),
                    requestAt,
                    responseAt,
                    safeSnippet(token.rawBody()),
                    Map.of("degraded", true));
        }

        String probeCode = toKiwoomCode(safe(props.getHealthCheckStockCode(), "005930"));
        ApiResult call = post(
                props.getQuotePath(),
                props.getQuoteApiId(),
                token.token(),
                Map.of(
                        "stk_cd", probeCode,
                        "mrkt_tp", safe(props.getMarketType(), "0")));
        responseAt = OffsetDateTime.now();
        MarketProviderHealthStatus status = call.ok() ? MarketProviderHealthStatus.HEALTHY : MarketProviderHealthStatus.DOWN;
        String reason = call.ok() ? "kiwoom provider reachable" : "kiwoom quote probe failed";
        MarketProviderHealthDto dto = new MarketProviderHealthDto(
                providerId(),
                status,
                reason,
                Duration.between(requestAt, responseAt).toMillis(),
                responseAt);
        return MarketProviderFetchResult.success(
                providerId(),
                "health-check",
                List.of(dto),
                call.ok() ? 200 : call.httpStatus(),
                requestAt,
                responseAt,
                safeSnippet(call.rawBody()),
                Map.of("degraded", !call.ok()));
    }

    private AuthToken ensureToken() {
        if (cachedToken != null && cachedTokenExpiry != null && cachedTokenExpiry.isAfter(Instant.now().plusSeconds(60))) {
            return AuthToken.ok(cachedToken);
        }
        synchronized (this) {
            if (cachedToken != null && cachedTokenExpiry != null && cachedTokenExpiry.isAfter(Instant.now().plusSeconds(60))) {
                return AuthToken.ok(cachedToken);
            }
            String appKey = firstNonBlank(
                    props.getAppKey(),
                    readKey(firstNonBlank(props.getAppKeyFile(), System.getenv("KIWOOM_APPKEY_FILE"))),
                    System.getenv("KIWOOM_APPKEY"));
            String secretKey = firstNonBlank(
                    props.getSecretKey(),
                    readKey(firstNonBlank(props.getSecretKeyFile(), System.getenv("KIWOOM_SECRETKEY_FILE"))),
                    System.getenv("KIWOOM_SECRETKEY"));
            if (blank(appKey) || blank(secretKey)) {
                return AuthToken.fail(
                        503,
                        "KIWOOM_CREDENTIAL_MISSING",
                        "kiwoom appkey/secretkey is not configured",
                        "");
            }

            ApiResult auth = postRaw(props.getTokenPath(), null, null, Map.of(
                    "grant_type", "client_credentials",
                    "appkey", appKey,
                    "secretkey", secretKey));
            if (!auth.ok()) {
                return AuthToken.fail(
                        auth.httpStatus(),
                        safe(auth.errorCode(), "KIWOOM_AUTH_FAILED"),
                        safe(auth.errorMessage(), "kiwoom auth failed"),
                        auth.rawBody());
            }

            String token = firstNonBlank(str(auth.mapBody().get("token")), str(auth.mapBody().get("access_token")));
            if (blank(token)) {
                return AuthToken.fail(502, "KIWOOM_AUTH_TOKEN_EMPTY", "kiwoom token response has no token field", auth.rawBody());
            }

            String expires = firstNonBlank(str(auth.mapBody().get("expires_dt")), str(auth.mapBody().get("expires_at")));
            Instant expiry = Instant.now().plusSeconds(3600);
            if (!blank(expires)) {
                try {
                    expiry = LocalDateTime.parse(expires.trim(), KST14).atZone(KST).toInstant();
                } catch (Exception ignored) {
                }
            }
            cachedToken = token.trim();
            cachedTokenExpiry = expiry;
            return AuthToken.ok(cachedToken);
        }
    }

    private ApiResult post(String path, String apiId, String bearerToken, Map<String, Object> body) {
        ApiResult call = postRaw(path, apiId, bearerToken, body);
        if (!call.ok()) {
            return call;
        }

        String rtnCode = firstNonBlank(
                str(call.mapBody().get("return_code")),
                str(call.mapBody().get("rt_cd")),
                str(call.mapBody().get("rtn_cd")));
        if (!blank(rtnCode) && !("0".equals(rtnCode) || "0000".equals(rtnCode))) {
            String errMsg = firstNonBlank(
                    str(call.mapBody().get("return_msg")),
                    str(call.mapBody().get("msg1")),
                    "kiwoom api error");
            return ApiResult.fail(call.httpStatus(), call.rawBody(), "KIWOOM_API_" + rtnCode, errMsg);
        }
        return call;
    }

    private ApiResult postRaw(String path, String apiId, String bearerToken, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(joinUrl(props.getBaseUrl(), path)))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .timeout(Duration.ofMillis(Math.max(1500, props.getRequestTimeoutMillis())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

            if (!blank(apiId)) {
                builder.header("api-id", apiId.trim());
                builder.header("cont-yn", "N");
                builder.header("next-key", "");
            }
            if (!blank(bearerToken)) {
                builder.header("authorization", "Bearer " + bearerToken.trim());
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String raw = response.body() == null ? "" : response.body();
            Map<String, Object> map = parseMap(raw);
            if (status >= 200 && status < 300) {
                return ApiResult.ok(status, raw, map);
            }
            String errMsg = firstNonBlank(str(map.get("return_msg")), str(map.get("msg1")), raw, "kiwoom http error");
            return ApiResult.fail(status, raw, "KIWOOM_HTTP_" + status, truncate(errMsg, 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResult.fail(500, "", "KIWOOM_HTTP_INTERRUPTED", "kiwoom http call interrupted");
        } catch (Exception e) {
            log.warn("kiwoom api call failed: {}", e.getMessage());
            return ApiResult.fail(500, "", "KIWOOM_HTTP_EXCEPTION", truncate(safe(e.getMessage(), "kiwoom http call failed"), 500));
        }
    }

    private MarketQuoteDto toQuote(AssetUniverseEntity asset, Map<String, Object> raw) {
        Map<String, Object> payload = payloadMap(raw);
        BigDecimal last = num(payload, "cur_prc", "stk_prpr", "last_pric", "close_pric", "lastPrice");
        if (last == null) {
            return null;
        }
        OffsetDateTime quoteTime = dt(payload, "dt", "trd_dt", "cntr_tm");
        if (quoteTime == null) {
            quoteTime = OffsetDateTime.now(UTC);
        }
        return new MarketQuoteDto(
                assetMeta(asset),
                OffsetDateTime.now(UTC),
                quoteTime,
                last,
                num(payload, "flu_rt", "pred_pre_rt", "change_rate", "pred_pre"),
                num(payload, "bid_pric", "bidp1"),
                num(payload, "ask_pric", "askp1"),
                num(payload, "bid_qty", "bidp_rsqn1"),
                num(payload, "ask_qty", "askp_rsqn1"),
                num(payload, "trde_qty", "acml_vol", "volume", "accTrdeQty"),
                providerId());
    }

    private List<MarketPriceBarDto> toBars(AssetUniverseEntity asset, String timeframe, int limit, Map<String, Object> raw) {
        List<Map<String, Object>> payload = payloadList(raw);
        if (payload.isEmpty()) {
            return List.of();
        }
        String tf = blank(timeframe) ? "1m" : timeframe.trim().toLowerCase(Locale.ROOT);
        List<MarketPriceBarDto> rows = new ArrayList<>();
        for (Map<String, Object> row : payload) {
            OffsetDateTime barTime = dt(row, "cntr_tm", "dt", "base_dt", "stck_bsop_date", "datetime");
            BigDecimal open = num(row, "open_pric", "stck_oprc", "open");
            BigDecimal high = num(row, "high_pric", "stck_hgpr", "high");
            BigDecimal low = num(row, "low_pric", "stck_lwpr", "low");
            BigDecimal close = num(row, "cur_prc", "close_pric", "stck_prpr", "close");
            if (barTime == null || open == null || high == null || low == null || close == null) {
                continue;
            }
            rows.add(new MarketPriceBarDto(
                    assetMeta(asset),
                    barTime,
                    tf,
                    open,
                    high,
                    low,
                    close,
                    zeroIfNull(num(row, "trde_qty", "acml_vol", "cntg_vol", "volume")),
                    providerId()));
            if (rows.size() >= limit) {
                break;
            }
        }
        return rows;
    }

    private List<AssetUniverseEntity> kiwoomTargets(List<AssetUniverseEntity> assets) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        return assets.stream()
                .filter(a -> a != null && toKiwoomCode(a.getAssetCode()).matches("\\d{6}"))
                .toList();
    }

    private int resolveInterval(String timeframe) {
        if (blank(timeframe)) {
            return clampInterval(props.getChartDefaultIntervalMinutes());
        }
        String normalized = timeframe.trim().toLowerCase(Locale.ROOT);
        if ("1h".equals(normalized) || "h1".equals(normalized)) {
            return 60;
        }
        if (normalized.endsWith("m")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return clampInterval(Integer.parseInt(normalized));
        } catch (Exception ignored) {
            return clampInterval(props.getChartDefaultIntervalMinutes());
        }
    }

    private int clampInterval(int value) {
        return Math.max(1, Math.min(value, 240));
    }

    private String toKiwoomCode(String assetCode) {
        if (blank(assetCode)) {
            return "";
        }
        String code = assetCode.trim().toUpperCase(Locale.ROOT);
        int colon = code.indexOf(':');
        if (colon >= 0 && colon + 1 < code.length()) {
            code = code.substring(colon + 1);
        }
        int dot = code.indexOf('.');
        if (dot > 0) {
            code = code.substring(0, dot);
        }
        code = code.replaceAll("[^0-9A-Z]", "");
        if (code.startsWith("A") && code.substring(1).matches("\\d+")) {
            code = code.substring(1);
        }
        return code.length() > 6 && code.matches("\\d+") ? code.substring(code.length() - 6) : code;
    }

    private Map<String, Object> parseMap(String raw) {
        if (blank(raw)) {
            return Map.of();
        }
        try {
            Object node = objectMapper.readValue(raw, Object.class);
            if (node instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return out;
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> payloadMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.get("output") instanceof Map<?, ?> m) {
            return toStringMap(m);
        }
        if (raw.get("list") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            return toStringMap(m);
        }
        if (raw.get("output1") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            return toStringMap(m);
        }
        if (raw.get("atn_stk_infr") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            return toStringMap(m);
        }
        if (raw.get("stk_infr") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            return toStringMap(m);
        }
        return raw;
    }

    private List<Map<String, Object>> payloadList(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        for (String key : List.of(
                "chart",
                "list",
                "output",
                "output1",
                "output2",
                "stk_dt_pole_chart_qry",
                "stk_mn_pole_chart_qry",
                "stk_min_pole_chart_qry",
                "data")) {
            Object candidate = raw.get(key);
            if (candidate instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object row : list) {
                    if (row instanceof Map<?, ?> m) {
                        out.add(toStringMap(m));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        return List.of();
    }

    private Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private MarketAssetMetaDto assetMeta(AssetUniverseEntity asset) {
        return new MarketAssetMetaDto(
                asset == null ? null : asset.getAssetCode(),
                asset == null ? null : asset.getAssetName(),
                asset == null ? null : asset.getCountry(),
                asset == null ? null : asset.getTheme(),
                asset == null || asset.getAssetType() == null ? null : asset.getAssetType().name());
    }

    private BigDecimal num(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object raw = map.get(key);
            if (raw == null) {
                continue;
            }
            if (raw instanceof Number n) {
                return BigDecimal.valueOf(n.doubleValue());
            }
            String s = String.valueOf(raw).trim()
                    .replace(",", "")
                    .replace("%", "")
                    .replace("+", "");
            if (blank(s) || "-".equals(s)) {
                continue;
            }
            try {
                return new BigDecimal(s);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private OffsetDateTime dt(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String raw = str(map.get(key));
            if (blank(raw)) {
                continue;
            }
            String digits = raw.replaceAll("[^0-9]", "");
            try {
                if (digits.length() == 14) {
                    return LocalDateTime.parse(digits, KST14).atZone(KST).withZoneSameInstant(UTC).toOffsetDateTime();
                }
                if (digits.length() == 8) {
                    return LocalDate.parse(digits, YMD).atStartOfDay(KST).withZoneSameInstant(UTC).toOffsetDateTime();
                }
                if (digits.length() == 6) {
                    LocalDateTime local = LocalDateTime.of(LocalDate.now(KST), LocalDateTime.parse(digits, HMS).toLocalTime());
                    return local.atZone(KST).withZoneSameInstant(UTC).toOffsetDateTime();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, Object> meta(int requested, int target, int skipped, int failed, boolean degraded) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requested_count", requested);
        meta.put("target_count", target);
        meta.put("skipped_count", skipped);
        meta.put("failed_count", failed);
        meta.put("degraded", degraded);
        return meta;
    }

    private String joinUrl(String base, String path) {
        String b = safe(base, "https://api.kiwoom.com");
        String p = safe(path, "");
        if (blank(p)) {
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

    private String safeSnippet(String raw) {
        if (blank(raw)) {
            return "{}";
        }
        return truncate(raw.replaceAll("\\s+", " ").trim(), 2000);
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
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        int safeLen = Math.max(1, maxLength);
        return value.length() <= safeLen ? value : value.substring(0, safeLen);
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

    private record AuthToken(
            boolean ok,
            String token,
            int httpStatus,
            String errorCode,
            String errorMessage,
            String rawBody) {
        private static AuthToken ok(String token) {
            return new AuthToken(true, token, 200, null, null, "");
        }

        private static AuthToken fail(int httpStatus, String errorCode, String errorMessage, String rawBody) {
            return new AuthToken(false, null, httpStatus, errorCode, errorMessage, rawBody);
        }
    }

    private record ApiResult(
            boolean ok,
            int httpStatus,
            String rawBody,
            Map<String, Object> mapBody,
            String errorCode,
            String errorMessage) {
        private static ApiResult ok(int httpStatus, String rawBody, Map<String, Object> mapBody) {
            return new ApiResult(true, httpStatus, rawBody, mapBody, null, null);
        }

        private static ApiResult fail(int httpStatus, String rawBody, String errorCode, String errorMessage) {
            return new ApiResult(false, httpStatus, rawBody, Map.of(), errorCode, errorMessage);
        }
    }
}

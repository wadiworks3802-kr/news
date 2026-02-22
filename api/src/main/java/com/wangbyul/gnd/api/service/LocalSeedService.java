package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.config.LocalSeedProperties;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AssetSelectionSourceType;
import com.wangbyul.gnd.core.domain.AssetVerificationStatusType;
import com.wangbyul.gnd.core.domain.CategoryType;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.domain.SourceGrade;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.repository.SourceRepository;
import com.wangbyul.gnd.core.util.HashUtils;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
/**
 * 로컬 환경 데이터 부트스트랩 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 주요 역할:
 * - 기본 소스(국가별 무료 RSS) 등록
 * - 레거시 샘플 소스 비활성화
 * - 로컬 샘플 기사 정리/생성
 * - URL 컬럼 길이 보정(로컬 H2 호환)
 */
public class LocalSeedService {

    private final SourceRepository sourceRepository;
    private final NewsRepository newsRepository;
    private final AssetUniverseRepository assetUniverseRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final LocalSeedProperties seedProperties;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    public LocalSeedService(
            SourceRepository sourceRepository,
            NewsRepository newsRepository,
            AssetUniverseRepository assetUniverseRepository,
            MarketPriceBarRepository marketPriceBarRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            LocalSeedProperties seedProperties,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate) {
        this.sourceRepository = sourceRepository;
        this.newsRepository = newsRepository;
        this.assetUniverseRepository = assetUniverseRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.seedProperties = seedProperties;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void seedIfNeeded() {
        // 전체 시드 기능이 꺼져 있으면 아무 작업도 하지 않음
        if (!seedProperties.isEnabled()) {
            return;
        }

        // reset-on-start=true일 때만 완전 초기화
        if (seedProperties.isResetOnStart()) {
            newsRepository.deleteAllInBatch();
            sourceRepository.deleteAllInBatch();
        }

        // Google RSS 긴 URL 대응을 위해 로컬 컬럼 길이 보정
        widenNewsUrlColumnsIfNeeded();
        // 국가별 기본 소스 등록/갱신
        Map<String, SourceEntity> defaultSources = ensureDefaultSources();
        // 전략정책 검증용 자산/시장 시계열 기본 데이터 보장
        ensureAssetMarketSeed();
        // 과거 seed-src-* 소스는 실수 수집 방지를 위해 비활성화
        disableLegacySeedSources();

        // 기존 로컬 샘플 기사 정리
        long deleted = purgeLocalSampleRows();
        if (deleted > 0) {
            log.info("Purged local sample rows={}", deleted);
        }

        // 샘플 기사 생성이 꺼져 있으면 소스만 준비하고 종료
        if (!seedProperties.isSampleNewsEnabled()) {
            return;
        }

        if (newsRepository.count() > 0) {
            return;
        }

        SourceEntity krSource = defaultSources.get("KR");
        SourceEntity usSource = defaultSources.get("US");
        if (krSource == null || usSource == null) {
            return;
        }

        int perCategory = Math.max(1, seedProperties.getItemsPerCategory());
        int index = 0;
        for (CategoryType category : CategoryType.values()) {
            for (int i = 1; i <= perCategory; i++) {
                index++;
                SourceEntity source = (index % 2 == 0) ? usSource : krSource;
                String country = (index % 2 == 0) ? "US" : "KR";
                newsRepository.save(buildSeedNews(source, country, category, i, index));
            }
        }
    }

    private long purgeLocalSampleRows() {
        return entityManager.createQuery(
                        "delete from NewsEntity n where n.url like :seedPrefix or n.url like :ingestPrefix or n.source.sid like :seedSidPrefix")
                .setParameter("seedPrefix", "https://local.seed/%")
                .setParameter("ingestPrefix", "https://local.ingest/%")
                .setParameter("seedSidPrefix", "seed-src-%")
                .executeUpdate();
    }

    private void disableLegacySeedSources() {
        entityManager.createQuery("update SourceEntity s set s.allowFetch = false where s.sid like :seedSidPrefix")
                .setParameter("seedSidPrefix", "seed-src-%")
                .executeUpdate();
    }

    /**
     * H2 로컬 환경에서 URL 길이 제약으로 저장 실패가 나는 문제를 피하기 위해
     * url/url_norm 컬럼 길이를 넉넉하게 확장한다.
     */
    private void widenNewsUrlColumnsIfNeeded() {
        try {
            jdbcTemplate.execute("ALTER TABLE news ALTER COLUMN url VARCHAR(2048)");
            jdbcTemplate.execute("ALTER TABLE news ALTER COLUMN url_norm VARCHAR(2048)");
        } catch (Exception ignored) {
            // 이미 확장된 경우 등은 무시
        }
    }

    /**
     * 프로젝트 목표 국가 10개에 대한 무료 RSS 소스를 로컬 기본값으로 보장한다.
     */
    private Map<String, SourceEntity> ensureDefaultSources() {
        List<SourceSpec> specs = List.of(
                new SourceSpec("rss-google-kr-p1", "KR", SourceGrade.P1, 10, "https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko"),
                new SourceSpec("rss-google-us-p1", "US", SourceGrade.P1, 20, "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"),
                new SourceSpec("rss-google-uk-p1", "UK", SourceGrade.P1, 30, "https://news.google.com/rss?hl=en-GB&gl=GB&ceid=GB:en"),
                new SourceSpec("rss-google-de-p1", "DE", SourceGrade.P1, 40, "https://news.google.com/rss?hl=de&gl=DE&ceid=DE:de"),
                new SourceSpec("rss-google-fr-p1", "FR", SourceGrade.P1, 50, "https://news.google.com/rss?hl=fr&gl=FR&ceid=FR:fr"),
                new SourceSpec("rss-google-jp-p1", "JP", SourceGrade.P1, 60, "https://news.google.com/rss?hl=ja&gl=JP&ceid=JP:ja"),
                new SourceSpec("rss-google-cn-p1", "CN", SourceGrade.P1, 70, "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans"),
                new SourceSpec("rss-google-in-p1", "IN", SourceGrade.P1, 80, "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"),
                new SourceSpec("rss-google-ru-p1", "RU", SourceGrade.P1, 90, "https://news.google.com/rss?hl=ru&gl=RU&ceid=RU:ru"),
                new SourceSpec("rss-google-br-p1", "BR", SourceGrade.P1, 100, "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419"));

        return specs.stream()
                .map(spec -> upsertSource(
                        spec.sid(),
                        spec.country(),
                        spec.grade(),
                        spec.priority(),
                        spec.endpoint(),
                        "rss-public"))
                .collect(java.util.stream.Collectors.toMap(SourceEntity::getCountry, source -> source, (a, b) -> a));
    }

    private SourceEntity upsertSource(
            String sid,
            String country,
            SourceGrade grade,
            int priority,
            String endpoint,
            String licensePolicy) {
        // 있으면 갱신, 없으면 생성
        SourceEntity source = sourceRepository.findById(sid).orElseGet(SourceEntity::new);
        source.setSid(sid);
        source.setCountry(country);
        source.setSourceGrade(grade);
        source.setPriority(priority);
        source.setAllowFetch(true);
        source.setAllowStoreRaw(grade == SourceGrade.P0);
        source.setAllowStoreDerived(true);
        source.setCacheTtlSeconds(7200);
        source.setLicensePolicy(licensePolicy);
        source.setRobotsPolicy("allow");
        source.setEndpointUrl(endpoint);
        return sourceRepository.save(source);
    }

    private void ensureAssetMarketSeed() {
        List<AssetSpec> assets = List.of(
                new AssetSpec("005930.KS", "Samsung Electronics", "KR", "Semiconductor", "Technology"),
                new AssetSpec("000660.KS", "SK Hynix", "KR", "Semiconductor", "Technology"),
                new AssetSpec("NVDA", "NVIDIA", "US", "AI", "Technology"),
                new AssetSpec("AAPL", "Apple", "US", "ConsumerTech", "Technology"),
                new AssetSpec("TSLA", "Tesla", "US", "EV", "Automotive"),
                new AssetSpec("7203.T", "Toyota", "JP", "EV", "Automotive"),
                new AssetSpec("SIE.DE", "Siemens", "DE", "Industrial", "Industry"),
                new AssetSpec("AIR.PA", "Airbus", "FR", "Aerospace", "Industry"),
                new AssetSpec("SHEL.L", "Shell", "UK", "Energy", "Energy"),
                new AssetSpec("0700.HK", "Tencent", "CN", "Internet", "Technology"),
                new AssetSpec("RELIANCE.NS", "Reliance", "IN", "Energy", "Conglomerate"),
                new AssetSpec("PETR4.SA", "Petrobras", "BR", "Energy", "OilGas"));

        for (AssetSpec spec : assets) {
            AssetUniverseEntity asset = assetUniverseRepository.findById(spec.assetCode()).orElseGet(AssetUniverseEntity::new);
            asset.setAssetCode(spec.assetCode());
            asset.setAssetName(spec.assetName());
            asset.setCountry(spec.country());
            asset.setTheme(spec.theme());
            asset.setSector(spec.sector());
            asset.setAssetType(AssetType.STOCK);
            asset.setActive(true);
            if (asset.getLiquidityScore() == null) {
                asset.setLiquidityScore(BigDecimal.valueOf(0.55d));
            }
            if (asset.getSelectionSource() == null) {
                asset.setSelectionSource(AssetSelectionSourceType.MANUAL);
            }
            if (asset.getSelectionScore() == null) {
                asset.setSelectionScore(BigDecimal.ZERO);
            }
            if (asset.getDisplayWeight() == null) {
                asset.setDisplayWeight(0);
            }
            if (asset.getVerificationStatus() == null) {
                asset.setVerificationStatus(AssetVerificationStatusType.UNVERIFIED);
            }
            assetUniverseRepository.save(asset);

            ensurePriceBars(asset);
            ensureQuoteSnapshot(asset);
        }
    }

    private void ensurePriceBars(AssetUniverseEntity asset) {
        if (!marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(asset.getAssetCode(), "D1").isEmpty()
                && !marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(asset.getAssetCode(), "H1").isEmpty()) {
            return;
        }

        BigDecimal basePrice = BigDecimal.valueOf(50 + Math.abs(asset.getAssetCode().hashCode() % 200));
        List<MarketPriceBarEntity> bars = new ArrayList<>();
        for (int i = 120; i >= 1; i--) {
            bars.add(buildBar(asset.getAssetCode(), "D1", OffsetDateTime.now().minusDays(i), basePrice, i, 0.015d));
        }
        for (int i = 180; i >= 1; i--) {
            bars.add(buildBar(asset.getAssetCode(), "H1", OffsetDateTime.now().minusHours(i), basePrice, i, 0.006d));
        }
        marketPriceBarRepository.saveAll(bars);
    }

    private MarketPriceBarEntity buildBar(
            String assetCode,
            String timeframe,
            OffsetDateTime barTime,
            BigDecimal basePrice,
            int step,
            double noiseScale) {
        double wave = Math.sin(step / 6.0d) * noiseScale;
        BigDecimal open = basePrice.multiply(BigDecimal.valueOf(1d + wave));
        BigDecimal close = open.multiply(BigDecimal.valueOf(1d + (Math.cos(step / 5.0d) * noiseScale)));
        BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.004d));
        BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.996d));
        BigDecimal volume = BigDecimal.valueOf(100000d + (Math.abs(step * 173) % 50000));

        MarketPriceBarEntity bar = new MarketPriceBarEntity();
        bar.setAssetCode(assetCode);
        bar.setTimeframe(timeframe);
        bar.setBarTime(barTime);
        bar.setOpenPrice(open.setScale(6, RoundingMode.HALF_UP));
        bar.setClosePrice(close.setScale(6, RoundingMode.HALF_UP));
        bar.setHighPrice(high.setScale(6, RoundingMode.HALF_UP));
        bar.setLowPrice(low.setScale(6, RoundingMode.HALF_UP));
        bar.setVolume(volume.setScale(4, RoundingMode.HALF_UP));
        bar.setProviderName("local-mock");
        return bar;
    }

    private void ensureQuoteSnapshot(AssetUniverseEntity asset) {
        if (marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(asset.getAssetCode()).isPresent()) {
            return;
        }
        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(asset.getAssetCode(), "H1");
        if (bars.size() < 2) {
            return;
        }
        BigDecimal latest = bars.get(0).getClosePrice();
        BigDecimal prev = bars.get(1).getClosePrice();
        BigDecimal changePct = latest.subtract(prev)
                .divide(prev.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : prev, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100d));

        MarketQuoteSnapshotEntity snapshot = new MarketQuoteSnapshotEntity();
        snapshot.setAssetCode(asset.getAssetCode());
        snapshot.setSnapshotUtc(OffsetDateTime.now());
        snapshot.setLastPrice(latest);
        snapshot.setChangePct(changePct);
        snapshot.setBidPrice(latest.multiply(BigDecimal.valueOf(0.999d)).setScale(6, RoundingMode.HALF_UP));
        snapshot.setAskPrice(latest.multiply(BigDecimal.valueOf(1.001d)).setScale(6, RoundingMode.HALF_UP));
        snapshot.setBidSize(BigDecimal.valueOf(1200d));
        snapshot.setAskSize(BigDecimal.valueOf(1180d));
        snapshot.setSpreadPct(BigDecimal.valueOf(0.2d));
        snapshot.setVolume(bars.get(0).getVolume());
        snapshot.setProviderName("local-mock");
        marketQuoteSnapshotRepository.save(snapshot);
    }

    private NewsEntity buildSeedNews(SourceEntity source, String country, CategoryType category, int itemNo, int sequence) {
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(sequence * 12L);
        String id = UUID.randomUUID().toString().replace("-", "");
        String url = "https://local.seed/news/" + category.name().toLowerCase() + "/" + itemNo + "-" + sequence;
        String titleKo = "%s 샘플 뉴스 %d".formatted(category.name(), itemNo);
        String summaryKo = "%s 카테고리 샘플 요약입니다. 로컬 시각화 검증을 위한 데이터입니다.".formatted(category.name());
        String bodyRaw = "Seeded content for %s item %d with traceable facts and timeline.".formatted(category.name(), itemNo);
        String hashInput = titleKo + "\n" + bodyRaw + "\n" + now;

        NewsEntity news = new NewsEntity();
        news.setId(id);
        news.setSource(source);
        news.setCountry(country);
        news.setLang("ko");
        news.setCategory(category);
        news.setUrl(url);
        news.setUrlNorm(url);
        news.setTitleRaw(titleKo);
        news.setBodyRaw(bodyRaw);
        news.setPubUtc(now);
        news.setFetchUtc(now.plusSeconds(30));
        news.setLicense("seed-license");
        news.setRobots(true);
        news.setTtl(172800);
        news.setTitleKo(titleKo);
        news.setSummaryKo(summaryKo);
        news.setEvidenceSpans("[\"when:2026-02-20\",\"who:seed-bot\",\"what:sample\",\"impact:ui-check\"]");
        news.setContentHash(HashUtils.sha256(hashInput));
        news.setSimhash64(HashUtils.simHash64(titleKo + " " + bodyRaw + " " + sequence));
        news.setDedupGroupId(id);
        news.setTrustScore(BigDecimal.valueOf(0.50 + ((sequence % 10) * 0.04)).setScale(4, RoundingMode.HALF_UP));
        return news;
    }

    private record SourceSpec(
            String sid,
            String country,
            SourceGrade grade,
            int priority,
            String endpoint) {
    }

    private record AssetSpec(
            String assetCode,
            String assetName,
            String country,
            String theme,
            String sector) {
    }
}

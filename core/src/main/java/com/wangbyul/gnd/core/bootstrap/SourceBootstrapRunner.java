package com.wangbyul.gnd.core.bootstrap;

import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.domain.SourceGrade;
import com.wangbyul.gnd.core.repository.SourceRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * app.seed.enabled=false 환경에서도 기본 뉴스 수집 소스를 보장한다.
 */
@Slf4j
@Component
public class SourceBootstrapRunner implements ApplicationRunner {

    private final SourceRepository sourceRepository;
    private final boolean enabled;
    private final boolean forceUpdate;
    private final int cacheTtlSeconds;

    public SourceBootstrapRunner(
            SourceRepository sourceRepository,
            @Value("${app.news.source-bootstrap.enabled:true}") boolean enabled,
            @Value("${app.news.source-bootstrap.force-update:false}") boolean forceUpdate,
            @Value("${app.news.source-bootstrap.cache-ttl-seconds:7200}") int cacheTtlSeconds) {
        this.sourceRepository = sourceRepository;
        this.enabled = enabled;
        this.forceUpdate = forceUpdate;
        this.cacheTtlSeconds = Math.max(300, cacheTtlSeconds);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("source bootstrap disabled");
            return;
        }

        List<SourceSpec> specs = defaultSpecs();
        int inserted = 0;
        int updated = 0;
        for (SourceSpec spec : specs) {
            SourceEntity source = sourceRepository.findById(spec.sid()).orElseGet(SourceEntity::new);
            boolean created = source.getSid() == null;
            if (created) {
                source.setSid(spec.sid());
            }

            if (created || forceUpdate || blank(source.getCountry())) {
                source.setCountry(spec.country());
            }
            if (created || forceUpdate || source.getSourceGrade() == null) {
                source.setSourceGrade(SourceGrade.P1);
            }
            if (created || forceUpdate || source.getPriority() == null) {
                source.setPriority(spec.priority());
            }
            source.setAllowFetch(true);
            source.setAllowStoreRaw(false);
            source.setAllowStoreDerived(true);
            source.setCacheTtlSeconds(cacheTtlSeconds);
            if (created || forceUpdate || blank(source.getLicensePolicy())) {
                source.setLicensePolicy("rss-public");
            }
            if (created || forceUpdate || blank(source.getRobotsPolicy())) {
                source.setRobotsPolicy("allow");
            }
            if (created || forceUpdate || blank(source.getEndpointUrl())) {
                source.setEndpointUrl(spec.endpointUrl());
            }

            sourceRepository.save(source);
            if (created) {
                inserted++;
            } else {
                updated++;
            }
        }
        log.info("source bootstrap completed inserted={} updated={} total_specs={}", inserted, updated, specs.size());
    }

    private List<SourceSpec> defaultSpecs() {
        return List.of(
                new SourceSpec("rss-google-kr-p1", "KR", 10, "https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko"),
                new SourceSpec("rss-google-us-p1", "US", 20, "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"),
                new SourceSpec("rss-google-uk-p1", "UK", 30, "https://news.google.com/rss?hl=en-GB&gl=GB&ceid=GB:en"),
                new SourceSpec("rss-google-de-p1", "DE", 40, "https://news.google.com/rss?hl=de&gl=DE&ceid=DE:de"),
                new SourceSpec("rss-google-fr-p1", "FR", 50, "https://news.google.com/rss?hl=fr&gl=FR&ceid=FR:fr"),
                new SourceSpec("rss-google-jp-p1", "JP", 60, "https://news.google.com/rss?hl=ja&gl=JP&ceid=JP:ja"),
                new SourceSpec("rss-google-cn-p1", "CN", 70, "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans"),
                new SourceSpec("rss-google-in-p1", "IN", 80, "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"),
                new SourceSpec("rss-google-ru-p1", "RU", 90, "https://news.google.com/rss?hl=ru&gl=RU&ceid=RU:ru"),
                new SourceSpec("rss-google-br-p1", "BR", 100, "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419"));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record SourceSpec(String sid, String country, int priority, String endpointUrl) {
    }
}

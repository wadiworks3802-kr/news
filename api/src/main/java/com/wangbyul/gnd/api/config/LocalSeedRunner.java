package com.wangbyul.gnd.api.config;

import com.wangbyul.gnd.api.service.LocalSeedService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
/**
 * LocalSeedRunner 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Component
@Profile("local")
public class LocalSeedRunner implements ApplicationRunner {

    private final LocalSeedService localSeedService;

    public LocalSeedRunner(LocalSeedService localSeedService) {
        this.localSeedService = localSeedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        localSeedService.seedIfNeeded();
    }
}

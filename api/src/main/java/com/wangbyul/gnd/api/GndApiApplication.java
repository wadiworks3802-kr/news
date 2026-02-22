package com.wangbyul.gnd.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
/**
 * GndApiApplication 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@SpringBootApplication(scanBasePackages = "com.wangbyul.gnd")
@EnableJpaRepositories(basePackages = "com.wangbyul.gnd.core.repository")
@EntityScan(basePackages = "com.wangbyul.gnd.core.domain")
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.wangbyul.gnd.api.config")
public class GndApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(GndApiApplication.class, args);
    }
}

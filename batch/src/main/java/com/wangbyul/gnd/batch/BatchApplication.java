package com.wangbyul.gnd.batch;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
/**
 * BatchApplication 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@SpringBootApplication(scanBasePackages = "com.wangbyul.gnd")
@EnableJpaRepositories(basePackages = "com.wangbyul.gnd.core.repository")
@EntityScan(basePackages = "com.wangbyul.gnd.core.domain")
public class BatchApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(BatchApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}

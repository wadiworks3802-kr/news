package com.wangbyul.gnd.batch.config;

import com.wangbyul.gnd.batch.job.ApiResponseAuditCleanupJob;
import com.wangbyul.gnd.batch.job.CleanupJob;
import com.wangbyul.gnd.batch.job.DataQualitySummaryJob;
import com.wangbyul.gnd.batch.job.FetchJob;
import com.wangbyul.gnd.batch.job.MarketDataGapDetectionJob;
import com.wangbyul.gnd.batch.job.MarketDataQualityAuditJob;
import com.wangbyul.gnd.batch.job.NlpJob;
import com.wangbyul.gnd.batch.job.TickerAliasVerificationJob;
import com.wangbyul.gnd.batch.job.UniverseRebuildJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * BatchQuartzConfig 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Configuration
public class BatchQuartzConfig {

    /**
     * 관련 뉴스 수집 주기(분).
     * 로컬 운영 기본값은 1분으로 두고, 성능 이슈가 있으면 설정으로 상향 조정한다.
     */
    @Value("${app.batch.fetch-interval-minutes:1}")
    private int fetchIntervalMinutes;

    @Value("${app.batch.market-quality-audit-interval-minutes:10}")
    private int marketQualityAuditIntervalMinutes;

    @Value("${app.batch.market-gap-detection-interval-minutes:5}")
    private int marketGapDetectionIntervalMinutes;

    @Value("${app.batch.universe-rebuild-cron:0 10 0 * * ?}")
    private String universeRebuildCron;

    @Value("${app.batch.ticker-alias-verification-cron:0 25 0 * * ?}")
    private String tickerAliasVerificationCron;

    private int safeFetchIntervalMinutes() {
        return Math.max(1, fetchIntervalMinutes);
    }

    private int safeMarketQualityAuditIntervalMinutes() {
        return Math.max(5, marketQualityAuditIntervalMinutes);
    }

    private int safeMarketGapDetectionIntervalMinutes() {
        return Math.max(1, marketGapDetectionIntervalMinutes);
    }

    @Bean
    public JobDetail fetchP0JobDetail() {
        return JobBuilder.newJob(FetchJob.class)
                .withIdentity("fetchP0Job")
                .usingJobData("grade", "P0")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger fetchP0Trigger(JobDetail fetchP0JobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(fetchP0JobDetail)
                .withIdentity("fetchP0Trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(safeFetchIntervalMinutes())
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail fetchP1JobDetail() {
        return JobBuilder.newJob(FetchJob.class)
                .withIdentity("fetchP1Job")
                .usingJobData("grade", "P1")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger fetchP1Trigger(JobDetail fetchP1JobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(fetchP1JobDetail)
                .withIdentity("fetchP1Trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(safeFetchIntervalMinutes())
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail fetchP23JobDetail() {
        return JobBuilder.newJob(FetchJob.class)
                .withIdentity("fetchP23Job")
                .usingJobData("grade", "P2")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger fetchP23Trigger(JobDetail fetchP23JobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(fetchP23JobDetail)
                .withIdentity("fetchP23Trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(safeFetchIntervalMinutes())
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail nlpJobDetail() {
        return JobBuilder.newJob(NlpJob.class)
                .withIdentity("nlpJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger nlpTrigger(JobDetail nlpJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(nlpJobDetail)
                .withIdentity("nlpTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMinutes(2).repeatForever())
                .build();
    }

    @Bean
    public JobDetail cleanupJobDetail() {
        return JobBuilder.newJob(CleanupJob.class)
                .withIdentity("cleanupJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger cleanupTrigger(JobDetail cleanupJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(cleanupJobDetail)
                .withIdentity("cleanupTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInHours(1).repeatForever())
                .build();
    }

    @Bean
    public JobDetail marketDataQualityAuditJobDetail() {
        return JobBuilder.newJob(MarketDataQualityAuditJob.class)
                .withIdentity("marketDataQualityAuditJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger marketDataQualityAuditTrigger(JobDetail marketDataQualityAuditJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(marketDataQualityAuditJobDetail)
                .withIdentity("marketDataQualityAuditTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(safeMarketQualityAuditIntervalMinutes())
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail marketDataGapDetectionJobDetail() {
        return JobBuilder.newJob(MarketDataGapDetectionJob.class)
                .withIdentity("marketDataGapDetectionJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger marketDataGapDetectionTrigger(JobDetail marketDataGapDetectionJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(marketDataGapDetectionJobDetail)
                .withIdentity("marketDataGapDetectionTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(safeMarketGapDetectionIntervalMinutes())
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail apiResponseAuditCleanupJobDetail() {
        return JobBuilder.newJob(ApiResponseAuditCleanupJob.class)
                .withIdentity("apiResponseAuditCleanupJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger apiResponseAuditCleanupTrigger(JobDetail apiResponseAuditCleanupJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(apiResponseAuditCleanupJobDetail)
                .withIdentity("apiResponseAuditCleanupTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 15 0 * * ?"))
                .build();
    }

    @Bean
    public JobDetail dataQualitySummaryJobDetail() {
        return JobBuilder.newJob(DataQualitySummaryJob.class)
                .withIdentity("dataQualitySummaryJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger dataQualitySummaryTrigger(JobDetail dataQualitySummaryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(dataQualitySummaryJobDetail)
                .withIdentity("dataQualitySummaryTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 30 0 * * ?"))
                .build();
    }

    @Bean
    public JobDetail universeRebuildJobDetail() {
        return JobBuilder.newJob(UniverseRebuildJob.class)
                .withIdentity("universeRebuildJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger universeRebuildTrigger(JobDetail universeRebuildJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(universeRebuildJobDetail)
                .withIdentity("universeRebuildTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(universeRebuildCron))
                .build();
    }

    @Bean
    public JobDetail tickerAliasVerificationJobDetail() {
        return JobBuilder.newJob(TickerAliasVerificationJob.class)
                .withIdentity("tickerAliasVerificationJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger tickerAliasVerificationTrigger(JobDetail tickerAliasVerificationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(tickerAliasVerificationJobDetail)
                .withIdentity("tickerAliasVerificationTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(tickerAliasVerificationCron))
                .build();
    }
}

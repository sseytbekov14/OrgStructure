package cs_orgchart.ad.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.sync.AdSyncScheduler;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.AdSyncStartup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * What runs the AD sync by itself: the schedule (AD_SYNC_CRON) and the startup tasks (AD_SYNC_ON_STARTUP,
 * AD_SYNC_STARTUP_DRY_RUN). Only with AD_SYNC_MODE set and AD_SYNC_ENABLED=true: otherwise nothing contacts AD by
 * itself and scheduling stays off, as before.
 */
@Configuration
@Conditional(AdSyncModeCondition.class)
@ConditionalOnProperty(name = "ad.sync.enabled", havingValue = "true")
@EnableScheduling
public class AdSyncSchedulingConfig {

    @Bean
    public AdSyncScheduler adSyncScheduler(AdSyncService service, AdLdapProperties properties) {
        return new AdSyncScheduler(service, properties.getSync().getCron());
    }

    @Bean(destroyMethod = "shutdown")
    public AdSyncStartup adSyncStartup(AdSyncService service, ObjectMapper json, AdLdapProperties properties) {
        AdLdapProperties.Sync sync = properties.getSync();
        return new AdSyncStartup(service, json, sync.isOnStartup(), sync.isStartupDryRun(), sync.getReportPath());
    }
}

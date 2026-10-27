package cs_orgchart.ad.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;

/**
 * The scheduled AD sync (AD_SYNC_CRON, Spring cron with seconds; "-" disables it). Only with AD_SYNC_ENABLED=true.
 * An unusable cron expression is reported and the schedule left out: it never stops the application.
 */
@Slf4j
public class AdSyncScheduler implements SchedulingConfigurer {

    public static final String DISABLED = "-";

    private final AdSyncService service;
    private final String cron;

    public AdSyncScheduler(AdSyncService service, String cron) {
        this.service = service;
        this.cron = cron == null ? DISABLED : cron.trim();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (cron.isEmpty() || DISABLED.equals(cron)) {
            log.info("AD sync: no schedule (AD_SYNC_CRON={})", cron);
            return;
        }
        if (!CronExpression.isValidExpression(cron)) {
            log.error("AD sync: AD_SYNC_CRON '{}' is not a Spring cron expression (6 fields, e.g. 0 */30 * * * *);"
                    + " no scheduled sync", cron);
            return;
        }
        registrar.addCronTask(this::runScheduled, cron);
        log.info("AD sync scheduled: {}", cron);
    }

    void runScheduled() {
        try {
            service.sync(SyncTrigger.SCHEDULED);
        } catch (RuntimeException e) {
            log.error("AD sync (SCHEDULED) could not run", e);
        }
    }
}

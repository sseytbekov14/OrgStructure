package cs_orgchart.ad.health;

import cs_orgchart.ad.sync.AdSyncLog;
import cs_orgchart.ad.sync.AdSyncLogRepository;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.ad.sync.SyncStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.List;
import java.util.Optional;

/**
 * The state of the AD sync in /actuator/health (component "adSync"). Never DOWN: the site serves the employees it
 * has when AD is unavailable, and CI checks /actuator/health for 200. STALE and DISABLED are not part of the
 * aggregated status and map to HTTP 200 (management.endpoint.health.status.http-mapping).
 * <ul>
 *   <li>DISABLED: AD_SYNC_ENABLED=false;</li>
 *   <li>UNKNOWN: no sync has finished yet, or the log cannot be read;</li>
 *   <li>UP: the last finished sync succeeded;</li>
 *   <li>STALE: the last finished sync failed, or runs are suspended after invalid credentials.</li>
 * </ul>
 */
public class AdSyncHealthIndicator implements HealthIndicator {

    public static final Status STALE = new Status("STALE", "The last AD sync did not succeed");
    public static final Status DISABLED = new Status("DISABLED", "AD_SYNC_ENABLED=false");

    private static final List<String> FINISHED = List.of(SyncStatus.SUCCESS.name(), SyncStatus.FAILED.name());

    private final AdSyncLogRepository syncLog;
    private final AuthCircuitBreaker breaker;
    private final boolean enabled;

    public AdSyncHealthIndicator(AdSyncLogRepository syncLog, AuthCircuitBreaker breaker, boolean enabled) {
        this.syncLog = syncLog;
        this.breaker = breaker;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.status(DISABLED).build();
        }
        try {
            Optional<AdSyncLog> last = syncLog.findFirstByStatusInOrderByIdDesc(FINISHED);
            Optional<AdSyncLog> lastSuccess = syncLog.findFirstByStatusOrderByIdDesc(SyncStatus.SUCCESS.name());
            boolean running = syncLog.findFirstByOrderByIdDesc()
                    .map(l -> SyncStatus.RUNNING.name().equals(l.getStatus())).orElse(false);
            Health.Builder health;
            if (breaker.isSuspended()) {
                health = Health.status(STALE).withDetail("suspended", "after invalid credentials (AUTH) until a"
                        + " manual sync or a restart");
            } else if (last.isEmpty()) {
                health = Health.unknown().withDetail("lastRun", "none");
            } else if (SyncStatus.SUCCESS.name().equals(last.get().getStatus())) {
                health = Health.up();
            } else {
                health = Health.status(STALE).withDetail("lastErrorCode", String.valueOf(last.get().getErrorCode()));
            }
            last.ifPresent(l -> health.withDetail("lastRunAt", l.getFinishedAt()).withDetail("lastStatus",
                    l.getStatus()));
            lastSuccess.ifPresent(l -> health.withDetail("lastSuccessAt", l.getFinishedAt())
                    .withDetail("employees", l.getFetchedCount()).withDetail("skipped", l.getSkippedCount()));
            return health.withDetail("running", running).build();
        } catch (RuntimeException e) {
            return Health.unknown().withDetail("error", "the AD sync log cannot be read: " + e.getClass()
                    .getSimpleName()).build();
        }
    }
}

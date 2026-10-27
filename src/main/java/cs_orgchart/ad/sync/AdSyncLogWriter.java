package cs_orgchart.ad.sync;

import cs_orgchart.ad.client.AdErrorCode;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * The ad_sync_log rows, each in its own transaction (REQUIRES_NEW): a rollback of the sync never takes its RUNNING,
 * FAILED or SKIPPED row with it.
 */
public class AdSyncLogWriter {

    private static final int MESSAGE_MAX = 4000;

    private final AdSyncLogRepository log;
    private final Clock clock;

    public AdSyncLogWriter(AdSyncLogRepository log, Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long running(SyncTrigger trigger) {
        return log.save(AdSyncLog.builder().startedAt(clock.instant()).status(SyncStatus.RUNNING.name())
                .triggerType(trigger.name()).build()).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(long id, SyncCounts counts) {
        AdSyncLog row = log.findById(id).orElseThrow();
        row.setStatus(SyncStatus.SUCCESS.name());
        row.setFinishedAt(clock.instant());
        row.setFetchedCount(counts.fetched());
        row.setSkippedCount(counts.skipped());
        row.setInsertedCount(counts.inserted());
        row.setUpdatedCount(counts.updated());
        row.setDisabledCount(counts.disabled());
        row.setExternalCount(counts.external());
        row.setUnmappedCount(counts.unmapped());
        row.setPmLocalpartCount(counts.pmLocalPart());
    }

    /**
     * @param plan the plan when the failure came after the fetch (SAFETY), for the fetched and skipped counts
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(long id, String errorCode, String message, SyncPlan plan) {
        AdSyncLog row = log.findById(id).orElseThrow();
        row.setStatus(SyncStatus.FAILED.name());
        row.setFinishedAt(clock.instant());
        row.setErrorCode(errorCode);
        row.setErrorMessage(truncate(message));
        if (plan != null) {
            row.setFetchedCount(plan.fetchedCount());
            row.setSkippedCount(plan.skippedCount());
            row.setUnmappedCount(plan.unmappedSegments().size());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long skipped(SyncTrigger trigger, String message) {
        return log.save(AdSyncLog.builder().startedAt(clock.instant()).finishedAt(clock.instant())
                .status(SyncStatus.SKIPPED.name()).triggerType(trigger.name()).errorMessage(message).build()).getId();
    }

    /**
     * RUNNING rows other than {@code currentId} → FAILED (INTERRUPTED). Only for a caller holding the sync lock:
     * then no other run is going on and every other RUNNING row was cut off.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int interrupted(Long currentId) {
        return log.failRunning(currentId == null ? -1L : currentId, AdErrorCode.INTERRUPTED.name(),
                "the run did not finish (the application stopped or crashed during the sync)", clock.instant());
    }

    private static String truncate(String message) {
        return message == null || message.length() <= MESSAGE_MAX ? message : message.substring(0, MESSAGE_MAX);
    }
}

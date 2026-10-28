package cs_orgchart.ad.sync;

/**
 * Published after every sync that ended SUCCESS, also when nothing changed (then {@link EmployeesSynced} is not
 * published): the photo phase runs after each successful sync, so that failed photo reads are retried and switching
 * AD_PHOTOS_ENABLED on takes effect at the next run.
 */
public record AdSyncSucceeded(long logId, SyncTrigger trigger, SyncCounts counts) {
}

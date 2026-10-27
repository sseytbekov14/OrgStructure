package cs_orgchart.ad.sync;

/**
 * {@code ad_sync_log.status} (spec v3 §6.2), and the outcome of a run that wrote no row (SUSPENDED).
 */
public enum SyncStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    /** Another sync held the lock: nothing done */
    SKIPPED,
    /** Not started: scheduled and startup runs are suspended after invalid credentials (no row is written) */
    SUSPENDED
}

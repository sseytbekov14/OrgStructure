package cs_orgchart.ad.sync;

/**
 * What a sync run did.
 *
 * @param logId     the ad_sync_log row; null when no row was written (SUSPENDED)
 * @param counts    null unless SUCCESS (FAILED after the fetch has fetched and skipped only)
 * @param errorCode the {@link cs_orgchart.ad.client.AdErrorCode} of a FAILED run
 */
public record SyncOutcome(SyncStatus status, Long logId, SyncCounts counts, String errorCode, String message) {
}

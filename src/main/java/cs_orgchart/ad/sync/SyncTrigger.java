package cs_orgchart.ad.sync;

/**
 * What started an AD sync run ({@code ad_sync_log.trigger_type}).
 */
public enum SyncTrigger {
    SCHEDULED,
    MANUAL,
    STARTUP
}

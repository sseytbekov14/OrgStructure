package cs_orgchart.ad.sync;

/**
 * Published after a sync committed changes to the employees (spec v3 §7 step 8): the employee directory and the
 * open pages are refreshed by its listeners (Phase 5).
 */
public record EmployeesSynced(long logId, SyncTrigger trigger, SyncCounts counts) {
}

package cs_orgchart.ad.sync;

/**
 * The counts of a sync run, as stored in ad_sync_log (spec v3 §4.4, §6.2).
 *
 * @param fetched     records that passed validation (what is upserted)
 * @param skipped     records dropped by the validation; fetched + skipped = the LDAP result size
 * @param inserted    new rows (employees and external managers/PMs)
 * @param updated     existing rows with an AD value or a link changed
 * @param disabled    employees no longer in AD (enabled = false, never deleted)
 * @param external    managers/PMs outside the fetched set, looked up in AD and linked
 * @param unmapped    distinct department segments the dictionaries do not know
 * @param pmLocalPart PMs found only by the local part of the e-mail
 */
public record SyncCounts(int fetched, int skipped, int inserted, int updated, int disabled, int external,
                         int unmapped, int pmLocalPart) {

    /** Whether the employees changed (then the directory has to be rebuilt) */
    public boolean changed() {
        return inserted + updated + disabled > 0;
    }
}

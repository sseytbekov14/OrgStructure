package cs_orgchart.ad.sync;

import cs_orgchart.ad.client.AdDirectoryException;
import cs_orgchart.ad.client.AdErrorCode;
import cs_orgchart.ad.mapper.AdEmployeeMapper.SkipReason;
import cs_orgchart.ad.model.AdRecord;
import cs_orgchart.ad.sync.SyncPlan.ManagerMatch;
import cs_orgchart.ad.sync.SyncPlan.PmMatch;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.override.EmployeeOverride;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AD sync of spec v3 §7 on the mock directory: what it writes, what it never writes, and every way it stops.
 */
@RecordApplicationEvents
class AdSyncServiceTest extends AdSyncTestSupport {

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private DataSource dataSource;

    // --- first and repeated sync ---------------------------------------------------------------------------------

    @Test
    void firstSyncIntoAnEmptyDatabasePassesTheSafetyCheckAndFillsIt() {
        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);

        assertThat(outcome.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(outcome.counts()).isEqualTo(new SyncCounts(41, 2, 42, 0, 0, 1, 0, 1));
        assertThat(employees.countByEnabledTrueAndExternalFalse()).isEqualTo(41);
        assertThat(employees.findAll()).filteredOn(e -> !e.isExternal())
                .allSatisfy(e -> assertThat(e.getCountryCode()).isIn("KZ", "UZ", "AZ", "GE", "AM", "KG"));
        assertThat(employees.findAll()).extracting(Employee::getLogin)
                .doesNotContain("tdev01", "bblank", "copilot-agent01", "ppearl", "qquince");

        AdSyncLog row = logRow(outcome.logId());
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getTriggerType()).isEqualTo("MANUAL");
        assertThat(row.getFinishedAt()).isNotNull();
        assertThat(row.getFetchedCount()).isEqualTo(41);
        assertThat(row.getSkippedCount()).isEqualTo(2);
        assertThat(row.getInsertedCount()).isEqualTo(42);
        assertThat(row.getUpdatedCount()).isZero();
        assertThat(row.getDisabledCount()).isZero();
        assertThat(row.getExternalCount()).isEqualTo(1);
        assertThat(row.getUnmappedCount()).isZero();
        assertThat(row.getPmLocalpartCount()).isEqualTo(1);
        assertThat(row.getErrorCode()).isNull();
    }

    @Test
    void departmentsAreNormalizedAndLinksResolved() {
        service.sync(SyncTrigger.MANUAL);

        Employee tenders = employee("ggold");
        assertThat(List.of(tenders.getDeptLevel1(), tenders.getDeptLevel2(), tenders.getDeptLevel3()))
                .containsExactly("Central Services", "Facilities & Operations", "Proposals & Tenders");
        assertThat(tenders.getDepartment()).isEqualTo("CS/F&O/Facilities & Operations/Proposals & Tenders");
        assertThat(employee("bblue").getManagerId()).isEqualTo(employee("aamber").getId());
        assertThat(employee("eebony").getManagerId()).isEqualTo(employee("ccoral").getId());
        assertThat(employee("eebony").getPmId()).isEqualTo(employee("ddenim").getId());
        // PM by e-mail (the PM attribute is the mail, not the UPN) and by the local part only (other domain)
        assertThat(employee("aazure").getPmId()).isEqualTo(employee("yyellow").getId());
        assertThat(employee("bbronze").getPmId()).isEqualTo(employee("yyellow").getId());
    }

    @Test
    void peopleWithoutManagerAndPmAreRootsNotErrors() {
        service.sync(SyncTrigger.MANUAL);

        for (String login : List.of("kkhaki", "ssand", "wwheat", "nnickel")) {
            Employee root = employee(login);
            assertThat(root.getManagerDn()).isNull();
            assertThat(root.getManagerId()).isNull();
            assertThat(root.getPmUpn()).isNull();
            assertThat(root.getPmId()).isNull();
        }
    }

    @Test
    void managerOutsideCentralServicesIsSavedAsExternalAndLinked() {
        service.sync(SyncTrigger.MANUAL);

        Employee partner = employee("rrust");
        assertThat(partner.isExternal()).isTrue();
        assertThat(partner.getCountryCode()).isNull();
        assertThat(partner.getDeptLevel1()).isNull();
        assertThat(partner.getDepartment()).startsWith("Advisory");
        for (String head : List.of("aamber", "qquartz", "tteal", "xxanadu", "ccyan", "ggarnet")) {
            assertThat(employee(head).getManagerId()).as(head).isEqualTo(partner.getId());
            assertThat(employee(head).getPmId()).as(head).isEqualTo(partner.getId());
        }
        // The external is looked up once per run, not per employee
        assertThat(directory.lookupCalls).hasValue(1);
    }

    @Test
    void secondRunInsertsNothingAndUpdatesNothing() {
        service.sync(SyncTrigger.MANUAL);
        events.clear();

        SyncOutcome second = service.sync(SyncTrigger.SCHEDULED);

        assertThat(second.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(second.counts()).isEqualTo(new SyncCounts(41, 2, 0, 0, 0, 1, 0, 1));
        assertThat(events.stream(EmployeesSynced.class)).as("nothing changed: no event").isEmpty();
    }

    @Test
    void onlyRealChangesAreUpdates() {
        service.sync(SyncTrigger.MANUAL);
        directory.change("mmint", "title", "Senior System Administrator");
        directory.change("oolive", "manager", directory.byLogin("kkhaki").get("distinguishedName"));

        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);

        assertThat(outcome.counts().inserted()).isZero();
        assertThat(outcome.counts().updated()).isEqualTo(2);
        assertThat(employee("mmint").getJobTitle()).isEqualTo("Senior System Administrator");
        assertThat(employee("oolive").getManagerId()).isEqualTo(employee("kkhaki").getId());
        assertThat(employee("mmint").getUpdatedAt()).isAfter(employee("mmint").getCreatedAt());
        assertThat(employee("nnavy").getUpdatedAt()).isEqualTo(employee("nnavy").getCreatedAt());
    }

    @Test
    void eventIsPublishedAfterAChangingSync() {
        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);

        assertThat(events.stream(EmployeesSynced.class)).singleElement().satisfies(e -> {
            assertThat(e.logId()).isEqualTo(outcome.logId());
            assertThat(e.trigger()).isEqualTo(SyncTrigger.MANUAL);
            assertThat(e.counts()).isEqualTo(outcome.counts());
        });
    }

    // --- leavers, skips, safety ----------------------------------------------------------------------------------

    @Test
    void leaverIsDisabledNeverDeletedAndComesBack() {
        service.sync(SyncTrigger.MANUAL);
        long rows = count("employees");
        directory.remove("mmauve");

        SyncOutcome left = service.sync(SyncTrigger.MANUAL);

        assertThat(left.counts().disabled()).isEqualTo(1);
        assertThat(count("employees")).isEqualTo(rows);
        assertThat(employee("mmauve").isEnabled()).isFalse();

        directory.reset();
        SyncOutcome back = service.sync(SyncTrigger.MANUAL);
        assertThat(back.counts().updated()).isEqualTo(1);
        assertThat(employee("mmauve").isEnabled()).isTrue();
    }

    @Test
    void invalidRecordsNeverFailTheSyncAndAreCountedPerReason() {
        List<AdRecord> records = new ArrayList<>(directory.fetched());
        records.add(withoutAttribute(directory.byLogin("nnavy"), "objectGUID", "bad-guid"));
        records.add(withoutAttribute(directory.byLogin("nnavy"), "userPrincipalName", "bad-upn"));
        directory.setFetched(records);

        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);

        assertThat(outcome.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(outcome.counts().fetched()).isEqualTo(41);
        assertThat(outcome.counts().skipped()).isEqualTo(4);
        assertThat(logRow(outcome.logId()).getSkippedCount()).isEqualTo(4);
    }

    @Test
    void safetyCheckCountsValidRecordsOnlyAndLeavesTheDatabaseUnchanged() {
        service.sync(SyncTrigger.MANUAL);
        // 45 entries from AD (more than the 41 employees), but only 10 valid: 35 under OU=Test for Dev Team
        List<AdRecord> records = new ArrayList<>(directory.fetched().subList(0, 10));
        for (int i = 0; i < 35; i++) {
            records.add(testOu(i));
        }
        directory.setFetched(records);

        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);

        assertThat(outcome.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("SAFETY");
        assertThat(employees.countByEnabledTrueAndExternalFalse()).isEqualTo(41);
        AdSyncLog row = logRow(outcome.logId());
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getErrorCode()).isEqualTo("SAFETY");
        assertThat(row.getFetchedCount()).isEqualTo(10);
        assertThat(row.getSkippedCount()).isEqualTo(35);
        assertThat(row.getErrorMessage()).contains("only 10 valid employees", "AD_SYNC_MIN_RATIO");
    }

    @Test
    void noValidRecordFailsEvenOnAnEmptyDatabase() {
        directory.setFetched(List.of(testOu(1), testOu(2)));

        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);

        assertThat(outcome.errorCode()).isEqualTo("SAFETY");
        assertThat(count("employees")).isZero();
    }

    // --- overlay -------------------------------------------------------------------------------------------------

    @Test
    void mockOverlayIsSeededOnceAfterTheFirstSync() {
        service.sync(SyncTrigger.MANUAL);

        List<EmployeeOverride> rows = overrides.findAll();
        assertThat(rows).hasSize(18);
        assertThat(rows).filteredOn(o -> o.getEmployeeId().equals(employee("hhoney").getId()))
                .extracting(EmployeeOverride::getCs).containsExactlyInAnyOrder("People", "F&O");
        assertThat(rows).filteredOn(o -> o.getCs().equals("F&O") && o.getEmployeeId().equals(employee("hhoney").getId()))
                .singleElement().satisfies(o -> assertThat(o.getManagerOverrideId()).isEqualTo(employee("ccoral").getId()));

        directory.change("mmint", "title", "Changed");
        service.sync(SyncTrigger.MANUAL);
        assertThat(overrides.count()).isEqualTo(18);
    }

    @Test
    void syncNeverTouchesTheOverlay() {
        service.sync(SyncTrigger.MANUAL);
        EmployeeOverride edited = overrides.findAll().get(0);
        edited.setAreaOfDuties("Edited by an admin");
        overrides.save(edited);
        Map<Long, String> before = overlay();

        directory.change("mmint", "title", "Changed");
        directory.remove("ggold");
        service.sync(SyncTrigger.MANUAL);

        assertThat(overlay()).isEqualTo(before);
    }

    // --- circuit breaker, lock, interrupted runs ---------------------------------------------------------------

    @Test
    void authErrorSuspendsScheduledAndStartupRunsUntilAManualRun() {
        directory.failWith(new AdDirectoryException(AdErrorCode.AUTH, "invalid credentials (LDAP error 49)"));

        SyncOutcome failed = service.sync(SyncTrigger.MANUAL);
        assertThat(failed.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(logRow(failed.logId()).getErrorCode()).isEqualTo("AUTH");

        assertThat(service.sync(SyncTrigger.SCHEDULED).status()).isEqualTo(SyncStatus.SUSPENDED);
        assertThat(service.sync(SyncTrigger.STARTUP).status()).isEqualTo(SyncStatus.SUSPENDED);
        assertThat(directory.fetchCalls).as("no further bind attempt").hasValue(1);
        assertThat(count("ad_sync_log")).isEqualTo(1);

        directory.reset();
        assertThat(service.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(service.sync(SyncTrigger.SCHEDULED).status()).isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    void otherFailuresAreFailedWithTheirCodeAndDoNotSuspend() {
        directory.failWith(new AdDirectoryException(AdErrorCode.NETWORK, "cannot reach dc:636"));

        SyncOutcome outcome = service.sync(SyncTrigger.SCHEDULED);

        assertThat(outcome.errorCode()).isEqualTo("NETWORK");
        assertThat(logRow(outcome.logId()).getErrorMessage()).contains("cannot reach dc:636");
        assertThat(breaker.isSuspended()).isFalse();
    }

    @Test
    void parallelRunIsSkippedWhileAnotherHoldsTheLock() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement lock = other.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + AdSyncService.LOCK_KEY + ")");
            }

            SyncOutcome outcome = service.sync(SyncTrigger.SCHEDULED);

            assertThat(outcome.status()).isEqualTo(SyncStatus.SKIPPED);
            assertThat(logRow(outcome.logId()).getStatus()).isEqualTo("SKIPPED");
            assertThat(directory.fetchCalls).hasValue(0);
            assertThat(count("employees")).isZero();
            other.rollback();
        }
        assertThat(service.sync(SyncTrigger.SCHEDULED).status()).as("lock released with the other transaction")
                .isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    void runningRowWithoutTheLockIsMarkedInterruptedByTheNextRun() {
        long stale = insertRunningRow();

        service.sync(SyncTrigger.MANUAL);

        AdSyncLog repaired = logRow(stale);
        assertThat(repaired.getStatus()).isEqualTo("FAILED");
        assertThat(repaired.getErrorCode()).isEqualTo("INTERRUPTED");
        assertThat(repaired.getFinishedAt()).isNotNull();
    }

    @Test
    void repairOfInterruptedRunsWaitsForARunningSync() throws Exception {
        long stale = insertRunningRow();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement lock = other.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + AdSyncService.LOCK_KEY + ")");
            }
            assertThat(service.repairInterruptedRuns()).isZero();
            assertThat(logRow(stale).getStatus()).isEqualTo("RUNNING");
            other.rollback();
        }
        assertThat(service.repairInterruptedRuns()).isEqualTo(1);
        assertThat(logRow(stale).getErrorCode()).isEqualTo("INTERRUPTED");
    }

    @Test
    void accountCreatedAgainWithANewGuidTakesOverTheDnAndUpn() {
        service.sync(SyncTrigger.MANUAL);
        Employee old = employee("mmint");
        directory.change("mmint", "objectGUID", UUID.randomUUID().toString());

        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);

        assertThat(outcome.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(outcome.counts().inserted()).isEqualTo(1);
        assertThat(outcome.counts().disabled()).isEqualTo(1);
        Employee released = employees.findById(old.getId()).orElseThrow();
        assertThat(released.isEnabled()).isFalse();
        assertThat(released.getUpn()).endsWith("#released-" + old.getId());
        assertThat(employees.findFirstByUpnIgnoreCase("mmint@kz.example")).hasValueSatisfying(e -> {
            assertThat(e.getId()).isNotEqualTo(old.getId());
            assertThat(e.isEnabled()).isTrue();
        });
    }

    // --- dry run -------------------------------------------------------------------------------------------------

    @Test
    void dryRunReportsEverythingAndWritesNothing() {
        DryRunReport report = service.dryRun(SyncTrigger.MANUAL);

        assertThat(report.status()).isEqualTo("OK");
        assertThat(report.ldapResultSize()).isEqualTo(43);
        assertThat(report.fetchedCount()).isEqualTo(41);
        assertThat(report.skippedCount()).isEqualTo(2);
        assertThat(report.skippedByReason()).containsEntry(SkipReason.COUNTRY_NOT_ALLOWED, 1)
                .containsEntry(SkipReason.BLANK_DISPLAY_NAME, 1).containsEntry(SkipReason.MISSING_REQUIRED, 0);
        assertThat(report.byCountry()).containsOnlyKeys("KZ", "UZ", "AZ", "GE", "AM", "KG");
        assertThat(report.byFunction()).containsOnlyKeys("People", "Finance", "ITS", "Markets", "F&O", "OGC", "QRM",
                "Tenders");
        assertThat(report.unmappedSegments()).isEmpty();
        assertThat(report.withoutManager()).isEqualTo(4);
        assertThat(report.withoutPm()).isEqualTo(4);
        assertThat(report.withoutManagerAndPm()).isEqualTo(4);
        assertThat(report.managers()).containsEntry(ManagerMatch.EXTERNAL, 6).containsEntry(ManagerMatch.NONE, 4)
                .containsEntry(ManagerMatch.UNRESOLVED, 0).containsEntry(ManagerMatch.FETCHED, 31);
        assertThat(report.pms()).containsEntry(PmMatch.EMAIL, 1).containsEntry(PmMatch.LOCAL_PART, 1)
                .containsEntry(PmMatch.EXTERNAL, 6).containsEntry(PmMatch.NONE, 4).containsEntry(PmMatch.UNRESOLVED, 0);
        assertThat(report.pmDiffersFromManager()).isEqualTo(2);
        assertThat(report.pmEqualsManager()).isEqualTo(35);
        assertThat(report.pmResolvedByLocalPart()).isEqualTo(1);
        assertThat(report.externals()).isEqualTo(1);
        assertThat(report.currentEmployees()).isZero();
        assertThat(report.safetyCheck()).isEqualTo("PASS");

        assertThat(count("employees")).isZero();
        assertThat(count("employee_overrides")).isZero();
        assertThat(count("ad_sync_log")).isZero();
    }

    @Test
    void dryRunAfterASyncChangesNothingAndShowsTheSafetyProblem() {
        service.sync(SyncTrigger.MANUAL);
        Map<String, Long> before = counts();
        directory.setFetched(directory.fetched().subList(0, 5));

        DryRunReport report = service.dryRun(SyncTrigger.MANUAL);

        assertThat(report.currentEmployees()).isEqualTo(41);
        assertThat(report.safetyCheck()).startsWith("only 5 valid employees");
        assertThat(counts()).isEqualTo(before);
        assertThat(employees.countByEnabledTrueAndExternalFalse()).isEqualTo(41);
    }

    @Test
    void failedDryRunIsReportedWithItsCode() {
        directory.failWith(new AdDirectoryException(AdErrorCode.TLS, "truststore missing or wrong"));

        DryRunReport report = service.dryRun(SyncTrigger.MANUAL);

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorCode()).isEqualTo("TLS");
        assertThat(count("ad_sync_log")).isZero();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private long insertRunningRow() {
        return syncLog.save(AdSyncLog.builder().status("RUNNING").triggerType("SCHEDULED").build()).getId();
    }

    private Map<Long, String> overlay() {
        Map<Long, String> rows = new HashMap<>();
        jdbc.query("SELECT * FROM employee_overrides ORDER BY id", (ResultSet rs) -> {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.append(rs.getObject(i)).append('|');
            }
            rows.put(rs.getLong("id"), row.toString());
        });
        return rows;
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new HashMap<>();
        for (String table : List.of("employees", "employee_overrides", "ad_sync_log")) {
            counts.put(table, count(table));
        }
        counts.put("enabled", employees.countByEnabledTrueAndExternalFalse());
        return counts;
    }

    private static AdRecord testOu(int n) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("objectGUID", String.format("c0000000-0000-4000-8000-%012d", n));
        entry.put("distinguishedName", "CN=tdev" + n + ",OU=Test for Dev Team,OU=Countries,OU=CCG,DC=corp,DC=example");
        entry.put("sAMAccountName", "tdev" + n);
        entry.put("userPrincipalName", "tdev" + n + "@kz.example");
        entry.put("displayName", "Dev, Test " + n);
        entry.put("department", "CS/ITS/ITS/ITS General");
        entry.put("userAccountControl", 512);
        return new AdRecord(entry);
    }

    private static AdRecord withoutAttribute(AdRecord record, String attribute, String login) {
        Map<String, Object> entry = new HashMap<>(record.attributes());
        entry.remove(attribute);
        entry.put("sAMAccountName", login);
        entry.put("objectGUID", entry.containsKey("objectGUID") ? UUID.randomUUID().toString() : null);
        entry.values().removeIf(v -> v == null);
        entry.put("distinguishedName", "CN=" + login + ",OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example");
        return new AdRecord(entry);
    }
}

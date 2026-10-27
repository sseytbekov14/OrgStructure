package cs_orgchart.ad.sync;

import cs_orgchart.ad.client.AdDirectoryException;
import cs_orgchart.ad.client.AdErrorCode;
import cs_orgchart.employee.EmployeeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The AD sync (spec v3 §7). One run:
 * <ol>
 *   <li>after invalid credentials, scheduled and startup runs are refused without contacting AD (circuit
 *       breaker);</li>
 *   <li>the sync transaction takes a transaction-level advisory lock; when another run holds it, a SKIPPED row is
 *       written and nothing else happens. The lock, not a RUNNING row, says that a sync is running: it is released
 *       with the transaction on the same connection, also when the application dies;</li>
 *   <li>RUNNING row (own transaction), fetch, validation with skips, resolution ({@link AdSyncPlanner});</li>
 *   <li>safety check on the valid records: none, or fewer than AD_SYNC_MIN_RATIO of the current employees → FAILED
 *       (SAFETY); an empty database (first sync) has no lower limit;</li>
 *   <li>the writes in the one transaction ({@link AdSyncWriter}); any failure rolls them all back;</li>
 *   <li>SUCCESS or FAILED row (own transaction), and after the commit {@link EmployeesSynced} when the employees
 *       changed.</li>
 * </ol>
 * The overlay (employee_overrides) is never touched.
 */
@Slf4j
public class AdSyncService {

    /** Key of the PostgreSQL advisory lock of the AD sync */
    public static final long LOCK_KEY = 0x4F52_4741_4453_594EL;

    private final AdSyncPlanner planner;
    private final AdSyncWriter writer;
    private final AdSyncLogWriter syncLog;
    private final EmployeeRepository employees;
    private final AuthCircuitBreaker breaker;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final double minRatio;
    private final Clock clock;

    public AdSyncService(AdSyncPlanner planner, AdSyncWriter writer, AdSyncLogWriter syncLog,
                         EmployeeRepository employees, AuthCircuitBreaker breaker, TransactionTemplate transaction,
                         JdbcTemplate jdbc, ApplicationEventPublisher events, double minRatio, Clock clock) {
        this.planner = planner;
        this.writer = writer;
        this.syncLog = syncLog;
        this.employees = employees;
        this.breaker = breaker;
        this.transaction = transaction;
        this.jdbc = jdbc;
        this.events = events;
        this.minRatio = minRatio;
        this.clock = clock;
    }

    /** What the sync transaction ended with */
    private record Attempt(boolean busy, Long logId, SyncPlan plan, SyncCounts counts, RuntimeException failure) {
    }

    public SyncOutcome sync(SyncTrigger trigger) {
        if (!breaker.tryStart(trigger)) {
            return new SyncOutcome(SyncStatus.SUSPENDED, null, null, AdErrorCode.AUTH.name(),
                    "suspended after invalid credentials until a manual sync or a restart");
        }
        long started = System.nanoTime();
        AtomicReference<Long> logId = new AtomicReference<>();
        AtomicReference<SyncPlan> plan = new AtomicReference<>();
        Attempt attempt;
        try {
            attempt = transaction.execute(status -> {
                if (!tryLock()) {
                    return new Attempt(true, null, null, null, null);
                }
                logId.set(syncLog.running(trigger));
                int interrupted = syncLog.interrupted(logId.get());
                if (interrupted > 0) {
                    log.warn("AD sync: {} earlier run(s) found RUNNING without the lock, marked FAILED (INTERRUPTED)",
                            interrupted);
                }
                try {
                    plan.set(planner.plan());
                    checkSafety(plan.get());
                    SyncCounts counts = writer.apply(plan.get(), clock.instant());
                    return new Attempt(false, logId.get(), plan.get(), counts, null);
                } catch (RuntimeException e) {
                    status.setRollbackOnly();
                    return new Attempt(false, logId.get(), plan.get(), null, e);
                }
            });
        } catch (RuntimeException e) {
            // The commit itself failed (or the lock query): everything is rolled back
            attempt = new Attempt(false, logId.get(), plan.get(), null, e);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;

        if (attempt.busy()) {
            long id = syncLog.skipped(trigger, "another AD sync was running (advisory lock held)");
            log.info("AD sync ({}) SKIPPED: another sync is running", trigger);
            return new SyncOutcome(SyncStatus.SKIPPED, id, null, null, "another AD sync is running");
        }
        if (attempt.failure() != null) {
            return failed(trigger, attempt, ms);
        }
        SyncCounts counts = attempt.counts();
        syncLog.succeeded(attempt.logId(), counts);
        breaker.recordSuccess();
        log.info("AD sync ({}) SUCCESS in {} ms: fetched {}, skipped {}, inserted {}, updated {}, disabled {},"
                        + " external {}, unmapped {}, PM by local part {}", trigger, ms, counts.fetched(),
                counts.skipped(), counts.inserted(), counts.updated(), counts.disabled(), counts.external(),
                counts.unmapped(), counts.pmLocalPart());
        if (counts.changed()) {
            try {
                events.publishEvent(new EmployeesSynced(attempt.logId(), trigger, counts));
            } catch (RuntimeException e) {
                // The sync is committed; a listener's problem is its own
                log.error("AD sync: a listener of EmployeesSynced failed", e);
            }
        }
        return new SyncOutcome(SyncStatus.SUCCESS, attempt.logId(), counts, null, null);
    }

    private SyncOutcome failed(SyncTrigger trigger, Attempt attempt, long ms) {
        RuntimeException failure = attempt.failure();
        AdErrorCode code = failure instanceof AdDirectoryException ad ? ad.getCode() : AdErrorCode.INTERNAL;
        String message = failure instanceof AdDirectoryException ad ? ad.getMessage()
                : failure.getClass().getSimpleName() + ": " + mostSpecificMessage(failure);
        breaker.recordFailure(code, message);
        if (code == AdErrorCode.INTERNAL) {
            log.error("AD sync ({}) FAILED in {} ms, nothing changed: {}", trigger, ms, message, failure);
        } else {
            log.error("AD sync ({}) FAILED in {} ms, nothing changed: {}", trigger, ms, message);
        }
        Long id = attempt.logId();
        if (id != null) {
            syncLog.failed(id, code.name(), message, attempt.plan());
        }
        SyncCounts counts = attempt.plan() == null ? null : new SyncCounts(attempt.plan().fetchedCount(),
                attempt.plan().skippedCount(), 0, 0, 0, 0, attempt.plan().unmappedSegments().size(), 0);
        return new SyncOutcome(SyncStatus.FAILED, id, counts, code.name(), message);
    }

    /**
     * Spec §7 dry run: fetch, validation, resolution and the safety check, writing nothing (no log row either).
     */
    public DryRunReport dryRun(SyncTrigger trigger) {
        Instant at = clock.instant();
        long started = System.nanoTime();
        long current = employees.countByEnabledTrueAndExternalFalse();
        if (!breaker.tryStart(trigger)) {
            return DryRunReport.notRun("SUSPENDED", trigger, at, 0, AdErrorCode.AUTH.name(),
                    "suspended after invalid credentials until a manual run or a restart", current, minRatio);
        }
        try {
            SyncPlan plan = planner.plan();
            breaker.recordSuccess();
            DryRunReport report = DryRunReport.of(plan, trigger, at, (System.nanoTime() - started) / 1_000_000,
                    current, minRatio, safetyProblem(plan, current));
            log.info("{}", report.summary());
            return report;
        } catch (AdDirectoryException e) {
            breaker.recordFailure(e.getCode(), e.getMessage());
            log.error("AD dry run ({}) FAILED: {}", trigger, e.getMessage());
            return DryRunReport.notRun("FAILED", trigger, at, (System.nanoTime() - started) / 1_000_000,
                    e.getCode().name(), e.getMessage(), current, minRatio);
        }
    }

    /**
     * Rows left RUNNING by a run that was cut off → FAILED (INTERRUPTED), when no sync holds the lock now.
     *
     * @return how many rows were repaired
     */
    public int repairInterruptedRuns() {
        Integer repaired = transaction.execute(status -> tryLock() ? syncLog.interrupted(null) : 0);
        if (repaired != null && repaired > 0) {
            log.warn("AD sync: {} run(s) left RUNNING by an earlier start marked FAILED (INTERRUPTED)", repaired);
        }
        return repaired == null ? 0 : repaired;
    }

    private boolean tryLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                LOCK_KEY));
    }

    private void checkSafety(SyncPlan plan) {
        String problem = safetyProblem(plan, employees.countByEnabledTrueAndExternalFalse());
        if (!"PASS".equals(problem)) {
            throw new AdDirectoryException(AdErrorCode.SAFETY, problem + "; the database is left unchanged");
        }
    }

    /** PASS, or why a sync with this plan must not run (spec §7 step 5, on the valid records) */
    private String safetyProblem(SyncPlan plan, long current) {
        int fetched = plan.fetchedCount();
        if (fetched == 0) {
            return "no valid employee fetched (" + plan.skippedCount() + " skipped) - check AD_LDAP_FILTER,"
                    + " AD_LDAP_BASE_DN and AD_LDAP_COUNTRIES";
        }
        if (current > 0 && fetched < minRatio * current) {
            return String.format(Locale.ROOT, "only %d valid employees fetched (%d skipped), fewer than"
                    + " AD_SYNC_MIN_RATIO %.2f x %d current employees", fetched, plan.skippedCount(), minRatio, current);
        }
        return "PASS";
    }

    private static String mostSpecificMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? failure.getMessage() : root.getMessage();
    }
}

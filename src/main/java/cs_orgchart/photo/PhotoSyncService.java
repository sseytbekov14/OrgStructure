package cs_orgchart.photo;

import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.client.AdDirectoryException;
import cs_orgchart.ad.client.AdErrorCode;
import cs_orgchart.ad.sync.AdSyncLogWriter;
import cs_orgchart.ad.sync.AdSyncSucceeded;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.ad.sync.SyncTrigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * The photo phase of the AD sync (AD_PHOTOS_ENABLED=true, EMPLOYEE_SOURCE=db). After every successful sync
 * ({@link AdSyncSucceeded}) it runs on its own thread, so it never fails, slows down or changes the employee sync:
 * <ol>
 *   <li>refused while the circuit breaker is suspended (invalid credentials); an AUTH failure here suspends it as
 *       for the employee sync;</li>
 *   <li>a session advisory lock of its own: two runs never overlap (the second one is BUSY);</li>
 *   <li>the stored photos of disabled employees are deleted;</li>
 *   <li>candidates: enabled employees, also the managers outside Central Services (external: their card shows the
 *       photo too), without a photo row or whose ad_when_changed differs from the
 *       row's source_when_changed (force: everybody). Each one: read, validate, write its row in its own small
 *       transaction (PRESENT, NONE or INVALID). A failed read is counted and retried by the next run. Photos
 *       uploaded on the site ({@link PhotoSource#MANUAL}) are never candidates and never overwritten;</li>
 *   <li>at most AD_PHOTOS_TIME_BUDGET per run: the rest is left for the next run;</li>
 *   <li>the counters into the ad_sync_log row of the run; {@link PhotosChanged} when photos were stored or removed.</li>
 * </ol>
 * Logs ids, states and counts only: never the bytes, names or e-mails.
 */
@Slf4j
public class PhotoSyncService {

    /** Key of the PostgreSQL advisory lock of the photo phase (not the employee sync's) */
    public static final long LOCK_KEY = 0x4F52_4741_5048_4F54L;

    public enum Status { DONE, DISABLED, SUSPENDED, BUSY }

    /**
     * One run of the photo phase
     *
     * @param remaining candidates left for the next run (time budget reached or stopped by an AUTH failure)
     */
    public record PhotoRun(Status status, Long logId, int candidates, int fetched, int updated, int removed,
                           int invalid, int failed, int remaining) {

        static PhotoRun notRun(Status status) {
            return new PhotoRun(status, null, 0, 0, 0, 0, 0, 0, 0);
        }

        public boolean changed() {
            return updated + removed > 0;
        }
    }

    /** An employee whose photo is (re)read; previous: the state of its row, or null */
    private record Candidate(long id, String dn, UUID adGuid, Instant whenChanged, PhotoState previous,
                             String previousHash) {
    }

    private static final String CANDIDATES = """
            SELECT e.id, e.dn, e.ad_guid, e.ad_when_changed, p.state, p.sha256
            FROM employees e LEFT JOIN employee_photos p ON p.employee_id = e.id
            WHERE e.enabled AND COALESCE(p.source, 'AD') <> 'MANUAL'
              AND (? OR p.employee_id IS NULL OR p.source_when_changed IS DISTINCT FROM e.ad_when_changed)
            ORDER BY e.id""";

    private final AdDirectoryClient directory;
    private final PhotoValidator validator;
    private final AuthCircuitBreaker breaker;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EmployeePhotoRepository photos;
    private final AdSyncLogWriter syncLog;
    private final ApplicationEventPublisher events;
    private final PhotoProperties properties;
    private final boolean dbMode;
    private final Clock clock;
    private volatile Executor executor;

    public PhotoSyncService(AdDirectoryClient directory, AuthCircuitBreaker breaker, DataSource dataSource,
                            JdbcTemplate jdbc, PlatformTransactionManager transactions, EmployeePhotoRepository photos,
                            AdSyncLogWriter syncLog, ApplicationEventPublisher events, PhotoProperties properties,
                            boolean dbMode, Clock clock, Executor executor) {
        this.directory = directory;
        this.breaker = breaker;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.photos = photos;
        this.syncLog = syncLog;
        this.events = events;
        this.properties = properties;
        this.dbMode = dbMode;
        this.clock = clock;
        this.executor = executor;
        this.validator = new PhotoValidator(properties.getMaxBytes(), properties.getMaxSide());
    }

    /** AD_PHOTOS_ENABLED=true with EMPLOYEE_SOURCE=db */
    public boolean enabled() {
        return properties.isAdEnabled() && dbMode;
    }

    /** Where the runs after a sync go (tests: the calling thread) */
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /** At shutdown: a running photo phase is interrupted (its finished rows are committed) */
    public void close() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    /** After every successful employee sync: the photo phase on its own thread */
    @EventListener
    public void onSyncSucceeded(AdSyncSucceeded synced) {
        if (!enabled()) {
            return;
        }
        executor.execute(() -> {
            try {
                run(synced.logId(), synced.trigger(), false);
            } catch (RuntimeException e) {
                log.error("AD photos: the photo phase after sync run {} failed", synced.logId(), e);
            }
        });
    }

    /**
     * A run soon on the photo thread, not tied to an employee sync: after an uploaded photo was removed, so that the
     * AD photo of that person shows again without waiting for the next sync. Nothing when the feature is off.
     */
    public void refreshSoon() {
        if (!enabled()) {
            return;
        }
        executor.execute(() -> {
            try {
                run(null, SyncTrigger.MANUAL, false);
            } catch (RuntimeException e) {
                log.error("AD photos: the photo phase after a removed upload failed", e);
            }
        });
    }

    /** How many employees a run would read now (the dry run report); null when the feature is off */
    public Integer countCandidates() {
        if (!enabled()) {
            return null;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM (" + CANDIDATES + ") c", Integer.class, false);
        return count == null ? 0 : count;
    }

    /**
     * One run now, on the calling thread
     *
     * @param logId the ad_sync_log row of the sync it follows; null: a run of its own gets its own row
     * @param force read everybody, ignoring the whenChanged comparison
     */
    public PhotoRun run(Long logId, SyncTrigger trigger, boolean force) {
        if (!enabled()) {
            return PhotoRun.notRun(Status.DISABLED);
        }
        if (breaker.isSuspended()) {
            log.warn("AD photos: skipped, the AD sync is suspended after invalid credentials");
            return PhotoRun.notRun(Status.SUSPENDED);
        }
        try (Connection lock = dataSource.getConnection()) {
            if (!advisoryLock(lock, "pg_try_advisory_lock")) {
                log.info("AD photos: skipped, another photo run holds the lock");
                return PhotoRun.notRun(Status.BUSY);
            }
            try {
                return locked(logId, trigger, force);
            } finally {
                advisoryLock(lock, "pg_advisory_unlock");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("AD photos: the advisory lock could not be taken", e);
        }
    }

    private PhotoRun locked(Long logId, SyncTrigger trigger, boolean force) {
        long started = System.nanoTime();
        Instant start = clock.instant();
        Duration budget = properties.getTimeBudget();
        int removed = purgeUnservable();
        List<Candidate> candidates = jdbc.query(CANDIDATES, (rs, n) -> candidate(rs), force);
        int fetched = 0;
        int updated = 0;
        int invalid = 0;
        int failed = 0;
        int done = 0;
        for (Candidate candidate : candidates) {
            if (Duration.between(start, clock.instant()).compareTo(budget) >= 0) {
                log.info("AD photos: time budget {} reached, {} employee(s) left for the next run", budget,
                        candidates.size() - done);
                break;
            }
            Optional<byte[]> value;
            try {
                value = directory.readPhoto(candidate.dn(), candidate.adGuid());
                fetched++;
            } catch (AdDirectoryException e) {
                failed++;
                done++;
                if (e.getCode() == AdErrorCode.AUTH) {
                    breaker.recordFailure(AdErrorCode.AUTH, e.getMessage());
                    log.error("AD photos: invalid credentials, the photo phase stops (suspended like the sync)");
                    break;
                }
                log.warn("AD photos: the photo of employee id={} could not be read ({})", candidate.id(), e.getCode());
                continue;
            } catch (RuntimeException e) {
                failed++;
                done++;
                log.warn("AD photos: the photo of employee id={} could not be read ({})", candidate.id(),
                        e.getClass().getSimpleName());
                continue;
            }
            try {
                Change change = store(candidate, value);
                updated += change == Change.UPDATED ? 1 : 0;
                removed += change == Change.REMOVED || change == Change.INVALID_REMOVED ? 1 : 0;
                invalid += change == Change.INVALID || change == Change.INVALID_REMOVED ? 1 : 0;
            } catch (RuntimeException e) {
                failed++;
                log.warn("AD photos: the photo of employee id={} could not be stored ({})", candidate.id(),
                        e.getClass().getSimpleName());
            }
            done++;
        }
        long id = logId != null ? logId : syncLog.photosOnly(trigger);
        syncLog.photos(id, fetched, updated, removed, invalid, failed);
        PhotoRun run = new PhotoRun(Status.DONE, id, candidates.size(), fetched, updated, removed, invalid, failed,
                candidates.size() - done);
        log.info("AD photos ({}) in {} ms: {} candidate(s), read {}, updated {}, removed {}, invalid {}, failed {},"
                        + " left {}", trigger, (System.nanoTime() - started) / 1_000_000, run.candidates(), fetched,
                updated, removed, invalid, failed, run.remaining());
        if (run.changed()) {
            try {
                events.publishEvent(new PhotosChanged(updated, removed));
            } catch (RuntimeException e) {
                log.error("AD photos: a listener of PhotosChanged failed", e);
            }
        }
        return run;
    }

    private enum Change { NONE, UPDATED, REMOVED, INVALID, INVALID_REMOVED }

    /** The row of one employee, in its own transaction */
    private Change store(Candidate candidate, Optional<byte[]> value) {
        boolean hadPhoto = candidate.previous() == PhotoState.PRESENT;
        EmployeePhoto.EmployeePhotoBuilder row = EmployeePhoto.builder().employeeId(candidate.id())
                .sourceWhenChanged(candidate.whenChanged()).checkedAt(clock.instant());
        Change change;
        if (value.isEmpty()) {
            row.state(PhotoState.NONE);
            change = hadPhoto ? Change.REMOVED : Change.NONE;
        } else {
            PhotoValidator.Result result = validator.validate(value.get());
            if (result.valid()) {
                row.state(PhotoState.PRESENT).content(result.content()).contentType(result.contentType())
                        .width(result.width()).height(result.height()).sizeBytes(result.content().length)
                        .sha256(result.sha256());
                change = hadPhoto && result.sha256().equals(candidate.previousHash()) ? Change.NONE : Change.UPDATED;
            } else {
                log.warn("AD photos: the photo of employee id={} is INVALID: {}", candidate.id(), result.reason());
                row.state(PhotoState.INVALID);
                change = hadPhoto ? Change.INVALID_REMOVED : Change.INVALID;
            }
        }
        EmployeePhoto photo = row.build();
        // A photo uploaded on the site while this run was reading AD stays: the upload wins
        boolean uploaded = Boolean.TRUE.equals(transaction.execute(status -> {
            if (photos.findById(candidate.id()).map(p -> p.getSource() == PhotoSource.MANUAL).orElse(false)) {
                return true;
            }
            photos.save(photo);
            return false;
        }));
        return uploaded ? Change.NONE : change;
    }

    /** The photos of employees that are disabled now: deleted (rows and bytes); how many had a photo */
    private int purgeUnservable() {
        List<String> states = jdbc.queryForList("""
                DELETE FROM employee_photos p USING employees e
                WHERE e.id = p.employee_id AND NOT e.enabled
                RETURNING p.state""", String.class);
        int purged = (int) states.stream().filter(PhotoState.PRESENT.name()::equals).count();
        if (!states.isEmpty()) {
            log.info("AD photos: {} row(s) of disabled employees deleted ({} with a photo)",
                    states.size(), purged);
        }
        return purged;
    }

    private static Candidate candidate(ResultSet rs) throws SQLException {
        Timestamp whenChanged = rs.getTimestamp("ad_when_changed");
        String state = rs.getString("state");
        String hash = rs.getString("sha256");
        return new Candidate(rs.getLong("id"), rs.getString("dn"), rs.getObject("ad_guid", UUID.class),
                whenChanged == null ? null : whenChanged.toInstant(),
                state == null ? null : PhotoState.valueOf(state), hash == null ? null : hash.trim());
    }

    private static boolean advisoryLock(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}

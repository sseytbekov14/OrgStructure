package cs_orgchart.ad.sync;

import cs_orgchart.ad.client.AdDirectoryException;
import cs_orgchart.ad.client.AdErrorCode;
import cs_orgchart.ad.client.MockPhotos;
import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.photo.EmployeePhotoRepository;
import cs_orgchart.photo.PhotoProperties;
import cs_orgchart.photo.PhotoState;
import cs_orgchart.photo.PhotoSyncService;
import cs_orgchart.photo.PhotoSyncService.PhotoRun;
import cs_orgchart.photo.PhotoSyncService.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The photo phase on PostgreSQL with the mock directory (db mode): states, incremental runs, removals, failures and
 * retries, the time budget, purging, the switch, the circuit breaker, the lock, the counters, and that the employee
 * sync is the same with and without it. The photo phase runs on the test thread here (it has its own thread in the
 * application).
 */
@TestPropertySource(properties = "EMPLOYEE_SOURCE=db")
class PhotoSyncServiceTest extends AdSyncTestSupport {

    @Autowired
    private PhotoSyncService photoSync;

    @Autowired
    private PhotoProperties properties;

    @Autowired
    private EmployeePhotoRepository photos;

    @Autowired
    private AdSyncLogWriter syncLogWriter;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private EmployeeDirectory employeeDirectory;

    private static final byte[] VALID = MockPhotos.square(1, "MM", "jpeg");
    private static final byte[] OTHER = MockPhotos.square(2, "MM", "png");
    private static final byte[] TEXT = "not a photo".getBytes();

    @BeforeEach
    void photosOnTheTestThread() {
        photoSync.setExecutor(Runnable::run);
        properties.setAdEnabled(true);
        properties.setTimeBudget(Duration.ofSeconds(120));
    }

    @AfterEach
    void photosOff() {
        properties.setAdEnabled(false);
        properties.setTimeBudget(Duration.ofSeconds(120));
    }

    private SyncOutcome syncNow() {
        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);
        assertThat(outcome.status()).isEqualTo(SyncStatus.SUCCESS);
        return outcome;
    }

    private PhotoState state(String login) {
        String state = jdbc.queryForObject("SELECT state FROM employee_photos WHERE employee_id = ?", String.class,
                employee(login).getId());
        return PhotoState.valueOf(state);
    }

    private int rows() {
        return (int) count("employee_photos");
    }

    private int servable() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM employees WHERE enabled", Integer.class);
        return n == null ? 0 : n;
    }

    private void touch(String login, String whenChanged) {
        directory.change(login, "whenChanged", whenChanged);
    }

    @Test
    void theFirstRunStoresPresentNoneAndInvalid() {
        directory.setPhoto("mmint", VALID);
        directory.setPhoto("kkhaki", null);
        directory.setPhoto("oolive", TEXT);

        SyncOutcome outcome = syncNow();

        assertThat(rows()).isEqualTo(servable());
        assertThat(state("mmint")).isEqualTo(PhotoState.PRESENT);
        assertThat(state("kkhaki")).isEqualTo(PhotoState.NONE);
        assertThat(state("oolive")).isEqualTo(PhotoState.INVALID);
        var stored = photos.findById(employee("mmint").getId()).orElseThrow();
        assertThat(stored.getContentType()).isEqualTo("image/jpeg");
        assertThat(stored.getContent()).startsWith((byte) 0xFF, (byte) 0xD8);
        assertThat(stored.getSha256()).hasSize(64);
        assertThat(stored.getWidth()).isEqualTo(96);
        assertThat(stored.getSourceWhenChanged()).isEqualTo(employee("mmint").getAdWhenChanged());
        assertThat(photos.findById(employee("kkhaki").getId()).orElseThrow().getContent()).isNull();
        assertThat(photos.findById(employee("oolive").getId()).orElseThrow().getContent()).isNull();
        // Every candidate was read once; the mock values give all three states
        assertThat(directory.photoReads).hasSize(servable());
        assertThat(jdbc.queryForList("SELECT DISTINCT state FROM employee_photos", String.class))
                .containsExactlyInAnyOrder("PRESENT", "NONE", "INVALID");
        // The counters in the log row of that sync
        AdSyncLog row = logRow(outcome.logId());
        assertThat(row.getPhotosFetched()).isEqualTo(servable());
        assertThat(row.getPhotosUpdated()).isEqualTo((int) count("employee_photos WHERE state = 'PRESENT'"));
        assertThat(row.getPhotosInvalid()).isEqualTo((int) count("employee_photos WHERE state = 'INVALID'"));
        assertThat(row.getPhotosRemoved()).isZero();
        assertThat(row.getPhotosFailed()).isZero();
    }

    @Test
    void theSecondRunReadsNobody() {
        syncNow();
        directory.photoReads.clear();

        syncNow();
        PhotoRun manual = photoSync.run(null, SyncTrigger.MANUAL, false);

        assertThat(directory.photoReads).isEmpty();
        assertThat(manual.status()).isEqualTo(Status.DONE);
        assertThat(manual.candidates()).isZero();
        assertThat(photoSync.countCandidates()).isZero();
    }

    @Test
    void aChangedWhenChangedReReadsOnlyThatEmployee() {
        directory.setPhoto("mmint", VALID);
        syncNow();
        String before = photos.findById(employee("mmint").getId()).orElseThrow().getSha256();
        directory.photoReads.clear();

        directory.setPhoto("mmint", OTHER);
        touch("mmint", "20270102030405.0Z");
        SyncOutcome outcome = syncNow();

        assertThat(directory.photoReads).containsExactly("mmint");
        assertThat(photos.findById(employee("mmint").getId()).orElseThrow().getSha256()).isNotEqualTo(before);
        assertThat(logRow(outcome.logId()).getPhotosUpdated()).isEqualTo(1);
    }

    @Test
    void aPhotoRemovedInAdBecomesNoneAndItsBytesAreDeleted() {
        directory.setPhoto("mmint", VALID);
        syncNow();
        directory.setPhoto("mmint", null);
        touch("mmint", "20270102030405.0Z");

        SyncOutcome outcome = syncNow();

        assertThat(state("mmint")).isEqualTo(PhotoState.NONE);
        assertThat(photos.findById(employee("mmint").getId()).orElseThrow().getContent()).isNull();
        assertThat(logRow(outcome.logId()).getPhotosRemoved()).isEqualTo(1);
    }

    @Test
    void anInvalidValueNeverFailsTheRun() {
        syncNow();
        for (MockPhotos.Bad bad : MockPhotos.Bad.values()) {
            directory.setPhoto("mmint", MockPhotos.bad(bad, 3, "MM"));
            touch("mmint", "2027010" + (bad.ordinal() + 1) + "000000.0Z");
            properties.setAdEnabled(false);
            syncNow(); // the new whenChanged, without the photo phase
            properties.setAdEnabled(true);

            PhotoRun run = photoSync.run(null, SyncTrigger.MANUAL, false);

            assertThat(run.status()).as(bad.name()).isEqualTo(Status.DONE);
            assertThat(run.failed()).as(bad.name()).isZero();
            assertThat(run.invalid()).as(bad.name()).isEqualTo(bad == MockPhotos.Bad.PNG_WITH_HTML ? 0 : 1);
            assertThat(state("mmint")).as(bad.name())
                    .isEqualTo(bad == MockPhotos.Bad.PNG_WITH_HTML ? PhotoState.PRESENT : PhotoState.INVALID);
        }
    }

    @Test
    void aReadErrorIsCountedAndRetriedByTheNextRun() {
        syncNow(); // employees and photos
        jdbc.update("DELETE FROM employee_photos WHERE employee_id = ?", employee("mmint").getId());
        directory.failPhoto("mmint", new AdDirectoryException(AdErrorCode.NETWORK, "connection reset"));
        directory.photoReads.clear();

        PhotoRun failed = photoSync.run(null, SyncTrigger.MANUAL, false);
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.status()).isEqualTo(Status.DONE);
        assertThat(count("employee_photos WHERE employee_id = " + employee("mmint").getId())).isZero();
        assertThat(logRow(failed.logId()).getPhotosFailed()).isEqualTo(1);

        directory.failPhoto("mmint", null);
        directory.setPhoto("mmint", VALID);
        PhotoRun retried = photoSync.run(null, SyncTrigger.MANUAL, false);
        assertThat(retried.fetched()).isEqualTo(1);
        assertThat(state("mmint")).isEqualTo(PhotoState.PRESENT);
    }

    /** A clock the test moves */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2027-01-01T00:00:00Z"));

        void advance(Duration step) {
            now.updateAndGet(i -> i.plus(step));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void theTimeBudgetStopsTheRunAndTheNextRunContinues() {
        properties.setAdEnabled(false);
        syncNow(); // employees only
        properties.setAdEnabled(true);
        TestClock clock = new TestClock();
        PhotoSyncService budgeted = new PhotoSyncService(directory, breaker, dataSource, jdbc, transactions, photos,
                syncLogWriter, events, properties, true, clock, Runnable::run);
        directory.beforePhotoRead(() -> clock.advance(Duration.ofSeconds(50)));

        // 120 s: reads at 0, 50 and 100 s; at 150 s the budget is used up
        PhotoRun first = budgeted.run(null, SyncTrigger.MANUAL, false);
        assertThat(first.fetched()).isEqualTo(3);
        assertThat(first.remaining()).isEqualTo(servable() - 3);
        assertThat(rows()).isEqualTo(3);

        directory.beforePhotoRead(() -> { });
        PhotoRun second = budgeted.run(null, SyncTrigger.MANUAL, false);
        assertThat(second.candidates()).isEqualTo(servable() - 3);
        assertThat(second.remaining()).isZero();
        assertThat(rows()).isEqualTo(servable());
    }

    @Test
    void theStoredPhotoOfADisabledEmployeeIsPurged() {
        directory.setPhoto("mmint", VALID);
        syncNow();
        assertThat(state("mmint")).isEqualTo(PhotoState.PRESENT);

        directory.remove("mmint");
        SyncOutcome outcome = syncNow();

        assertThat(employee("mmint").isEnabled()).isFalse();
        assertThat(photos.findById(employee("mmint").getId())).isEmpty();
        assertThat(logRow(outcome.logId()).getPhotosRemoved()).isEqualTo(1);
    }

    @Test
    void managersOutsideCentralServicesGetTheirPhotoToo() {
        syncNow();
        String login = jdbc.queryForObject("SELECT login FROM employees WHERE is_external AND enabled ORDER BY id"
                + " LIMIT 1", String.class);
        long id = employee(login).getId();
        assertThat(count("employee_photos WHERE employee_id = " + id)).as("checked as a candidate").isEqualTo(1);

        directory.setPhoto(login, VALID);
        photoSync.run(null, SyncTrigger.MANUAL, true);

        assertThat(state(login)).isEqualTo(PhotoState.PRESENT);
        // The people reporting to them carry the URL of that photo
        assertThat(employeeDirectory.getAllEmployees()).filteredOn(e -> Long.valueOf(id).equals(e.getPmId()))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getPmPhotoUrl()).startsWith("/api/photos/" + id + "?v="));
    }

    @Test
    void switchedOffNoPhotoIsRead() {
        properties.setAdEnabled(false);

        syncNow();

        assertThat(directory.photoReads).isEmpty();
        assertThat(rows()).isZero();
        assertThat(photoSync.run(null, SyncTrigger.MANUAL, true).status()).isEqualTo(Status.DISABLED);
        assertThat(photoSync.countCandidates()).isNull();
        assertThat(directory.photoReads).isEmpty();
    }

    @Test
    void invalidCredentialsSuspendThePhotoPhaseLikeTheSync() {
        properties.setAdEnabled(false);
        syncNow();
        properties.setAdEnabled(true);
        directory.fetched().forEach(r -> directory.failPhoto(String.valueOf(r.get("sAMAccountName")),
                new AdDirectoryException(AdErrorCode.AUTH, "invalid credentials")));

        PhotoRun run = photoSync.run(null, SyncTrigger.SCHEDULED, false);

        assertThat(directory.photoReads).hasSize(1);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.remaining()).isEqualTo(servable() - 1);
        assertThat(breaker.isSuspended()).isTrue();
        directory.photoReads.clear();
        assertThat(photoSync.run(null, SyncTrigger.MANUAL, false).status()).isEqualTo(Status.SUSPENDED);
        assertThat(service.sync(SyncTrigger.SCHEDULED).status()).isEqualTo(SyncStatus.SUSPENDED);
        assertThat(directory.photoReads).isEmpty();
    }

    @Test
    void twoRunsNeverOverlap() throws Exception {
        properties.setAdEnabled(false);
        syncNow();
        properties.setAdEnabled(true);
        try (Connection other = dataSource.getConnection()) {
            lock(other, "pg_advisory_lock");
            PhotoRun busy = photoSync.run(null, SyncTrigger.MANUAL, false);
            assertThat(busy.status()).isEqualTo(Status.BUSY);
            assertThat(directory.photoReads).isEmpty();
            lock(other, "pg_advisory_unlock");
        }
        // Two runs started together: one reads, the other is BUSY (or finds nothing left to read)
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        directory.beforePhotoRead(() -> {
            reading.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicReference<PhotoRun> first = new AtomicReference<>();
        Thread thread = new Thread(() -> first.set(photoSync.run(null, SyncTrigger.MANUAL, false)));
        thread.start();
        assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
        PhotoRun second = photoSync.run(null, SyncTrigger.MANUAL, false);
        release.countDown();
        thread.join(30_000);
        assertThat(second.status()).isEqualTo(Status.BUSY);
        assertThat(first.get().status()).isEqualTo(Status.DONE);
        assertThat(first.get().fetched()).isEqualTo(servable());
    }

    private static void lock(Connection connection, String function) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, PhotoSyncService.LOCK_KEY);
            statement.executeQuery().close();
        }
    }

    @Test
    void theEmployeeSyncIsTheSameWithAndWithoutPhotos() {
        SyncCounts with = syncNow().counts();
        assertThat(rows()).isEqualTo(servable());
        List<String> employeesWith = jdbc.queryForList(
                "SELECT login || '|' || enabled || '|' || is_external FROM employees ORDER BY login", String.class);

        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM ad_sync_log");
        properties.setAdEnabled(false);
        SyncCounts without = syncNow().counts();

        assertThat(without).isEqualTo(with);
        assertThat(jdbc.queryForList("SELECT login || '|' || enabled || '|' || is_external FROM employees"
                + " ORDER BY login", String.class)).containsExactlyElementsOf(employeesWith);
    }

    @Test
    void forceReadsEverybodyAgain() {
        syncNow();
        directory.photoReads.clear();

        PhotoRun forced = photoSync.run(null, SyncTrigger.MANUAL, true);

        assertThat(forced.candidates()).isEqualTo(servable());
        assertThat(directory.photoReads).hasSize(servable());
        assertThat(forced.updated()).as("the same photos again: nothing new").isZero();
    }

    @Test
    void theDryRunCountsCandidatesWithoutReadingPhotos() {
        properties.setAdEnabled(false);
        syncNow();
        properties.setAdEnabled(true);

        assertThat(photoSync.countCandidates()).isEqualTo(servable());
        assertThat(directory.photoReads).isEmpty();
    }

    @Test
    void theDirectoryLinksTheStoredPhotos() {
        directory.setPhoto("mmint", VALID);
        directory.setPhoto("kkhaki", null);
        syncNow();
        String hash = photos.findById(employee("mmint").getId()).orElseThrow().getSha256();

        var mike = employeeDirectory.getAllEmployees().stream().filter(e -> e.getId().equals(employee("mmint").getId()))
                .findFirst().orElseThrow();
        assertThat(mike.getPhotoUrl()).isEqualTo("/api/photos/" + mike.getId() + "?v=" + hash.substring(0, 8));
        var khaki = employeeDirectory.getAllEmployees().stream()
                .filter(e -> e.getId().equals(employee("kkhaki").getId())).findFirst().orElseThrow();
        assertThat(khaki.getPhotoUrl()).doesNotStartWith("/api/photos/");
    }
}

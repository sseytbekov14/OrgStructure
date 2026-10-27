package cs_orgchart.ad.health;

import cs_orgchart.ad.client.AdErrorCode;
import cs_orgchart.ad.sync.AdSyncLog;
import cs_orgchart.ad.sync.AdSyncLogRepository;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AD sync in /actuator/health: never DOWN, so the overall status (and HTTP 200) does not depend on AD.
 */
class AdSyncHealthIndicatorTest {

    private final AdSyncLogRepository syncLog = mock(AdSyncLogRepository.class);
    private final AuthCircuitBreaker breaker = new AuthCircuitBreaker();

    private Health health(boolean enabled) {
        return new AdSyncHealthIndicator(syncLog, breaker, enabled).health();
    }

    private void lastRun(String status, String errorCode) {
        AdSyncLog row = AdSyncLog.builder().id(7L).status(status).triggerType("SCHEDULED").errorCode(errorCode)
                .finishedAt(Instant.parse("2026-10-27T10:00:00Z")).fetchedCount(41).skippedCount(2).build();
        when(syncLog.findFirstByStatusInOrderByIdDesc(any())).thenReturn(Optional.of(row));
        when(syncLog.findFirstByOrderByIdDesc()).thenReturn(Optional.of(row));
        when(syncLog.findFirstByStatusOrderByIdDesc(anyString()))
                .thenReturn("SUCCESS".equals(status) ? Optional.of(row) : Optional.empty());
    }

    @Test
    void disabledWithoutReadingTheLog() {
        assertThat(health(false).getStatus()).isEqualTo(AdSyncHealthIndicator.DISABLED);
    }

    @Test
    void unknownBeforeTheFirstSync() {
        when(syncLog.findFirstByStatusInOrderByIdDesc(any())).thenReturn(Optional.empty());
        when(syncLog.findFirstByStatusOrderByIdDesc(anyString())).thenReturn(Optional.empty());
        when(syncLog.findFirstByOrderByIdDesc()).thenReturn(Optional.empty());

        assertThat(health(true).getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void upAfterASuccessfulSync() {
        lastRun("SUCCESS", null);

        Health health = health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("employees", 41).containsEntry("skipped", 2)
                .containsEntry("running", false);
    }

    @Test
    void staleAfterAFailedSync() {
        lastRun("FAILED", "NETWORK");

        Health health = health(true);

        assertThat(health.getStatus()).isEqualTo(AdSyncHealthIndicator.STALE);
        assertThat(health.getDetails()).containsEntry("lastErrorCode", "NETWORK");
    }

    @Test
    void staleWhileSuspendedAfterInvalidCredentials() {
        lastRun("SUCCESS", null);
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");

        assertThat(health(true).getStatus()).isEqualTo(AdSyncHealthIndicator.STALE);
    }

    @Test
    void unreadableLogIsUnknownNotDown() {
        when(syncLog.findFirstByStatusInOrderByIdDesc(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(health(true).getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void staleAndDisabledNeitherChangeTheOverallStatusNorGive503() {
        SimpleStatusAggregator aggregator = new SimpleStatusAggregator();
        assertThat(aggregator.getAggregateStatus(Set.of(Status.UP, AdSyncHealthIndicator.STALE))).isEqualTo(Status.UP);
        assertThat(aggregator.getAggregateStatus(Set.of(Status.UP, AdSyncHealthIndicator.DISABLED))).isEqualTo(Status.UP);
        assertThat(aggregator.getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN))).isEqualTo(Status.UP);

        SimpleHttpCodeStatusMapper http = new SimpleHttpCodeStatusMapper(Map.of("STALE", 200, "DISABLED", 200));
        assertThat(http.getStatusCode(AdSyncHealthIndicator.STALE)).isEqualTo(200);
        assertThat(http.getStatusCode(AdSyncHealthIndicator.DISABLED)).isEqualTo(200);
    }
}

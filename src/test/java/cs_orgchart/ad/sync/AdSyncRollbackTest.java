package cs_orgchart.ad.sync;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * A failure after the writes rolls all of them back, and the FAILED row (written in its own transaction) survives.
 */
class AdSyncRollbackTest extends AdSyncTestSupport {

    @MockitoSpyBean
    private AdSyncWriter writer;

    @Test
    void failedRowSurvivesTheRolledBackSync() {
        doAnswer(call -> {
            SyncCounts written = (SyncCounts) call.callRealMethod();
            assertThat(written.inserted()).as("the rows were written in the sync transaction").isEqualTo(42);
            throw new IllegalStateException("simulated failure after the writes");
        }).when(writer).apply(any(SyncPlan.class), any(Instant.class));

        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);
        reset(writer);

        assertThat(outcome.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("INTERNAL");
        assertThat(count("employees")).as("rolled back").isZero();
        assertThat(count("employee_overrides")).isZero();
        AdSyncLog row = logRow(outcome.logId());
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getErrorCode()).isEqualTo("INTERNAL");
        assertThat(row.getErrorMessage()).contains("simulated failure after the writes");
        assertThat(row.getFetchedCount()).isEqualTo(41);
        assertThat(row.getFinishedAt()).isNotNull();
        assertThat(breaker.isSuspended()).isFalse();

        assertThat(service.sync(SyncTrigger.MANUAL).status()).as("the lock was released by the rollback")
                .isEqualTo(SyncStatus.SUCCESS);
    }
}

package cs_orgchart.ad.sync;

import cs_orgchart.ad.client.AdErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After invalid credentials only a manual run (or a restart) contacts AD again (spec v2.1 §7 step 1).
 */
class AuthCircuitBreakerTest {

    private static final Instant NOW = Instant.parse("2026-10-27T09:00:00Z");

    private final AuthCircuitBreaker breaker = new AuthCircuitBreaker(Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(SyncTrigger.class)
    void everyRunIsAllowedAtFirst(SyncTrigger trigger) {
        assertThat(breaker.tryStart(trigger)).isTrue();
        assertThat(breaker.isSuspended()).isFalse();
    }

    @Test
    void authFailureSuspendsScheduledAndStartupRuns() {
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");

        assertThat(breaker.isSuspended()).isTrue();
        assertThat(breaker.suspension()).hasValueSatisfying(s -> {
            assertThat(s.since()).isEqualTo(NOW);
            assertThat(s.reason()).isEqualTo("invalid credentials");
        });
        assertThat(breaker.tryStart(SyncTrigger.SCHEDULED)).isFalse();
        assertThat(breaker.tryStart(SyncTrigger.STARTUP)).isFalse();
        assertThat(breaker.tryStart(SyncTrigger.SCHEDULED)).as("still suspended, no retry").isFalse();
    }

    @Test
    void manualRunIsAllowedAndLiftsTheSuspension() {
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");

        assertThat(breaker.tryStart(SyncTrigger.MANUAL)).isTrue();

        assertThat(breaker.isSuspended()).isFalse();
        assertThat(breaker.tryStart(SyncTrigger.SCHEDULED)).isTrue();
    }

    @Test
    void manualRunWithStillWrongCredentialsSuspendsAgain() {
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");
        breaker.tryStart(SyncTrigger.MANUAL);
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");

        assertThat(breaker.tryStart(SyncTrigger.SCHEDULED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AdErrorCode.class, names = "AUTH", mode = EnumSource.Mode.EXCLUDE)
    void otherFailuresDoNotSuspend(AdErrorCode code) {
        breaker.recordFailure(code, "failure");

        assertThat(breaker.isSuspended()).isFalse();
        assertThat(breaker.tryStart(SyncTrigger.SCHEDULED)).isTrue();
    }

    @Test
    void successClearsTheSuspension() {
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");
        breaker.recordSuccess();

        assertThat(breaker.tryStart(SyncTrigger.STARTUP)).isTrue();
    }

    @Test
    void aRestartStartsWithoutSuspension() {
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");

        AuthCircuitBreaker afterRestart = new AuthCircuitBreaker();

        assertThat(afterRestart.tryStart(SyncTrigger.STARTUP)).isTrue();
    }
}

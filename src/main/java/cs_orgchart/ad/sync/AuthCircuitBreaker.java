package cs_orgchart.ad.sync;

import cs_orgchart.ad.client.AdErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Protects the bind account from being locked (spec v3 §7 step 1): after a run that failed with invalid credentials
 * ({@link AdErrorCode#AUTH}), scheduled and startup runs are suspended until a manual run or a restart of the
 * application (the state is in memory only). Nothing is retried in a loop: each suspended run is refused without
 * contacting AD.
 */
@Slf4j
public class AuthCircuitBreaker {

    /** Since when and why runs are suspended */
    public record Suspension(Instant since, String reason) {
    }

    private final Clock clock;
    private volatile Suspension suspension;

    public AuthCircuitBreaker() {
        this(Clock.systemUTC());
    }

    public AuthCircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    /**
     * Whether a run with this trigger may contact AD. A manual run is always allowed and lifts the suspension (if
     * its credentials are still wrong, it suspends the runs again).
     */
    public synchronized boolean tryStart(SyncTrigger trigger) {
        if (suspension == null) {
            return true;
        }
        if (trigger == SyncTrigger.MANUAL) {
            log.info("AD sync: manual run, the suspension after invalid credentials (since {}) is lifted",
                    suspension.since());
            suspension = null;
            return true;
        }
        log.warn("AD sync: {} run skipped, suspended since {} after invalid credentials; fix AD_LDAP_BIND_DN /"
                + " AD_LDAP_BIND_PASSWORD, then run a manual sync or restart", trigger, suspension.since());
        return false;
    }

    /** The outcome of a run that failed; only {@link AdErrorCode#AUTH} suspends */
    public synchronized void recordFailure(AdErrorCode code, String message) {
        if (code == AdErrorCode.AUTH) {
            suspension = new Suspension(clock.instant(), message);
            log.error("AD sync: invalid credentials, scheduled and startup runs are suspended until a manual sync or"
                    + " a restart");
        }
    }

    public synchronized void recordSuccess() {
        suspension = null;
    }

    public Optional<Suspension> suspension() {
        return Optional.ofNullable(suspension);
    }

    public boolean isSuspended() {
        return suspension != null;
    }
}

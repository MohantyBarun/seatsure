package com.seatsure.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When a background job next has work, so its frequent schedule can skip the database the rest of
 * the time.
 *
 * <p>Why: the free Neon database stops (and stops costing compute hours) after 5 minutes without
 * queries. A job that polls every few seconds "just in case" keeps it running around the clock and
 * uses up the monthly allowance. With this, a job's tick is an in-memory check, and the database is
 * only queried when something is due:
 * <ul>
 *   <li>Code that creates work says when it will be due ({@link #expect}), e.g. a hold's expiry time
 *       or a failed delivery's retry time.</li>
 *   <li>After each sweep the job asks the database when its next item is due ({@link #swept}), so
 *       nothing is lost if a hint was missed.</li>
 *   <li>As a safety net it sweeps anyway at the next idle-sweep boundary (e.g. on the hour). The
 *       boundary is aligned to the clock, so every job's safety sweep falls in the same minute and
 *       wakes the database once, not once per job.</li>
 * </ul>
 * The first tick after startup always sweeps, picking up whatever a previous run left behind.
 */
public class DueWork {

    /** If a sweep fails before reporting back (e.g. the database is unreachable), try again this soon. */
    static final Duration RETRY_FAILED_SWEEP = Duration.ofMinutes(1);

    private final Clock clock;
    private final Duration idleSweep;
    private Instant next;
    /** The earliest hint since the current sweep started: the sweep's result must not override it. */
    private Instant hintDuringSweep;

    public DueWork(Clock clock, Duration idleSweep) {
        if (idleSweep.isNegative() || idleSweep.isZero()) {
            throw new IllegalArgumentException("idleSweep must be positive");
        }
        this.clock = clock;
        this.idleSweep = idleSweep;
        this.next = clock.instant();
    }

    /** Something will need doing at {@code at} (or now, if that's in the past). */
    public synchronized void expect(Instant at) {
        next = earlier(next, at);
        hintDuringSweep = earlier(hintDuringSweep, at);
    }

    /** True if the job should sweep now. The caller then sweeps and reports back with {@link #swept}. */
    public synchronized boolean claim() {
        Instant now = clock.instant();
        if (now.isBefore(next)) {
            return false;
        }
        next = now.plus(RETRY_FAILED_SWEEP); // replaced by swept(); stays if the sweep throws
        hintDuringSweep = null;
        return true;
    }

    /**
     * The sweep finished. {@code nextKnown} is when the database says the next item is due (empty
     * if nothing is waiting); the next check is that or the next idle-sweep boundary, whichever is
     * sooner, unless a hint during the sweep asked for an even earlier one.
     */
    public synchronized void swept(Optional<Instant> nextKnown) {
        Instant idle = nextIdleSweep(clock.instant());
        Instant candidate = nextKnown.filter(t -> t.isBefore(idle)).orElse(idle);
        next = earlier(candidate, hintDuringSweep);
        hintDuringSweep = null;
    }

    private static Instant earlier(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    public synchronized Instant nextCheck() {
        return next;
    }

    /** The first multiple of the idle-sweep interval (since the epoch) after {@code now}. */
    Instant nextIdleSweep(Instant now) {
        long step = idleSweep.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), step) * step + step);
    }
}

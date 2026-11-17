package com.seatsure.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** When a background job may touch the database: on a hint, at the next known item, or at the idle sweep. */
class DueWorkTest {

    private static final Instant START = Instant.parse("2026-11-17T10:20:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final DueWork work = new DueWork(clock, Duration.ofHours(1));

    @Test
    @DisplayName("the first tick after startup sweeps, then nothing until the next hour while idle")
    void idle_sweepsOnceAnHour() {
        assertThat(work.claim()).isTrue();
        work.swept(Optional.empty());

        assertThat(work.nextCheck()).isEqualTo(Instant.parse("2026-11-17T11:00:00Z"));
        clock.advance(Duration.ofMinutes(39));
        assertThat(work.claim()).isFalse();
        clock.advance(Duration.ofMinutes(1));
        assertThat(work.claim()).isTrue();
    }

    @Test
    @DisplayName("idle sweeps fall on clock boundaries, so every job's sweep wakes the database in the same minute")
    void idleSweep_isAlignedToTheClock() {
        DueWork other = new DueWork(new MutableClock(START.plusSeconds(1234)), Duration.ofHours(1));
        other.claim();
        other.swept(Optional.empty());
        work.claim();
        work.swept(Optional.empty());

        assertThat(other.nextCheck()).isEqualTo(work.nextCheck());
    }

    @Test
    @DisplayName("a hint brings the next check forward; it can't push it back")
    void hints() {
        work.claim();
        work.swept(Optional.empty()); // next: 11:00

        work.expect(START.plus(Duration.ofMinutes(10)));
        assertThat(work.nextCheck()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        work.expect(START.plus(Duration.ofMinutes(30)));
        assertThat(work.nextCheck()).isEqualTo(START.plus(Duration.ofMinutes(10)));

        clock.advance(Duration.ofMinutes(10));
        assertThat(work.claim()).isTrue();
    }

    @Test
    @DisplayName("after a sweep, the next item the database knows about is checked on time")
    void nextKnownItem_isCheckedOnTime() {
        work.claim();
        work.swept(Optional.of(START.plus(Duration.ofMinutes(5))));
        assertThat(work.nextCheck()).isEqualTo(START.plus(Duration.ofMinutes(5)));

        work.claim();
        work.swept(Optional.of(START.plus(Duration.ofHours(5))));
        assertThat(work.nextCheck()).as("never later than the idle sweep").isEqualTo(Instant.parse("2026-11-17T11:00:00Z"));
    }

    @Test
    @DisplayName("a hint that arrives during a sweep isn't overwritten by the sweep's result")
    void hintDuringASweep_survives() {
        assertThat(work.claim()).isTrue();
        work.expect(START.plus(Duration.ofSeconds(30))); // e.g. a delivery failed mid-sweep
        work.swept(Optional.empty());

        assertThat(work.nextCheck()).isEqualTo(START.plus(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("a sweep that fails before reporting back is retried a minute later, not at the next idle sweep")
    void failedSweep_isRetriedSoon() {
        assertThat(work.claim()).isTrue();
        // the sweep throws: swept() is never called

        assertThat(work.claim()).isFalse();
        assertThat(work.nextCheck()).isEqualTo(START.plus(DueWork.RETRY_FAILED_SWEEP));
    }

    @Test
    void hintInThePast_isDueNow() {
        work.claim();
        work.swept(Optional.empty());
        work.expect(START.minusSeconds(5));

        assertThat(work.claim()).isTrue();
    }

    @Test
    void idleSweepMustBePositive() {
        assertThatThrownBy(() -> new DueWork(clock, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

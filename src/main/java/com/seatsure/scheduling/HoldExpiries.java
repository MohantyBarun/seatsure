package com.seatsure.scheduling;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * When the next seat hold expires. Holds are created in two places (a customer holding seats, and
 * the waitlist offering freed seats), so both tell this one schedule, and the expiry job reads it.
 */
@Component
public class HoldExpiries extends DueWork {

    public HoldExpiries(Clock clock, @Value("${app.jobs.idle-sweep:PT1H}") Duration idleSweep) {
        super(clock, idleSweep);
    }
}

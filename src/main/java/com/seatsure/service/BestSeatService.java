package com.seatsure.service;

import com.seatsure.dto.CatalogDtos.BestSeats;
import com.seatsure.dto.CatalogDtos.SeatSuggestion;
import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.Show;
import com.seatsure.entity.ShowPrice;
import com.seatsure.entity.ShowSeatStatus;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.repository.SeatHoldRepository;
import com.seatsure.repository.SeatSlot;
import com.seatsure.repository.ShowPriceRepository;
import com.seatsure.repository.ShowRepository;
import com.seatsure.repository.ShowSeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * "Pick for me": finds the best free seats for a group ({@link BestSeatPicker}) and can hold them
 * in the same request.
 *
 * <p><b>Requests for the same show queue up instead of racing.</b> If they raced, everyone in a
 * burst would read the same hall, choose the same best block, and all but one would lose and have
 * to choose again (the load test measured half the requests of a 100-customer burst failing that
 * way). So choosing and holding happen together under a per-show lock: each customer reads the hall
 * after the previous one has committed, and gets the best block still free. First come, best seats.
 *
 * <p>The lock is taken twice, deliberately:
 * <ol>
 *   <li>A lock in this JVM (one of {@value #STRIPES} fair stripes, by show), taken <i>before</i> a
 *       database connection is borrowed. Waiters therefore don't sit on connections from the small
 *       pool, and the seat map keeps loading for everyone else during a burst.</li>
 *   <li>A PostgreSQL advisory lock for the show, taken first in the transaction, so requests on
 *       different app instances queue too.</li>
 * </ol>
 * Then the ordinary {@link HoldService#create} locks and checks exactly the chosen seats, as for
 * any hold. Seats picked by hand don't queue, so a best-seat request can still lose a seat to one:
 * that transaction rolls back and it chooses again (rare, so a few attempts are plenty). The
 * advisory lock is only ever taken first and only here, so it can't create a deadlock.
 */
@Slf4j
@Service
public class BestSeatService {

    static final int MAX_SUGGESTIONS = 3;
    /** Losing to seats picked by hand this many times in a row means the show is selling out right now. */
    static final int HOLD_ATTEMPTS = 3;
    static final int STRIPES = 64;
    /** First half of the advisory lock key: "best seats" in hex-speak, so it can't clash with other uses. */
    static final int ADVISORY_NAMESPACE = 0x5EA75;

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final SeatHoldRepository seatHolds;
    private final ShowPriceRepository prices;
    private final HoldService holds;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Duration queueTimeout;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final Counter retries;

    public BestSeatService(ShowRepository shows, ShowSeatRepository showSeats, SeatHoldRepository seatHolds,
                           ShowPriceRepository prices, HoldService holds, Clock clock,
                           PlatformTransactionManager transactionManager,
                           @Value("${app.holds.best-queue-timeout:PT15S}") Duration queueTimeout, MeterRegistry meters) {
        this.shows = shows;
        this.showSeats = showSeats;
        this.seatHolds = seatHolds;
        this.prices = prices;
        this.holds = holds;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.queueTimeout = queueTimeout;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock(true); // fair: first come, first served
        }
        this.retries = Counter.builder("holds.best.retries")
                .description("Times 'pick for me' lost its seats to seats picked by hand and chose again").register(meters);
    }

    /** The best places to sit right now. Nothing is reserved: they can be gone a moment later. */
    public BestSeats suggest(Long showId, int count, SeatCategory category) {
        Show show = shows.findById(showId).orElseThrow(() -> ResourceNotFoundException.of("Show", showId));
        if (!show.isBookable(clock.instant())) {
            return new BestSeats(showId, count, category, List.of());
        }
        Map<SeatCategory, Integer> priceOf = prices.findByShowId(showId).stream()
                .collect(Collectors.toMap(ShowPrice::getCategory, ShowPrice::getPricePaise));
        List<SeatSuggestion> suggestions = rank(showId, count, category, null).stream()
                .limit(MAX_SUGGESTIONS)
                .map(pick -> new SeatSuggestion(pick.rowLabel(), pick.category(), pick.seatIds(),
                        pick.seats().stream().map(SeatSlot::label).toList(), (int) Math.round(pick.score()),
                        pick.reasons(), priceOf.getOrDefault(pick.category(), 0) * count))
                .toList();
        return new BestSeats(showId, count, category, suggestions);
    }

    /**
     * Holds the best {@code count} seats together for this customer. Like any hold, it replaces
     * the one they already have for the show, so those seats count as free when choosing.
     */
    public HoldView holdBest(Long userId, Long showId, int count, SeatCategory category) {
        Show show = shows.findById(showId).orElseThrow(() -> ResourceNotFoundException.of("Show", showId));
        if (!show.isBookable(clock.instant())) {
            throw new BadRequestException("This show isn't taking bookings");
        }
        ReentrantLock queue = stripes[Math.floorMod(Long.hashCode(showId), STRIPES)];
        if (!acquire(queue)) {
            throw new ConflictException("Lots of people are picking seats for this show right now. Please try again.");
        }
        try {
            for (int attempt = 1; attempt <= HOLD_ATTEMPTS; attempt++) {
                try {
                    return tx.execute(status -> chooseAndHold(userId, showId, count, category));
                } catch (SeatsUnavailableException e) {
                    retries.increment();
                    log.info("Best seats for show {} were picked by hand first (attempt {} of {}); choosing again",
                            showId, attempt, HOLD_ATTEMPTS);
                }
            }
        } finally {
            queue.unlock();
        }
        throw new ConflictException("Seats for this show are going fast. Please try again, or pick them on the seat map.");
    }

    /** One attempt, in one transaction: queue for the show, read the hall, hold its best block. */
    private HoldView chooseAndHold(Long userId, Long showId, int count, SeatCategory category) {
        shows.lockBestSeatQueue(ADVISORY_NAMESPACE, showId);
        Long ownHoldId = seatHolds.findActiveId(userId, showId).orElse(null);
        List<BestSeatPicker.Pick> picks = rank(showId, count, category, ownHoldId);
        if (picks.isEmpty()) {
            throw new ConflictException(noneLeft(count, category));
        }
        return holds.create(userId, showId, picks.get(0).seatIds()); // joins this transaction
    }

    private boolean acquire(ReentrantLock queue) {
        try {
            return queue.tryLock(queueTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<BestSeatPicker.Pick> rank(Long showId, int count, SeatCategory category, Long ownHoldId) {
        Predicate<SeatSlot> free = slot -> slot.status() == ShowSeatStatus.AVAILABLE
                || (ownHoldId != null && ownHoldId.equals(slot.holdId()));
        return BestSeatPicker.rank(showSeats.findSlots(showId), count, category, free);
    }

    private static String noneLeft(int count, SeatCategory category) {
        String kind = category == null ? "" : switch (category) {
            case RECLINER -> "Recliner ";
            case PRIME -> "Prime ";
            case CLASSIC -> "Classic ";
        };
        return count == 1 ? "No " + kind + "seats are left for this show."
                : "There aren't " + count + " " + kind + "seats together any more. Try fewer seats, or pick them on the seat map.";
    }
}

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
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * "Pick for me": finds the best free seats for a group ({@link BestSeatPicker}) and can hold them
 * in the same request.
 *
 * <p>Choosing takes no locks. The hall is read as it is now, the best block is chosen, and the
 * ordinary {@link HoldService#create} then locks exactly those seats and checks they're still free.
 * If someone took one in between, that transaction rolls back and this reads the hall again and
 * tries the next best block. So "pick for me" obeys the same locking rules as picking by hand and
 * can't hold a seat twice or deadlock; two customers who are offered the same seats at the same
 * moment simply end up in the best and second-best places.
 *
 * <p>Deliberately not transactional: every attempt must be its own transaction and see fresh rows.
 */
@Slf4j
@Service
public class BestSeatService {

    static final int MAX_SUGGESTIONS = 3;
    /** Losing this many races in a row means the show is selling out right now; the customer is told to retry. */
    static final int HOLD_ATTEMPTS = 5;

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final SeatHoldRepository seatHolds;
    private final ShowPriceRepository prices;
    private final HoldService holds;
    private final Clock clock;
    private final Counter retries;

    public BestSeatService(ShowRepository shows, ShowSeatRepository showSeats, SeatHoldRepository seatHolds,
                           ShowPriceRepository prices, HoldService holds, Clock clock, MeterRegistry meters) {
        this.shows = shows;
        this.showSeats = showSeats;
        this.seatHolds = seatHolds;
        this.prices = prices;
        this.holds = holds;
        this.clock = clock;
        this.retries = Counter.builder("holds.best.retries")
                .description("Times 'pick for me' lost its seats to another customer and chose again").register(meters);
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
        for (int attempt = 1; attempt <= HOLD_ATTEMPTS; attempt++) {
            Long ownHoldId = seatHolds.findActiveId(userId, showId).orElse(null);
            List<BestSeatPicker.Pick> picks = rank(showId, count, category, ownHoldId);
            if (picks.isEmpty()) {
                throw new ConflictException(noneLeft(count, category));
            }
            try {
                return holds.create(userId, showId, picks.get(0).seatIds());
            } catch (SeatsUnavailableException e) {
                retries.increment();
                log.info("Best seats for show {} were taken first (attempt {} of {}); choosing again", showId, attempt,
                        HOLD_ATTEMPTS);
            }
        }
        throw new ConflictException("Seats for this show are going fast. Please try again, or pick them on the seat map.");
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

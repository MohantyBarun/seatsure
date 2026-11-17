package com.seatsure.service;

import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.Show;
import com.seatsure.entity.ShowSeatStatus;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.repository.SeatHoldRepository;
import com.seatsure.repository.SeatSlot;
import com.seatsure.repository.ShowPriceRepository;
import com.seatsure.repository.ShowRepository;
import com.seatsure.repository.ShowSeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The queue-and-retry rules of "pick for me", with the database mocked (the real race is in BestSeatsIntegrationTest). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BestSeatServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T06:00:00Z");
    private static final long SHOW = 7L;
    private static final long USER = 3L;

    @Mock private ShowRepository shows;
    @Mock private ShowSeatRepository showSeats;
    @Mock private SeatHoldRepository seatHolds;
    @Mock private ShowPriceRepository prices;
    @Mock private HoldService holds;
    @Mock private PlatformTransactionManager transactions;
    @Mock private Show show;

    private BestSeatService service;
    private final List<SeatSlot> hall = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(show.isBookable(NOW)).thenReturn(true);
        when(shows.findById(SHOW)).thenReturn(Optional.of(show));
        when(seatHolds.findActiveId(USER, SHOW)).thenReturn(Optional.empty());
        // One row of 6: seats 1-6, ids 101-106, all free
        for (int n = 1; n <= 6; n++) {
            hall.add(new SeatSlot(100L + n, "A", n, n, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE, null));
        }
        when(showSeats.findSlots(SHOW)).thenAnswer(inv -> List.copyOf(hall));
        service = new BestSeatService(shows, showSeats, seatHolds, prices, holds,
                Clock.fixed(NOW, ZoneOffset.UTC), transactions, Duration.ofSeconds(1), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("queues for the show before reading the hall, then holds its best block")
    void queuesThenHoldsTheBestBlock() {
        HoldView held = mock(HoldView.class);
        when(holds.create(USER, SHOW, List.of(103L, 104L))).thenReturn(held);

        assertThat(service.holdBest(USER, SHOW, 2, null)).isSameAs(held);

        InOrder order = inOrder(shows, showSeats, holds);
        order.verify(shows).lockBestSeatQueue(BestSeatService.ADVISORY_NAMESPACE, SHOW);
        order.verify(showSeats).findSlots(SHOW);
        order.verify(holds).create(USER, SHOW, List.of(103L, 104L));
    }

    @Test
    @DisplayName("losing a seat to someone who picked it by hand: rolls back and takes the next best block")
    void lostToAHandPickedSeat_choosesAgain() {
        HoldView held = mock(HoldView.class);
        when(holds.create(USER, SHOW, List.of(103L, 104L))).thenAnswer(inv -> {
            hall.set(3, new SeatSlot(104L, "A", 4, 4, SeatCategory.CLASSIC, ShowSeatStatus.HELD, 99L));
            throw new SeatsUnavailableException(List.of(104L));
        });
        when(holds.create(USER, SHOW, List.of(102L, 103L))).thenReturn(held);

        assertThat(service.holdBest(USER, SHOW, 2, null)).isSameAs(held);
        verify(transactions).rollback(any());
        verify(transactions).commit(any());
    }

    @Test
    void losingEveryAttempt_asksTheCustomerToTryAgain() {
        when(holds.create(eq(USER), eq(SHOW), anyList())).thenThrow(new SeatsUnavailableException(List.of(103L)));

        assertThatThrownBy(() -> service.holdBest(USER, SHOW, 2, null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("going fast");
        verify(holds, times(BestSeatService.HOLD_ATTEMPTS)).create(eq(USER), eq(SHOW), anyList());
    }

    @Test
    @DisplayName("no block of that size left: 409 straight away, no retries")
    void noBlockLeft() {
        assertThatThrownBy(() -> service.holdBest(USER, SHOW, 7, null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("aren't 7 seats together");
        verify(holds, never()).create(any(), any(), anyList());
    }

    @Test
    @DisplayName("seats the customer already holds count as free when choosing again")
    void ownHoldCountsAsFree() {
        hall.replaceAll(s -> new SeatSlot(s.seatId(), "A", s.seatNumber(), s.gridCol(), SeatCategory.CLASSIC,
                ShowSeatStatus.HELD, 55L));
        when(seatHolds.findActiveId(USER, SHOW)).thenReturn(Optional.of(55L));

        service.holdBest(USER, SHOW, 6, null);

        verify(holds).create(USER, SHOW, List.of(101L, 102L, 103L, 104L, 105L, 106L));
    }
}

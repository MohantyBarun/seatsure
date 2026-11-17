package com.seatsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.dto.CheckoutDtos.HeldSeat;
import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.exception.ConflictException;
import com.seatsure.service.BestSeatService;
import com.seatsure.service.HoldService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import com.seatsure.support.TestFixtures.ShowFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Pick for me" against real PostgreSQL: the best seats are suggested and held, and customers who
 * are all offered the same best seats at the same moment each end up with their own.
 */
@FullAppIntegrationTest
class BestSeatsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BestSeatService bestSeats;
    @Autowired private HoldService holds;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("suggestions are public: the centre of the best row first, one option per row")
    void suggestions_overHttp() throws Exception {
        ShowFixture show = fixtures.show(3, 10); // rows A-C; B is nearest the ideal distance

        mockMvc.perform(get("/shows/{id}/best-seats", show.showId()).param("count", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.suggestions.length()").value(3))
                .andExpect(jsonPath("$.suggestions[0].labels[0]").value("B5"))
                .andExpect(jsonPath("$.suggestions[0].labels[1]").value("B6"))
                .andExpect(jsonPath("$.suggestions[0].category").value("CLASSIC"))
                .andExpect(jsonPath("$.suggestions[0].ticketsPaise").value(2 * TestFixtures.CLASSIC_PRICE))
                .andExpect(jsonPath("$.suggestions[0].reasons[0]").value("In the centre of the row"))
                .andExpect(jsonPath("$.suggestions[2].rowLabel").value("A"));
        // Nothing was reserved by asking
        assertThat(available(show.showId())).isEqualTo(30);

        mockMvc.perform(get("/shows/{id}/best-seats", show.showId()).param("count", "11"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/shows/{id}/best-seats", show.showId()).param("category", "RECLINER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(0));
        mockMvc.perform(get("/shows/{id}/best-seats", 999_999_999L)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("one request picks and holds; the next customer gets the next best seats")
    void holdBest_overHttp() throws Exception {
        ShowFixture show = fixtures.show(3, 10);
        String alice = "Bearer " + fixtures.customer().accessToken();
        String bob = "Bearer " + fixtures.customer().accessToken();
        String key = UUID.randomUUID().toString();

        JsonNode first = json(mockMvc.perform(post("/shows/{id}/holds/best", show.showId()).header("Authorization", alice)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seats[0].label").value("B5"))
                .andExpect(jsonPath("$.seats[1].label").value("B6"))
                .andReturn().getResponse().getContentAsString());

        // A retry of the same request (same key) returns the same hold instead of choosing again
        mockMvc.perform(post("/shows/{id}/holds/best", show.showId()).header("Authorization", alice)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").value(first.get("holdId").asLong()));

        JsonNode second = json(mockMvc.perform(post("/shows/{id}/holds/best", show.showId()).header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(second.get("seats").findValuesAsText("label")).doesNotContain("B5", "B6").hasSize(2);
        assertThat(available(show.showId())).isEqualTo(26);

        mockMvc.perform(post("/shows/{id}/holds/best", show.showId()).header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/shows/{id}/holds/best", show.showId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":2}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no row with that many seats together: 409 that says so, and nothing is held")
    void noBlockLeft_is409() throws Exception {
        ShowFixture show = fixtures.show(1, 6);
        Long other = fixtures.customer().user().id();
        holds.create(other, show.showId(), List.of(show.seatIds().get(2))); // A3: leaves A1-A2 and A4-A6

        mockMvc.perform(post("/shows/{id}/holds/best", show.showId())
                        .header("Authorization", "Bearer " + fixtures.customer().accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("aren't 4 seats together")));
        assertThat(available(show.showId())).isEqualTo(5);
    }

    @Test
    @DisplayName("asking again replaces your hold, and the seats you were holding count as free")
    void askingAgain_replacesYourHold() {
        ShowFixture show = fixtures.show(1, 6);
        Long customer = fixtures.customer().user().id();

        HoldView two = bestSeats.holdBest(customer, show.showId(), 2, null);
        HoldView six = bestSeats.holdBest(customer, show.showId(), 6, null);

        assertThat(labels(two)).containsExactly("A3", "A4");
        assertThat(labels(six)).containsExactly("A1", "A2", "A3", "A4", "A5", "A6");
        assertThat(jdbc.queryForObject("SELECT status FROM seat_holds WHERE id = ?", String.class, two.holdId()))
                .isEqualTo("RELEASED");
        assertThat(available(show.showId())).isZero();
    }

    @Test
    @DisplayName("20 customers ask for the best pair at the same moment: all 20 get their own pair")
    void everyoneOfferedTheSameSeats_endsUpWithTheirOwn() throws Exception {
        int customers = 20;
        ShowFixture show = fixtures.show(8, 10); // 80 seats for 40 wanted: nobody should go without
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            users.add(fixtures.customer().user().id());
        }
        List<HoldView> won = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(customers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Long user : users) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        won.add(holdWithRetry(user, show.showId()));
                    } catch (Throwable t) {
                        failed.add(t);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failed).as("no deadlocks, lock timeouts or other errors").isEmpty();
        assertThat(won).hasSize(customers);
        List<Long> heldSeatIds = won.stream().flatMap(h -> h.seats().stream()).map(HeldSeat::seatId).toList();
        assertThat(heldSeatIds).as("no seat was given to two customers").doesNotHaveDuplicates().hasSize(2 * customers);
        for (HoldView hold : won) {
            List<String> pair = labels(hold);
            assertThat(pair.get(0).charAt(0)).as("a pair sits in one row: %s", pair).isEqualTo(pair.get(1).charAt(0));
            assertThat(Integer.parseInt(pair.get(1).substring(1)) - Integer.parseInt(pair.get(0).substring(1)))
                    .as("and side by side: %s", pair).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'HELD'",
                Long.class, show.showId())).isEqualTo(2L * customers);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE show_id = ? AND status = 'ACTIVE'",
                Long.class, show.showId())).isEqualTo((long) customers);
    }

    @Test
    void closedShow_suggestsNothingAndHoldsNothing() {
        ShowFixture show = fixtures.show(1, 4);
        Long customer = fixtures.customer().user().id();
        jdbc.update("UPDATE shows SET status = 'CANCELLED' WHERE id = ?", show.showId());

        assertThat(bestSeats.suggest(show.showId(), 2, null).suggestions()).isEmpty();
        assertThatThrownBy(() -> bestSeats.holdBest(customer, show.showId(), 2, null))
                .hasMessageContaining("isn't taking bookings");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * What the browser does: "seats are going fast, try again" (the service lost several races in
     * a row) is retried by the customer. Any other failure is a real one.
     */
    private HoldView holdWithRetry(Long userId, Long showId) {
        for (int i = 0; ; i++) {
            try {
                return bestSeats.holdBest(userId, showId, 2, null);
            } catch (ConflictException e) {
                if (i == 20 || !e.getMessage().contains("going fast")) {
                    throw e;
                }
            }
        }
    }

    private long available(Long showId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'",
                Long.class, showId);
    }

    private static List<String> labels(HoldView hold) {
        return hold.seats().stream().map(HeldSeat::label).toList();
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}

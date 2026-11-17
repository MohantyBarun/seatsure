package com.seatsure.service;

import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.ShowSeatStatus;
import com.seatsure.repository.SeatSlot;
import com.seatsure.service.BestSeatPicker.Pick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** The scoring behind "pick for me": centre, distance from the screen, staying together, no stranded seats. */
class BestSeatPickerTest {

    private static final Predicate<SeatSlot> AVAILABLE = slot -> slot.status() == ShowSeatStatus.AVAILABLE;

    @Test
    @DisplayName("an empty hall: the middle seats of the row about two-thirds back")
    void emptyHall_centreOfTheIdealRow() {
        List<SeatSlot> hall = hall(10, 10); // rows A (front) to J (back)

        Pick best = BestSeatPicker.rank(hall, 2, null, AVAILABLE).get(0);

        assertThat(labels(best)).containsExactly("G5", "G6"); // G is 6/9 = 0.67 of the way back
        assertThat(best.reasons()).containsExactly("In the centre of the row", "Ideal distance from the screen",
                "All 2 seats together");
        assertThat(best.score()).isGreaterThan(95);
    }

    @Test
    @DisplayName("one option per row, best first")
    void oneBlockPerRow_bestFirst() {
        List<Pick> picks = BestSeatPicker.rank(hall(10, 10), 2, null, AVAILABLE);

        assertThat(picks).hasSize(10);
        assertThat(picks).extracting(Pick::rowLabel).doesNotHaveDuplicates().startsWith("G", "F", "H");
        assertThat(picks).extracting(Pick::score).isSortedAccordingTo(Collections.reverseOrder());
        assertThat(picks.get(9).rowLabel()).isEqualTo("A"); // the front row is the worst place to sit
        assertThat(picks.get(9).reasons()).contains("Close to the screen");
    }

    @Test
    @DisplayName("seats are side by side: taken seats and aisles both break a block")
    void blocksNeverCrossTakenSeatsOrAisles() {
        // One row of 8 with an aisle after seat 4: grid columns 1-4 and 6-9
        List<SeatSlot> row = new ArrayList<>();
        for (int n = 1; n <= 8; n++) {
            row.add(slot("A", n, n <= 4 ? n : n + 1, SeatCategory.CLASSIC, n == 2 ? ShowSeatStatus.BOOKED : ShowSeatStatus.AVAILABLE));
        }

        assertThat(labels(BestSeatPicker.rank(row, 4, null, AVAILABLE).get(0))).containsExactly("A5", "A6", "A7", "A8");
        assertThat(labels(BestSeatPicker.rank(row, 2, null, AVAILABLE).get(0)))
                .as("A3-A4 and A5-A6 are equally central; A4-A5 would cross the aisle").containsExactly("A3", "A4");
        assertThat(BestSeatPicker.rank(row, 5, null, AVAILABLE)).isEmpty();
    }

    @Test
    @DisplayName("between equally central blocks, the one that strands no single seat wins")
    void prefersNotStrandingASeat() {
        // Row of 7 with seats 1 and 4 booked: free runs are 2-3 and 5-7.
        // 2-3 and 5-6 are equally far from the centre, but 5-6 would leave seat 7 on its own.
        List<SeatSlot> row = new ArrayList<>();
        Set<Integer> booked = Set.of(1, 4);
        for (int n = 1; n <= 7; n++) {
            row.add(slot("A", n, n, SeatCategory.CLASSIC, booked.contains(n) ? ShowSeatStatus.BOOKED : ShowSeatStatus.AVAILABLE));
        }

        assertThat(labels(BestSeatPicker.rank(row, 2, null, AVAILABLE).get(0))).containsExactly("A2", "A3");
    }

    @Test
    @DisplayName("a category filter keeps to that category, scored against the whole hall")
    void categoryFilter() {
        List<SeatSlot> hall = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            SeatCategory category = r < 4 ? SeatCategory.CLASSIC : r < 8 ? SeatCategory.PRIME : SeatCategory.RECLINER;
            for (int n = 1; n <= 10; n++) {
                hall.add(slot(String.valueOf((char) ('A' + r)), n, n, category, ShowSeatStatus.AVAILABLE));
            }
        }

        List<Pick> classic = BestSeatPicker.rank(hall, 3, SeatCategory.CLASSIC, AVAILABLE);

        assertThat(classic).extracting(Pick::category).containsOnly(SeatCategory.CLASSIC);
        assertThat(classic.get(0).rowLabel()).as("the Classic row furthest from the screen").isEqualTo("D");
        assertThat(BestSeatPicker.rank(hall, 3, SeatCategory.RECLINER, AVAILABLE).get(0).rowLabel()).isEqualTo("I");
    }

    @Test
    @DisplayName("seats the customer already holds count as free for them")
    void ownHeldSeatsCountAsFree() {
        List<SeatSlot> row = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            row.add(new SeatSlot((long) n, "A", n, n, SeatCategory.CLASSIC,
                    n == 3 || n == 4 ? ShowSeatStatus.HELD : ShowSeatStatus.AVAILABLE, n == 3 || n == 4 ? 77L : null));
        }

        assertThat(BestSeatPicker.rank(row, 3, null, AVAILABLE)).isEmpty();
        Predicate<SeatSlot> mine = AVAILABLE.or(slot -> Long.valueOf(77).equals(slot.holdId()));
        assertThat(labels(BestSeatPicker.rank(row, 4, null, mine).get(0))).containsExactly("A2", "A3", "A4", "A5");
    }

    @Test
    @DisplayName("the answer doesn't depend on the order the seats were read in")
    void orderOfInputDoesNotMatter() {
        List<SeatSlot> hall = hall(8, 12);
        List<SeatSlot> shuffled = new ArrayList<>(hall);
        Collections.shuffle(shuffled, new Random(7));

        assertThat(BestSeatPicker.rank(shuffled, 3, null, AVAILABLE)).isEqualTo(BestSeatPicker.rank(hall, 3, null, AVAILABLE));
    }

    @Test
    void nothingFree_orNothingAsked_isEmpty() {
        assertThat(BestSeatPicker.rank(hall(3, 4), 1, null, slot -> false)).isEmpty();
        assertThat(BestSeatPicker.rank(List.of(), 2, null, AVAILABLE)).isEmpty();
        assertThat(BestSeatPicker.rank(hall(3, 4), 5, null, AVAILABLE)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static long nextId = 1;

    private static SeatSlot slot(String row, int number, int col, SeatCategory category, ShowSeatStatus status) {
        return new SeatSlot(nextId++, row, number, col, category, status, null);
    }

    /** {@code rows} x {@code perRow} free Classic seats, no aisles; row A is nearest the screen. */
    private static List<SeatSlot> hall(int rows, int perRow) {
        List<SeatSlot> hall = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int n = 1; n <= perRow; n++) {
                hall.add(slot(String.valueOf((char) ('A' + r)), n, n, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE));
            }
        }
        return hall;
    }

    private static List<String> labels(Pick pick) {
        return pick.seats().stream().map(SeatSlot::label).toList();
    }
}

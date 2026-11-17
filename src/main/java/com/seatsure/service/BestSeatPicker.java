package com.seatsure.service;

import com.seatsure.entity.SeatCategory;
import com.seatsure.repository.SeatSlot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Ranks the free seats of a hall for a group that wants to sit together.
 *
 * <p>A candidate is {@code count} free seats side by side in one row (an aisle breaks a block).
 * Each is scored out of 100 on three things:
 * <ul>
 *   <li><b>Centre (45%):</b> how close the middle of the block is to the middle of the hall.</li>
 *   <li><b>Distance (45%):</b> how close the row is to about two-thirds of the way back, where the
 *       screen fills your view without making you look up.</li>
 *   <li><b>No stranded seats (10%):</b> a block that leaves a single free seat beside it, which
 *       nobody else will want, scores lower than one that doesn't.</li>
 * </ul>
 * Only the best block of each row is returned, so the options are really different places to sit
 * and not the same seats shifted by one.
 */
public final class BestSeatPicker {

    /** Where the best row is, from 0 (front row) to 1 (back row). */
    static final double IDEAL_DEPTH = 0.65;
    static final double CENTRE_WEIGHT = 0.45;
    static final double DISTANCE_WEIGHT = 0.45;
    static final double TIDY_WEIGHT = 0.10;

    private BestSeatPicker() {
    }

    /** A block of adjacent seats in one row, in seat order, with its score and what earned it. */
    public record Pick(String rowLabel, SeatCategory category, List<SeatSlot> seats, double score,
                       List<String> reasons) {

        public List<Long> seatIds() {
            return seats.stream().map(SeatSlot::seatId).toList();
        }
    }

    /**
     * @param hall     every seat of the show
     * @param count    seats wanted together
     * @param category only this category, or null for any
     * @param free     which seats can be taken
     * @return the best block of each row that has one, best first; empty if no row has
     *         {@code count} free seats together
     */
    public static List<Pick> rank(List<SeatSlot> hall, int count, SeatCategory category, Predicate<SeatSlot> free) {
        if (hall.isEmpty() || count < 1) {
            return List.of();
        }
        Map<String, List<SeatSlot>> rows = hall.stream().collect(Collectors.groupingBy(SeatSlot::rowLabel,
                () -> new TreeMap<>(CatalogService.ROW_ORDER), Collectors.toList()));
        int minCol = hall.stream().mapToInt(SeatSlot::gridCol).min().orElse(0);
        int maxCol = hall.stream().mapToInt(SeatSlot::gridCol).max().orElse(0);
        double hallCentre = (minCol + maxCol) / 2.0;
        double halfWidth = (maxCol - minCol) / 2.0;

        List<Pick> picks = new ArrayList<>();
        int rowIndex = 0;
        for (Map.Entry<String, List<SeatSlot>> entry : rows.entrySet()) {
            double depth = rows.size() == 1 ? IDEAL_DEPTH : (double) rowIndex / (rows.size() - 1);
            rowIndex++;
            List<SeatSlot> row = entry.getValue().stream().sorted(Comparator.comparingInt(SeatSlot::gridCol)).toList();
            Pick best = null;
            for (int from = 0; from + count <= row.size(); from++) {
                int to = from + count - 1; // inclusive
                if (!isFreeBlock(row, from, to, category, free)) {
                    continue;
                }
                Pick pick = score(entry.getKey(), row, from, to, depth, hallCentre, halfWidth, free);
                if (best == null || pick.score() > best.score()) { // on a tie, the block nearer the left stays
                    best = pick;
                }
            }
            if (best != null) {
                picks.add(best);
            }
        }
        // Stable sort: equal scores keep row order, so the result never depends on query order
        picks.sort(Comparator.comparingDouble(Pick::score).reversed());
        return List.copyOf(picks);
    }

    private static boolean isFreeBlock(List<SeatSlot> row, int from, int to, SeatCategory category,
                                       Predicate<SeatSlot> free) {
        for (int i = from; i <= to; i++) {
            SeatSlot seat = row.get(i);
            if (!free.test(seat) || (category != null && seat.category() != category)
                    || (i > from && seat.gridCol() != row.get(i - 1).gridCol() + 1)) {
                return false;
            }
        }
        return true;
    }

    private static Pick score(String rowLabel, List<SeatSlot> row, int from, int to, double depth, double hallCentre,
                              double halfWidth, Predicate<SeatSlot> free) {
        double blockCentre = (row.get(from).gridCol() + row.get(to).gridCol()) / 2.0;
        double centre = halfWidth == 0 ? 1 : 1 - Math.abs(blockCentre - hallCentre) / halfWidth;
        double distance = 1 - Math.abs(depth - IDEAL_DEPTH) / Math.max(IDEAL_DEPTH, 1 - IDEAL_DEPTH);
        int stranded = (strands(row, from, -1, free) ? 1 : 0) + (strands(row, to, 1, free) ? 1 : 0);
        double score = 100 * (CENTRE_WEIGHT * centre + DISTANCE_WEIGHT * distance + TIDY_WEIGHT * (1 - stranded / 2.0));

        List<String> reasons = new ArrayList<>();
        reasons.add(centre >= 0.85 ? "In the centre of the row" : centre >= 0.6 ? "Close to the centre" : "Towards the side");
        reasons.add(distance >= 0.85 ? "Ideal distance from the screen"
                : distance >= 0.6 ? "Good distance from the screen"
                : depth < IDEAL_DEPTH ? "Close to the screen" : "Far from the screen");
        if (to > from) {
            reasons.add("All " + (to - from + 1) + " seats together");
        }
        return new Pick(rowLabel, row.get(from).category(), List.copyOf(row.subList(from, to + 1)), score,
                List.copyOf(reasons));
    }

    /**
     * Whether taking the block would leave exactly one free seat next to its {@code edge}: the
     * neighbour is free, and the seat beyond it is taken, across an aisle, or doesn't exist.
     */
    private static boolean strands(List<SeatSlot> row, int edge, int step, Predicate<SeatSlot> free) {
        return isFreeNeighbour(row, edge, edge + step, free) && !isFreeNeighbour(row, edge + step, edge + 2 * step, free);
    }

    private static boolean isFreeNeighbour(List<SeatSlot> row, int of, int index, Predicate<SeatSlot> free) {
        if (index < 0 || index >= row.size()) {
            return false;
        }
        boolean adjacent = Math.abs(row.get(index).gridCol() - row.get(of).gridCol()) == 1;
        return adjacent && free.test(row.get(index));
    }
}

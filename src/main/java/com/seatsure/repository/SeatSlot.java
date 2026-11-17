package com.seatsure.repository;

import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.ShowSeatStatus;

/**
 * One seat of a show as plain values: where it is in the hall and whether it's free. Read without
 * entities, so reading again after losing a race always sees the current rows.
 */
public record SeatSlot(Long seatId, String rowLabel, int seatNumber, int gridCol, SeatCategory category,
                       ShowSeatStatus status, Long holdId) {

    public String label() {
        return rowLabel + seatNumber;
    }
}

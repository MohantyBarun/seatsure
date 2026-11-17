-- What the database looks like after the load test. k6 can only see responses; this checks that
-- the seats themselves add up. Run with the show ids from k6's summary:
--   psql -At -F '|' -v stampede=1 -v best=2 -v checkout=3 -f loadtest/invariants.sql
-- Output: one "name|value" line per check; loadtest/report.py compares them with k6's counters.
\set ON_ERROR_STOP on

-- Stampede: 300 customers, 100 seats
SELECT 'stampede_active_holds', count(*) FROM seat_holds WHERE show_id = :stampede AND status = 'ACTIVE';
SELECT 'stampede_seats_held', count(*) FROM show_seats WHERE show_id = :stampede AND status = 'HELD';
SELECT 'stampede_seats_claimed_by_holds', coalesce(sum(seat_count), 0)
FROM seat_holds WHERE show_id = :stampede AND status = 'ACTIVE';

-- Best seats: 100 customers, all offered the same pair first
SELECT 'best_active_holds', count(*) FROM seat_holds WHERE show_id = :best AND status = 'ACTIVE';
SELECT 'best_seats_held', count(*) FROM show_seats WHERE show_id = :best AND status = 'HELD';

-- Checkout: bookings end to end
SELECT 'checkout_confirmed_bookings', count(*) FROM bookings WHERE show_id = :checkout AND status = 'CONFIRMED';
SELECT 'checkout_seats_booked', count(*) FROM show_seats WHERE show_id = :checkout AND status = 'BOOKED';

-- Across every show: nothing sold or held twice, nothing orphaned, nothing refunded
SELECT 'seats_sold_twice', count(*) FROM (
    SELECT b.show_id, bs.seat_id FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
    WHERE b.status = 'CONFIRMED' GROUP BY b.show_id, bs.seat_id HAVING count(*) > 1) twice;
SELECT 'held_seats_without_an_active_hold', count(*)
FROM show_seats ss LEFT JOIN seat_holds h ON h.id = ss.hold_id
WHERE ss.status = 'HELD' AND h.status IS DISTINCT FROM 'ACTIVE';
SELECT 'booked_seats_without_a_booking', count(*)
FROM show_seats ss LEFT JOIN bookings b ON b.id = ss.booking_id
WHERE ss.status = 'BOOKED' AND b.status IS DISTINCT FROM 'CONFIRMED';
SELECT 'payments_refunded', count(*) FROM payments WHERE status = 'REFUNDED';

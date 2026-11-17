"""Turns a k6 summary and the database checks into a Markdown report; exits 1 if the seats don't add up.

    python loadtest/report.py summary.json invariants.txt > report.md
"""
import json
import sys


def main(summary_path, invariants_path):
    with open(summary_path, encoding="utf-8") as f:
        summary = json.load(f)
    metrics = summary["metrics"]
    db = {}
    with open(invariants_path, encoding="utf-8") as f:
        for line in f:
            if "|" in line:
                name, value = line.strip().split("|", 1)
                db[name] = int(value)

    def count(name):
        return int(metrics.get(name, {}).get("values", {}).get("count", 0))

    def ms(name, stat):
        value = metrics.get(name, {}).get("values", {}).get(stat)
        return "–" if value is None else f"{value:,.0f}"

    out = ["# SeatSure load test", ""]

    out += ["## Latency (ms)", "",
            "| Request | Count | Median | p95 | p99 | Max |",
            "|---|---:|---:|---:|---:|---:|"]
    rows = [
        ("Browse: city home", "browse_home"),
        ("Browse: showtimes", "browse_showtimes"),
        ("Browse: seat map", "browse_seat_map"),
        ("Browse: best seats", "browse_best_seats"),
        ("Stampede: hold 2 seats (300 at once)", "stampede_hold"),
        ("Best seats: pick and hold (100 at once)", "best_seats_hold"),
        ("Checkout: hold", "checkout_hold"),
        ("Checkout: start payment", "checkout_pay"),
        ("Checkout: approved -> ticket (webhook)", "checkout_paid_to_ticket"),
        ("Checkout: hold -> ticket, whole flow", "checkout_hold_to_ticket"),
    ]
    for label, name in rows:
        out.append(f"| {label} | {count(name):,} | {ms(name, 'med')} | {ms(name, 'p(95)')} | "
                   f"{ms(name, 'p(99)')} | {ms(name, 'max')} |")

    http = metrics.get("http_reqs", {}).get("values", {})
    failed = metrics.get("http_req_failed", {}).get("values", {}).get("rate", 0)
    checks = metrics.get("checks", {}).get("values", {})
    out += ["", f"{int(http.get('count', 0)):,} requests in total; {failed:.2%} unexpected statuses; "
                f"checks passed {int(checks.get('passes', 0)):,} / "
                f"{int(checks.get('passes', 0)) + int(checks.get('fails', 0)):,}.", ""]

    won, lost = count("stampede_holds_won"), count("stampede_holds_lost")
    best_won = count("best_seats_holds_won")
    booked = count("checkout_bookings")
    expectations = [
        ("Stampede: holds the API granted = active holds in the database", won, db["stampede_active_holds"]),
        ("Stampede: seats marked held = 2 x holds granted", 2 * won, db["stampede_seats_held"]),
        ("Stampede: seats marked held = seats the holds claim", db["stampede_seats_claimed_by_holds"],
         db["stampede_seats_held"]),
        ("Stampede: every customer got an answer (won + told taken = 300)", 300, won + lost),
        ("Best seats: all 100 customers got a pair", 100, best_won),
        ("Best seats: active holds in the database", best_won, db["best_active_holds"]),
        ("Best seats: seats marked held = 2 x holds", 2 * best_won, db["best_seats_held"]),
        ("Checkout: tickets issued = confirmed bookings", booked, db["checkout_confirmed_bookings"]),
        ("Checkout: seats booked = 2 x bookings", 2 * booked, db["checkout_seats_booked"]),
        ("Seats sold twice", 0, db["seats_sold_twice"]),
        ("Held seats without an active hold", 0, db["held_seats_without_an_active_hold"]),
        ("Booked seats without a booking", 0, db["booked_seats_without_a_booking"]),
        ("Payments refunded (none should be late or wrong)", 0, db["payments_refunded"]),
    ]
    ok = True
    out += ["## Correctness (database after the run)", "", "| Check | Expected | Actual | |", "|---|---:|---:|---|"]
    for label, expected, actual in expectations:
        passed = expected == actual
        ok &= passed
        out.append(f"| {label} | {expected:,} | {actual:,} | {'✅' if passed else '❌'} |")
    out += ["", f"Stampede: {won} customers won a pair, {lost} were told their seats were just taken. "
                f"Best seats: the browser retried {count('best_seats_client_retries')} time(s) after "
                f"\"seats are going fast\"."]

    print("\n".join(out))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # the report uses ✅/❌; Windows consoles default to cp1252
    sys.exit(main(sys.argv[1], sys.argv[2]))

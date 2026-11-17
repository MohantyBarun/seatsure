// SeatSure load test (k6). Four scenarios, one after another, against a running backend:
//
//   browse      100 requests/second of public reads for 30 s (home, showtimes, seat map, best seats)
//   stampede    300 customers try to hold 2 seats each in a 100-seat hall, all at the same moment
//   best_seats  100 customers press "hold the best seats for me" (2 seats) at the same moment
//   checkout    400 bookings end to end: hold -> pay -> gateway webhook -> ticket, 40 at a time
//
// setup() builds its own catalogue through the admin API and registers its own customers, so it
// needs an empty-ish database, an admin account, and rate limiting turned off (every request comes
// from one address). See .github/workflows/load-test.yml for the exact environment.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... loadtest/seatsure.js
//
// k6 measures latency; loadtest/report.py then checks the database for double bookings.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@seatsure.dev';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'Admin@123';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'summary.json';

const STAMPEDE_CUSTOMERS = 300;
const BEST_SEAT_CUSTOMERS = 100;
const CHECKOUT_VUS = 40;
const CHECKOUT_BOOKINGS = 400;
const CUSTOMERS = STAMPEDE_CUSTOMERS; // the largest scenario; the others reuse the same accounts

// A lost race for a seat is a correct answer (409), not a failed request
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: ['count', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate', exec: 'browse', startTime: '0s',
      rate: 100, timeUnit: '1s', duration: '30s', preAllocatedVUs: 50, maxVUs: 200,
    },
    stampede: {
      executor: 'per-vu-iterations', exec: 'stampede', startTime: '35s',
      vus: STAMPEDE_CUSTOMERS, iterations: 1, maxDuration: '60s',
    },
    best_seats: {
      executor: 'per-vu-iterations', exec: 'bestSeats', startTime: '70s',
      vus: BEST_SEAT_CUSTOMERS, iterations: 1, maxDuration: '60s',
    },
    checkout: {
      executor: 'shared-iterations', exec: 'checkout', startTime: '105s',
      vus: CHECKOUT_VUS, iterations: CHECKOUT_BOOKINGS, maxDuration: '4m',
    },
  },
  thresholds: {
    // Every response was the one the scenario expected (see the checks below)
    // Correctness only. Latency is reported, not gated: a shared CI runner's speed varies run to run.
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
};

// Latency per kind of request, in milliseconds
const browseHome = new Trend('browse_home', true);
const browseShowtimes = new Trend('browse_showtimes', true);
const browseSeatMap = new Trend('browse_seat_map', true);
const browseBestSeats = new Trend('browse_best_seats', true);
const stampedeHold = new Trend('stampede_hold', true);
const bestSeatsHold = new Trend('best_seats_hold', true);
const checkoutHold = new Trend('checkout_hold', true);
const checkoutPay = new Trend('checkout_pay', true);
const paidToTicket = new Trend('checkout_paid_to_ticket', true);
const holdToTicket = new Trend('checkout_hold_to_ticket', true);

const stampedeWon = new Counter('stampede_holds_won');
const stampedeLost = new Counter('stampede_holds_lost');
const bestSeatsWon = new Counter('best_seats_holds_won');
const bestSeatsClientRetries = new Counter('best_seats_client_retries');
const bookings = new Counter('checkout_bookings');

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function authed(token, idempotent) {
  const headers = { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
  if (idempotent) {
    // What the browser sends with every hold and payment
    headers['Idempotency-Key'] = `k6-${exec.vu.idInTest}-${exec.vu.iterationInInstance}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
  }
  return { headers };
}

function must(res, status, what) {
  if (res.status !== status) {
    fail(`${what}: expected ${status}, got ${res.status} ${res.body}`);
  }
  return res.json();
}

// ------------------------------------------------------------------ setup

function hall(rows, seatsPerRow) {
  const layout = [];
  for (let r = 0; r < rows; r++) {
    layout.push({ label: String.fromCharCode(65 + r), category: 'CLASSIC', seats: seatsPerRow, aisleAfter: [] });
  }
  return layout;
}

/** Seat ids in row order (A1, A2, ... B1, ...), so neighbours in the list are neighbours in the hall. */
function seatIdsOf(showId) {
  const map = must(http.get(`${BASE}/shows/${showId}/seats`), 200, 'seat map');
  return map.rows.flatMap((row) => row.seats.map((seat) => seat.id));
}

export function setup() {
  const run = Date.now().toString(36);
  const admin = must(http.post(`${BASE}/auth/login`, JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: JSON_HEADERS }), 200, 'admin login').accessToken;
  const asAdmin = authed(admin, false);

  const cityId = must(http.get(`${BASE}/admin/cities`, asAdmin), 200, 'cities')[0].id;
  const yesterday = new Date(Date.now() - 24 * 3600 * 1000).toISOString().slice(0, 10);
  const movieId = must(http.post(`${BASE}/admin/movies`, JSON.stringify({
    title: `Load Test ${run}`, synopsis: 'A movie that exists to be booked by a load test.', durationMinutes: 120,
    language: 'Hindi', certificate: 'UA', genres: ['Drama'], releaseDate: yesterday, posterUrl: null,
  }), asAdmin), 201, 'create movie').id;
  const cinemaId = must(http.post(`${BASE}/admin/cinemas`, JSON.stringify({
    cityId, name: `Load Test Cinema ${run}`, address: 'k6 Road',
  }), asAdmin), 201, 'create cinema').id;

  // Two days ahead on the hour: bookable, and well clear of the booking cut-off
  const startsAt = new Date(Math.ceil((Date.now() + 48 * 3600 * 1000) / 3600000) * 3600000).toISOString();
  const show = (name, rows, seatsPerRow) => {
    const screenId = must(http.post(`${BASE}/admin/cinemas/${cinemaId}/screens`,
      JSON.stringify({ name, rows: hall(rows, seatsPerRow) }), asAdmin), 201, `create screen ${name}`).id;
    const showId = must(http.post(`${BASE}/admin/shows`, JSON.stringify({
      movieId, screenId, startsAt, format: '2D', pricesPaise: { CLASSIC: 20000 },
    }), asAdmin), 201, `create show ${name}`).id;
    return { id: showId, seatsPerRow, seatIds: seatIdsOf(showId) };
  };
  const shows = {
    browse: show('Browse', 20, 20),      // 400 seats, only read
    stampede: show('Stampede', 10, 10),  // 100 seats for 300 customers wanting 2 each
    bestSeats: show('Best seats', 20, 20), // 400 seats for 100 customers wanting 2 each
    checkout: show('Checkout', 26, 40),  // 1,040 seats for 400 bookings of 2
  };

  // Customers, registered 20 at a time (each registration hashes a password with BCrypt)
  const tokens = [];
  for (let from = 0; from < CUSTOMERS; from += 20) {
    const batch = [];
    for (let i = from; i < Math.min(from + 20, CUSTOMERS); i++) {
      batch.push(['POST', `${BASE}/auth/register`, JSON.stringify({
        name: `Load Customer ${i}`, email: `load-${run}-${i}@seatsure.test`, phone: '', password: 'Secret#123',
      }), { headers: JSON_HEADERS }]);
    }
    http.batch(batch).forEach((res) => tokens.push(must(res, 201, 'register').accessToken));
  }
  return { cityId, movieId, shows, tokens };
}

/**
 * The customer for this iteration. Iteration numbers are unique within a scenario, and at most a few
 * dozen consecutive ones are in flight at once, so two concurrent requests never share an account
 * (a second hold by the same customer for the same show would replace the first).
 */
function tokenOf(data) {
  return data.tokens[exec.scenario.iterationInTest % data.tokens.length];
}

// ------------------------------------------------------------------ scenarios

export function browse(data) {
  const roll = Math.random();
  const showId = data.shows.browse.id;
  let res;
  if (roll < 0.3) {
    res = http.get(`${BASE}/cities/${data.cityId}/home`);
    browseHome.add(res.timings.duration);
  } else if (roll < 0.5) {
    res = http.get(`${BASE}/movies/${data.movieId}/showtimes?cityId=${data.cityId}`);
    browseShowtimes.add(res.timings.duration);
  } else if (roll < 0.85) {
    res = http.get(`${BASE}/shows/${showId}/seats`);
    browseSeatMap.add(res.timings.duration);
  } else {
    res = http.get(`${BASE}/shows/${showId}/best-seats?count=${1 + Math.floor(Math.random() * 4)}`);
    browseBestSeats.add(res.timings.duration);
  }
  check(res, { 'browse: 200': (r) => r.status === 200 });
}

/** Everyone at once, two neighbouring seats somewhere in a small hall: most must lose, nobody twice. */
export function stampede(data) {
  const { id, seatsPerRow, seatIds } = data.shows.stampede;
  const row = Math.floor(Math.random() * (seatIds.length / seatsPerRow));
  const col = Math.floor(Math.random() * (seatsPerRow - 1));
  const first = row * seatsPerRow + col;

  const res = http.post(`${BASE}/shows/${id}/holds`, JSON.stringify({ seatIds: [seatIds[first], seatIds[first + 1]] }),
    authed(tokenOf(data), true));
  stampedeHold.add(res.timings.duration);
  if (res.status === 201) stampedeWon.add(1);
  if (res.status === 409) stampedeLost.add(1);
  check(res, { 'stampede: held (201) or told the seats are taken (409)': (r) => r.status === 201 || r.status === 409 });
}

/** Everyone is offered the same best pair; each must still end up with a pair of their own. */
export function bestSeats(data) {
  const token = tokenOf(data);
  let res;
  // The server chooses again by itself up to 5 times; after that it asks the customer to retry
  for (let attempt = 0; attempt < 10; attempt++) {
    res = http.post(`${BASE}/shows/${data.shows.bestSeats.id}/holds/best`, JSON.stringify({ count: 2 }), authed(token, true));
    bestSeatsHold.add(res.timings.duration);
    if (res.status !== 409) break;
    bestSeatsClientRetries.add(1);
  }
  if (res.status === 201) bestSeatsWon.add(1);
  check(res, {
    'best seats: held': (r) => r.status === 201,
    'best seats: two seats side by side in one row': (r) => {
      if (r.status !== 201) return false;
      const [a, b] = r.json().seats.map((s) => s.label);
      return a[0] === b[0] && Number(b.slice(1)) - Number(a.slice(1)) === 1;
    },
  });
}

/** One booking, the way the browser does it: hold, start the payment, approve it, wait for the ticket. */
export function checkout(data) {
  const { id, seatIds } = data.shows.checkout;
  const token = tokenOf(data);
  const n = exec.scenario.iterationInTest; // each booking gets its own pair, so nothing here is a race
  const started = Date.now();

  const held = http.post(`${BASE}/shows/${id}/holds`, JSON.stringify({ seatIds: [seatIds[2 * n], seatIds[2 * n + 1]] }),
    authed(token, true));
  checkoutHold.add(held.timings.duration);
  if (!check(held, { 'checkout: seats held': (r) => r.status === 201 })) return;
  const holdId = held.json().holdId;

  const pay = http.post(`${BASE}/holds/${holdId}/payments`, null, authed(token, true));
  checkoutPay.add(pay.timings.duration);
  if (!check(pay, { 'checkout: payment started': (r) => r.status === 201 })) return;

  const approved = http.post(`${BASE}/mock-gateway/payments/${pay.json().gatewayRef}`, JSON.stringify({ outcome: 'SUCCESS' }),
    authed(token, false));
  if (!check(approved, { 'checkout: payment approved on the gateway page': (r) => r.status === 202 })) return;
  const paidAt = Date.now();

  // The booking appears when the gateway's signed webhook has been processed (it delivers every 2 s)
  let reference = null;
  for (let i = 0; i < 80 && !reference; i++) {
    sleep(0.25);
    const polled = http.get(`${BASE}/holds/${holdId}`, authed(token, false));
    reference = polled.status === 200 ? polled.json().bookingReference : null;
  }
  if (check(reference, { 'checkout: ticket issued within 20 s of paying': (ref) => Boolean(ref) })) {
    bookings.add(1);
    paidToTicket.add(Date.now() - paidAt);
    holdToTicket.add(Date.now() - started);
  }
}

// ------------------------------------------------------------------ summary

export function handleSummary(data) {
  const shows = data.setup_data ? data.setup_data.shows : {};
  const showIds = Object.fromEntries(Object.entries(shows).map(([name, show]) => [name, show.id]));
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: false }),
    [SUMMARY_FILE]: JSON.stringify({ showIds, metrics: data.metrics, state: data.state }, null, 2),
  };
}

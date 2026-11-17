import { screen, within } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { AdminMovie, CinemaView, SystemSnapshot } from '../../api/types';
import { AdminLayout } from '../../components/AdminLayout';
import { movie } from '../../test/fixtures';
import { jsonResponse, mockFetch, renderAt, signInAsAdmin } from '../../test/helpers';
import { SystemPage } from './InsightsPages';
import { AdminMoviesPage } from './MoviesPage';
import { AdminShowsPage } from './ShowsPage';

const base = { ...movie, synopsis: 'A test movie.' };
const movies: AdminMovie[] = [
  { ...base, id: 21, title: 'Showing Now', visibility: 'NOW_SHOWING', upcomingShows: 4, showingIn: ['Mumbai', 'Pune'] },
  { ...base, id: 22, title: 'Opens Friday', visibility: 'COMING_SOON', upcomingShows: 0, showingIn: [] },
  { ...base, id: 23, title: 'Gemini', visibility: 'HIDDEN', upcomingShows: 0, showingIn: [] },
];
const cinemas: CinemaView[] = [{
  id: 3, name: 'Starlight', address: 'KIIT Road', cityId: 8, cityName: 'Bhubaneswar',
  screens: [{ id: 5, name: 'Audi 1', seatCount: 40, categories: ['CLASSIC'] }],
}];

function adminBackend() {
  return mockFetch((url) => {
    if (url.pathname === '/admin/movies') return jsonResponse(movies);
    if (url.pathname === '/admin/cinemas') return jsonResponse(cinemas);
    if (url.pathname === '/admin/cinemas/3/shows') return jsonResponse([]);
    return jsonResponse({}, 404);
  });
}

describe('AdminMoviesPage', () => {
  it('tells the admin where customers can see each movie', async () => {
    signInAsAdmin();
    adminBackend();
    renderAt('/admin/movies', <Route path="/admin/movies" element={<AdminMoviesPage />} />);

    expect(await screen.findByText('Now showing · 2 cities')).toBeInTheDocument();
    expect(screen.getByText('Coming soon')).toBeInTheDocument();
    expect(screen.getByText('Hidden from customers')).toBeInTheDocument();

    // A hidden or coming-soon movie gets a shortcut to schedule it; one already showing doesn't
    const links = screen.getAllByRole('link', { name: 'Schedule a show' });
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/admin/shows?movie=22', '/admin/shows?movie=23']);
  });
});

describe('AdminShowsPage', () => {
  it('opens the schedule form with the movie picked on the Movies page', async () => {
    signInAsAdmin();
    adminBackend();
    renderAt('/admin/shows?movie=23', <Route path="/admin/shows" element={<AdminShowsPage />} />);

    const select = await screen.findByLabelText('Movie');
    expect(select).toHaveValue('23');
  });
});

describe('Under the hood', () => {
  const snapshot: SystemSnapshot = {
    generatedAt: '2030-01-01T10:00:00Z',
    infrastructure: { cache: 'Caffeine (in-process)', rateLimiter: 'In-memory', messaging: 'Outbox', email: 'Logged only',
      tracingExport: false, demoMode: true },
    http: { requests: 120, clientErrors: 2, serverErrors: 0, medianMs: 12.4, p95Ms: 2340, measuredRequests: 110, warmingUp: false },
    caches: [], outbox: { pending: 0, failed: 0, published: 3, relayedPublished: 3, relayedRetries: 0, relayedFailed: 0 },
    rateLimits: { rejectedByRule: {}, limitsPerMinute: {}, backendErrors: 0 }, circuitBreakers: [], operations: [],
    jvm: { uptimeSeconds: 600, heapUsedMb: 120, heapMaxMb: 384, threads: 30 },
  };

  it('sits apart from what a cinema admin uses, and says so', async () => {
    signInAsAdmin();
    mockFetch((url) => (url.pathname === '/admin/system' ? jsonResponse(snapshot) : jsonResponse({}, 404)));
    renderAt('/admin/system', <Route path="/admin" element={<AdminLayout />}><Route path="system" element={<SystemPage />} /></Route>);

    expect(within(screen.getByRole('navigation', { name: 'Admin' })).getByRole('link', { name: /customer emails/i })).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Admin' })).queryByRole('link', { name: /under the hood/i })).not.toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'For developers' })).getByRole('link', { name: /under the hood/i })).toBeInTheDocument();
    expect(await screen.findByText(/a cinema admin wouldn't see this page/i)).toBeInTheDocument();

    // Median and p95, not a mean a single cold start can wreck
    expect(screen.getByText('≈ 12 ms')).toBeInTheDocument();
    expect(screen.getByText('p95 ≈ 2.3 s · after the first minute')).toBeInTheDocument();
  });
});

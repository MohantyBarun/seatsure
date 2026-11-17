package com.seatsure.demo;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** What startup does with the demo: seed, reseed, swap in real movies, or leave it alone. */
class DemoSchedulerTest {

    private final DemoDataService demo = mock(DemoDataService.class);
    private final DemoCatalogSource catalogs = mock(DemoCatalogSource.class);
    private final LockingTaskExecutor locks = mock(LockingTaskExecutor.class);
    private final DemoScheduler scheduler = new DemoScheduler(demo, catalogs, locks, Clock.systemUTC());

    private final DemoCatalog real = new DemoCatalog(List.of(DemoLineupTest.movie("Real", "Hindi")), List.of(), false);
    private final DemoCatalog builtIn = DemoDataService.builtInCatalog(LocalDate.now());

    @BeforeEach
    void lockAlwaysGranted() {
        doAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return null;
        }).when(locks).executeWithLock(any(Runnable.class), any(LockConfiguration.class));
    }

    @Test
    @DisplayName("empty or stale: seeds with whatever movies are available")
    void needsSeed_seeds() {
        when(demo.needsSeed()).thenReturn(true);
        when(catalogs.load()).thenReturn(real);

        scheduler.run(null);

        verify(demo).seedIfEmptyOrStale(real);
        verify(demo, never()).reset(any());
    }

    @Test
    @DisplayName("a TMDB token was just added: the built-in movies are swapped for real ones now")
    void tokenAdded_swapsInRealMovies() {
        when(catalogs.realMoviesConfigured()).thenReturn(true);
        when(demo.showsBuiltInMovies()).thenReturn(true);
        when(catalogs.load()).thenReturn(real);

        scheduler.run(null);

        verify(demo).reset(real);
    }

    @Test
    @DisplayName("token set but TMDB unreachable: the demo is left alone (no reseed on every restart)")
    void tmdbUnreachable_leavesDemoAlone() {
        when(catalogs.realMoviesConfigured()).thenReturn(true);
        when(demo.showsBuiltInMovies()).thenReturn(true);
        when(catalogs.load()).thenReturn(builtIn);

        scheduler.run(null);

        verify(demo, never()).reset(any());
        verify(demo).seedIfEmptyOrStale(isNull());
    }

    @Test
    @DisplayName("current data and no token: TMDB isn't called and nothing is reseeded")
    void current_noToken_nothingHappens() {
        scheduler.run(null);

        verify(catalogs, never()).load();
        verify(demo, never()).reset(any());
        verify(demo).seedIfEmptyOrStale(isNull());
    }

    @Test
    @DisplayName("already showing real movies: left alone until it's stale")
    void alreadyReal_leftAlone() {
        when(catalogs.realMoviesConfigured()).thenReturn(true);
        when(demo.showsBuiltInMovies()).thenReturn(false);

        scheduler.run(null);

        verify(catalogs, never()).load();
        verify(demo, never()).reset(any());
    }
}

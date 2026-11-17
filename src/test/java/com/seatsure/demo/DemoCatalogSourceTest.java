package com.seatsure.demo;

import com.seatsure.entity.Certificate;
import com.seatsure.tmdb.TmdbClient;
import com.seatsure.tmdb.TmdbClient.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Which TMDB movies the demo uses, and that it falls back to the built-in movies whenever it must. */
class DemoCatalogSourceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final DemoCatalogSource source = new DemoCatalogSource(tmdb,
            Clock.fixed(TODAY.atTime(12, 0).atZone(IST).toInstant(), ZoneOffset.UTC), IST, "https://img.test/t/p");

    @BeforeEach
    void configured() {
        when(tmdb.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("no token: the built-in movies, and TMDB is never called")
    void noToken_builtIn() {
        when(tmdb.isConfigured()).thenReturn(false);

        DemoCatalog catalog = source.load();

        assertThat(catalog.builtIn()).isTrue();
        assertThat(catalog.nowShowing()).hasSize(8);
        verify(tmdb, never()).nowPlaying();
    }

    @Test
    @DisplayName("TMDB down: the built-in movies (the demo never depends on it)")
    void tmdbDown_builtIn() {
        when(tmdb.nowPlaying()).thenThrow(new ResourceAccessException("connect timed out"));

        assertThat(source.load().builtIn()).isTrue();
    }

    @Test
    @DisplayName("too few usable movies to fill a cinema's lineup: the built-in movies")
    void tooFew_builtIn() {
        nowPlaying(4);

        assertThat(source.load().builtIn()).isTrue();
    }

    @Test
    @DisplayName("a real movie keeps its poster, artwork, synopsis, rating and Indian certificate")
    void realMovie_isMappedFaithfully() {
        nowPlaying(6);

        DemoCatalog catalog = source.load();

        assertThat(catalog.builtIn()).isFalse();
        DemoCatalog.Movie first = catalog.nowShowing().get(0);
        assertThat(first.title()).isEqualTo("Film 1");
        assertThat(first.synopsis()).isEqualTo("What happens in film 1.");
        assertThat(first.posterUrl()).isEqualTo("https://img.test/t/p/w500/poster1.jpg");
        assertThat(first.backdropUrl()).isEqualTo("https://img.test/t/p/w1280/backdrop1.jpg");
        assertThat(first.rating()).isEqualTo(7.8); // 7.84, rounded to one decimal
        assertThat(first.voteCount()).isEqualTo(1001);
        assertThat(first.tmdbId()).isEqualTo(1);
        assertThat(first.language()).isEqualTo("Hindi");
        assertThat(first.certificate()).isEqualTo(Certificate.UA); // "UA 13+"
        assertThat(first.minutes()).isEqualTo(150);
        assertThat(first.genres()).containsExactly("Action", "Drama");
        assertThat(first.releaseDate()).isEqualTo(LocalDate.of(2026, 11, 7)); // India's theatrical date
    }

    @Test
    @DisplayName("skipped, not patched up: no poster, no runtime, or too long for the show slots")
    void unusableMovies_areSkipped() {
        List<Summary> results = new ArrayList<>();
        results.add(summary(100, "No Poster", null, 7.0, 50));
        results.add(summary(101, "No Runtime", "/p.jpg", 7.0, 50));
        results.add(summary(102, "Epic", "/p.jpg", 7.0, 50));
        for (int i = 1; i <= 5; i++) {
            results.add(summary(i, "Film " + i, "/poster" + i + ".jpg", 7.0, 50));
            details(i, 120, "U", "2026-11-01");
        }
        details(101, null, "U", "2026-11-01");
        details(102, 210, "U", "2026-11-01");
        when(tmdb.nowPlaying()).thenReturn(results);

        DemoCatalog catalog = source.load();

        assertThat(catalog.nowShowing()).extracting(DemoCatalog.Movie::title)
                .containsExactly("Film 1", "Film 2", "Film 3", "Film 4", "Film 5");
        verify(tmdb, never()).details(100);
    }

    @Test
    @DisplayName("coming soon must release after today; an unrated movie has no rating")
    void comingSoon() {
        nowPlaying(5);
        when(tmdb.upcoming()).thenReturn(List.of(
                summary(201, "Already Out", "/p.jpg", 6.0, 10),
                summary(202, "Next Week", "/p.jpg", 0.0, 0)));
        details(201, 120, "A", "2026-11-10");
        details(202, 130, "A", "2026-11-24");

        DemoCatalog catalog = source.load();

        assertThat(catalog.comingSoon()).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("Next Week");
            assertThat(m.releaseDate()).isEqualTo(LocalDate.of(2026, 11, 24));
            assertThat(m.rating()).isNull();
            assertThat(m.voteCount()).isNull();
            assertThat(m.certificate()).isEqualTo(Certificate.A);
        });
    }

    @Test
    @DisplayName("in cinemas now but TMDB's Indian date is later: treated as released today")
    void inCinemasWithAFutureDate_isReleasedToday() {
        nowPlaying(5);
        details(1, 150, "U", "2026-11-20");

        assertThat(source.load().nowShowing().get(0).releaseDate()).isEqualTo(TODAY);
    }

    @Test
    void languageNames() {
        assertThat(DemoCatalogSource.languageName("hi")).isEqualTo("Hindi");
        assertThat(DemoCatalogSource.languageName("ta")).isEqualTo("Tamil");
        assertThat(DemoCatalogSource.languageName("ml")).isEqualTo("Malayalam");
        assertThat(DemoCatalogSource.languageName("or")).isEqualTo("Odia");
        assertThat(DemoCatalogSource.languageName("en")).isEqualTo("English");
        assertThat(DemoCatalogSource.languageName(null)).isEqualTo("English");
    }

    // ---------------------------------------------------------------- helpers

    private void nowPlaying(int count) {
        List<Summary> results = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            results.add(summary(i, "Film " + i, "/poster" + i + ".jpg", 7.84, 1000 + i));
            details(i, 150, "UA 13+", "2026-11-07");
        }
        when(tmdb.nowPlaying()).thenReturn(results);
    }

    private static Summary summary(int id, String title, String poster, double rating, int votes) {
        return new Summary(id, title, "What happens in " + title.toLowerCase() + ".", poster, "/backdrop" + id + ".jpg",
                rating, votes, "2026-11-01", "hi");
    }

    private void details(int id, Integer runtime, String certificate, String indiaRelease) {
        when(tmdb.details(id)).thenReturn(new Details(id, runtime, List.of(new Genre("Action"), new Genre("Drama")),
                new ReleaseDates(List.of(
                        new CountryReleases("US", List.of(new Release("PG-13", "2026-10-01T00:00:00.000Z", 3))),
                        new CountryReleases("IN", List.of(new Release(certificate, indiaRelease + "T00:00:00.000Z", 3)))))));
    }
}

package com.seatsure.demo;

import com.seatsure.entity.Certificate;
import com.seatsure.tmdb.TmdbClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Where the demo's movies come from: what's really in Indian cinemas (TMDB) when a token is set and
 * TMDB answers, otherwise the built-in fictional movies. The demo never depends on TMDB being up.
 *
 * <p>Movies are skipped rather than patched up when something the app needs is missing: no poster or
 * synopsis, no runtime, or a runtime too long for the demo's show slots. Ratings are TMDB users'
 * average and are labelled as such in the UI.
 */
@Slf4j
@Component
public class DemoCatalogSource {

    /** The demo's show slots are 3.5 hours apart, less 15 minutes to clean between shows. */
    static final int MAX_RUNTIME_MINUTES = 195;
    static final int MIN_RUNTIME_MINUTES = 30;
    static final int NOW_SHOWING = 8;
    static final int MIN_NOW_SHOWING = DemoDataService.LINEUP_SIZE;
    static final int COMING_SOON = 2;
    static final String POSTER_SIZE = "/w500";
    static final String BACKDROP_SIZE = "/w1280";

    private final TmdbClient tmdb;
    private final Clock clock;
    private final ZoneId zone;
    private final String imageBaseUrl;

    public DemoCatalogSource(TmdbClient tmdb, Clock clock, ZoneId zone,
                             @Value("${app.tmdb.image-base-url:https://image.tmdb.org/t/p}") String imageBaseUrl) {
        this.tmdb = tmdb;
        this.clock = clock;
        this.zone = zone;
        this.imageBaseUrl = imageBaseUrl;
    }

    /** True when a TMDB token is set, so {@link #load} will try for real movies. */
    public boolean realMoviesConfigured() {
        return tmdb.isConfigured();
    }

    public DemoCatalog load() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (!tmdb.isConfigured()) {
            log.info("Demo movies: TMDB_API_TOKEN not set; using the built-in movies");
            return DemoDataService.builtInCatalog(today);
        }
        try {
            List<DemoCatalog.Movie> nowShowing = pick(tmdb.nowPlaying(), today, true, NOW_SHOWING);
            if (nowShowing.size() < MIN_NOW_SHOWING) {
                log.warn("Demo movies: TMDB returned only {} usable movies in cinemas; using the built-in movies",
                        nowShowing.size());
                return DemoDataService.builtInCatalog(today);
            }
            List<DemoCatalog.Movie> comingSoon = pick(tmdb.upcoming(), today, false, COMING_SOON);
            log.info("Demo movies: {} now showing and {} coming soon from TMDB", nowShowing.size(), comingSoon.size());
            return new DemoCatalog(nowShowing, comingSoon, false);
        } catch (RuntimeException e) {
            log.warn("Demo movies: TMDB unavailable ({}); using the built-in movies", e.toString());
            return DemoDataService.builtInCatalog(today);
        }
    }

    private List<DemoCatalog.Movie> pick(List<TmdbClient.Summary> candidates, LocalDate today, boolean inCinemas,
                                         int wanted) {
        List<DemoCatalog.Movie> picked = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (TmdbClient.Summary s : candidates) {
            if (picked.size() == wanted) {
                break;
            }
            if (isBlank(s.title()) || isBlank(s.overview()) || isBlank(s.posterPath()) || !titles.add(s.title().trim())) {
                continue;
            }
            TmdbClient.Details details = tmdb.details(s.id());
            Integer runtime = details == null ? null : details.runtime();
            if (runtime == null || runtime < MIN_RUNTIME_MINUTES || runtime > MAX_RUNTIME_MINUTES) {
                continue;
            }
            LocalDate released = releaseDate(details, s);
            if (inCinemas) {
                // Regional release dates can trail TMDB's; it's in cinemas now, so it has been released
                released = released == null || released.isAfter(today) ? today : released;
            } else if (released == null || !released.isAfter(today)) {
                continue; // "coming soon" must be in the future
            }
            picked.add(toMovie(s, details, runtime, released));
        }
        return List.copyOf(picked);
    }

    private DemoCatalog.Movie toMovie(TmdbClient.Summary s, TmdbClient.Details details, int runtime, LocalDate released) {
        List<String> genres = details.genres() == null ? List.of()
                : details.genres().stream().map(TmdbClient.Genre::name).filter(g -> !isBlank(g)).toList();
        boolean rated = s.voteCount() != null && s.voteCount() > 0 && s.voteAverage() != null;
        return new DemoCatalog.Movie(
                truncate(s.title().trim(), 150),
                truncate(s.overview().trim(), 2000),
                languageName(s.originalLanguage()),
                certificate(details),
                runtime,
                genres,
                released,
                imageBaseUrl + POSTER_SIZE + s.posterPath(),
                isBlank(s.backdropPath()) ? null : imageBaseUrl + BACKDROP_SIZE + s.backdropPath(),
                rated ? Math.round(s.voteAverage() * 10) / 10.0 : null,
                rated ? s.voteCount() : null,
                s.id());
    }

    /** The release in the region: the earliest theatrical one TMDB lists, else its general release date. */
    private LocalDate releaseDate(TmdbClient.Details details, TmdbClient.Summary s) {
        Optional<LocalDate> regional = regionReleases(details).stream()
                .filter(r -> r.type() != null && (r.type() == 2 || r.type() == 3))
                .map(r -> parseDate(r.releaseDate()))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder());
        return regional.orElseGet(() -> parseDate(s.releaseDate()));
    }

    /**
     * The Indian certificate (CBFC) from TMDB: "U", "UA" (also "U/A", "UA 13+", "UA 16+"...) or "A".
     * TMDB doesn't always have one; those are shown as UA, the most common rating.
     */
    static Certificate certificate(TmdbClient.Details details) {
        for (TmdbClient.Release r : regionReleases(details)) {
            String c = r.certification() == null ? "" : r.certification().trim().toUpperCase(Locale.ROOT);
            if (c.startsWith("UA") || c.startsWith("U/A")) {
                return Certificate.UA;
            }
            if (c.equals("A")) {
                return Certificate.A;
            }
            if (c.equals("U")) {
                return Certificate.U;
            }
        }
        return Certificate.UA;
    }

    private static List<TmdbClient.Release> regionReleases(TmdbClient.Details details) {
        if (details == null || details.releaseDates() == null || details.releaseDates().results() == null) {
            return List.of();
        }
        return details.releaseDates().results().stream()
                .filter(c -> "IN".equals(c.country()) && c.releaseDates() != null)
                .flatMap(c -> c.releaseDates().stream())
                .toList();
    }

    /** "hi" -> "Hindi", "ta" -> "Tamil"; unknown codes stay as they are. */
    static String languageName(String code) {
        if (isBlank(code)) {
            return "English";
        }
        String name = Locale.forLanguageTag(code.trim()).getDisplayLanguage(Locale.ENGLISH);
        if (isBlank(name) || name.equalsIgnoreCase(code.trim())) {
            name = code.trim().toUpperCase(Locale.ROOT);
        }
        return truncate(name.equals("Oriya") ? "Odia" : name, 30);
    }

    private static LocalDate parseDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return value.length() > 10 ? OffsetDateTime.parse(value).toLocalDate() : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }
}

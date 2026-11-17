package com.seatsure.demo;

import com.seatsure.entity.Certificate;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * The movies a demo seed uses: real ones from TMDB when it's configured and reachable, otherwise the
 * built-in fictional ones ({@code builtIn}, whose cinemas have hand-picked lineups).
 */
public record DemoCatalog(List<DemoCatalog.Movie> nowShowing, List<DemoCatalog.Movie> comingSoon, boolean builtIn) {

    /** One movie. Rating, vote count, artwork and TMDB id are null for the built-in movies. */
    public record Movie(String title, String synopsis, String language, Certificate certificate, int minutes,
                        List<String> genres, LocalDate releaseDate, String posterUrl, String backdropUrl,
                        Double rating, Integer voteCount, Integer tmdbId) {
    }

    public List<Movie> all() {
        return Stream.concat(nowShowing.stream(), comingSoon.stream()).toList();
    }
}

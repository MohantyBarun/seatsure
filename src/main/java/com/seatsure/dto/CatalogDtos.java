package com.seatsure.dto;

import com.seatsure.entity.Certificate;
import com.seatsure.entity.City;
import com.seatsure.entity.Movie;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.ShowFormat;
import com.seatsure.entity.ShowSeatStatus;
import com.seatsure.entity.ShowStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What customers browse: cities, movies, showtimes and seat maps. Prices are in paise. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CityView(Long id, String name, String state) {
        public static CityView of(City c) {
            return new CityView(c.getId(), c.getName(), c.getState());
        }
    }

    /** rating is out of 10 (TMDB users' average), with voteCount votes; both null when unrated. */
    public record MovieCard(Long id, String title, String language, Certificate certificate, List<String> genres,
                            int durationMinutes, LocalDate releaseDate, String posterUrl, String backdropUrl,
                            Double rating, Integer voteCount) {
        public static MovieCard of(Movie m) {
            return new MovieCard(m.getId(), m.getTitle(), m.getLanguage(), m.getCertificate(), m.genreList(),
                    m.getDurationMinutes(), m.getReleaseDate(), m.getPosterUrl(), m.getBackdropUrl(), m.getRating(),
                    m.getVoteCount());
        }
    }

    public record MovieDetail(Long id, String title, String synopsis, String language, Certificate certificate,
                              List<String> genres, int durationMinutes, LocalDate releaseDate, String posterUrl,
                              String backdropUrl, Double rating, Integer voteCount) {
        public static MovieDetail of(Movie m) {
            return new MovieDetail(m.getId(), m.getTitle(), m.getSynopsis(), m.getLanguage(), m.getCertificate(),
                    m.genreList(), m.getDurationMinutes(), m.getReleaseDate(), m.getPosterUrl(), m.getBackdropUrl(),
                    m.getRating(), m.getVoteCount());
        }
    }

    /** Home page for a city: what's showing now and what's coming. */
    public record Home(CityView city, List<MovieCard> nowShowing, List<MovieCard> comingSoon) {
    }

    public enum Availability { AVAILABLE, FILLING_FAST, SOLD_OUT }

    public record ShowtimeView(Long showId, Instant startsAt, ShowFormat format, String screenName,
                               Availability availability, long seatsLeft, int minPricePaise, int maxPricePaise) {
    }

    public record CinemaShowtimes(Long cinemaId, String cinemaName, String address, List<ShowtimeView> shows) {
    }

    /** Showtimes of one movie in one city on one day, grouped by cinema; availableDates drives the date strip. */
    public record Showtimes(MovieCard movie, Long cityId, LocalDate date, List<LocalDate> availableDates,
                            List<CinemaShowtimes> cinemas) {
    }

    public record PriceView(SeatCategory category, int pricePaise) {
    }

    public record CinemaRef(Long id, String name, String address, String city) {
    }

    public record ShowDetail(Long showId, MovieCard movie, CinemaRef cinema, String screenName, Instant startsAt,
                             Instant endsAt, ShowFormat format, ShowStatus status, boolean bookable,
                             List<PriceView> prices) {
    }

    public record SeatView(Long id, int number, int col, ShowSeatStatus status) {
    }

    public record SeatRow(String label, SeatCategory category, List<SeatView> seats) {
    }

    /** Rows in label order (A is nearest the screen); maxCol lets the client size the grid. */
    public record SeatMap(Long showId, boolean bookable, int maxCol, long available, long total,
                          List<PriceView> prices, List<SeatRow> rows) {
    }

    /** Free seats side by side in one row; score is out of 100 and reasons say what earned it. */
    public record SeatSuggestion(String rowLabel, SeatCategory category, List<Long> seatIds, List<String> labels,
                                 int score, List<String> reasons, int ticketsPaise) {
    }

    /** The best places to sit right now, best first: at most one per row. Empty if no row fits the group. */
    public record BestSeats(Long showId, int count, SeatCategory category, List<SeatSuggestion> suggestions) {
    }
}

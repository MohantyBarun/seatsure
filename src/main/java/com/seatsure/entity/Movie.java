package com.seatsure.entity;

import lombok.AccessLevel;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.annotation.CreatedDate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "movies")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "synopsis", nullable = false, length = 2000)
    private String synopsis;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "language", nullable = false, length = 30)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "certificate", nullable = false, length = 5)
    private Certificate certificate;

    /** Comma-separated, e.g. "Action,Thriller". */
    @Column(name = "genres", nullable = false, length = 200)
    private String genres;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    /** Wide artwork for the movie page; null for movies without one. */
    @Column(name = "backdrop_url", length = 500)
    private String backdropUrl;

    /** Average rating out of 10 and how many votes it's based on, with its source (TMDB); null if unrated. */
    @Column(name = "rating")
    private Double rating;

    @Column(name = "vote_count")
    private Integer voteCount;

    /** The TMDB movie the data came from; null for movies added by hand. */
    @Column(name = "tmdb_id")
    private Integer tmdbId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public List<String> genreList() {
        return Arrays.stream(genres.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Movie(String title, String synopsis, int durationMinutes, String language, Certificate certificate,
                 List<String> genres, LocalDate releaseDate, String posterUrl) {
        update(title, synopsis, durationMinutes, language, certificate, genres, releaseDate, posterUrl);
    }

    /** Everything an admin can edit, normalised (trimmed; a blank poster URL means a generated poster). */
    public void update(String title, String synopsis, int durationMinutes, String language, Certificate certificate,
                       List<String> genres, LocalDate releaseDate, String posterUrl) {
        this.title = title.trim();
        this.synopsis = synopsis.trim();
        this.durationMinutes = durationMinutes;
        this.language = language.trim();
        this.certificate = certificate;
        this.genres = String.join(",", genres.stream().map(String::trim).filter(g -> !g.isEmpty()).distinct().toList());
        this.releaseDate = releaseDate;
        this.posterUrl = posterUrl == null || posterUrl.isBlank() ? null : posterUrl.trim();
    }

    /** Ratings and artwork from an external catalogue (TMDB). Admin edits leave them alone. */
    public void describeFrom(Integer tmdbId, Double rating, Integer voteCount, String backdropUrl) {
        this.tmdbId = tmdbId;
        this.rating = rating;
        this.voteCount = voteCount;
        this.backdropUrl = backdropUrl == null || backdropUrl.isBlank() ? null : backdropUrl.trim();
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}

package com.seatsure.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * The few TMDB (The Movie Database) API calls the demo needs: what's in Indian cinemas now, what's
 * coming, and a movie's runtime, genres and Indian certificate. Free for non-commercial use with
 * attribution (the footer credits TMDB).
 *
 * <p>Only called when the demo is seeded (at most once a day), never while serving customers, so a
 * slow or failing TMDB can't slow the site down; the caller falls back to built-in movies.
 */
@Component
public class TmdbClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient http;
    private final boolean configured;
    private final String region;

    @Autowired
    public TmdbClient(RestClient.Builder builder,
                      @Value("${app.tmdb.api-token:}") String token,
                      @Value("${app.tmdb.base-url:https://api.themoviedb.org/3}") String baseUrl,
                      @Value("${app.tmdb.region:IN}") String region) {
        this(token, baseUrl, region, builder.clone().requestFactory(timeouts()));
    }

    /** For tests: a builder already bound to a mock server. */
    TmdbClient(String token, String baseUrl, String region, RestClient.Builder builder) {
        this.configured = token != null && !token.isBlank();
        this.region = region;
        this.http = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (configured ? token.trim() : ""))
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** True when an API read access token is set (TMDB_API_TOKEN). */
    public boolean isConfigured() {
        return configured;
    }

    /** Movies in cinemas in the region now, most popular first. */
    public List<Summary> nowPlaying() {
        return page("/movie/now_playing");
    }

    /** Movies releasing in the region soon. */
    public List<Summary> upcoming() {
        return page("/movie/upcoming");
    }

    /** Runtime, genres and every country's release dates and certificates. */
    public Details details(int movieId) {
        return http.get()
                .uri(uri -> uri.path("/movie/{id}").queryParam("append_to_response", "release_dates")
                        .queryParam("language", "en-US").build(movieId))
                .retrieve()
                .body(Details.class);
    }

    private List<Summary> page(String path) {
        Page page = http.get()
                .uri(uri -> uri.path(path).queryParam("region", region).queryParam("language", "en-US")
                        .queryParam("page", 1).build())
                .retrieve()
                .body(Page.class);
        return page == null || page.results() == null ? List.of() : page.results();
    }

    // ---------------------------------------------------------------- TMDB's JSON

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(List<Summary> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(int id, String title, String overview,
                          @JsonProperty("poster_path") String posterPath,
                          @JsonProperty("backdrop_path") String backdropPath,
                          @JsonProperty("vote_average") Double voteAverage,
                          @JsonProperty("vote_count") Integer voteCount,
                          @JsonProperty("release_date") String releaseDate,
                          @JsonProperty("original_language") String originalLanguage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(int id, Integer runtime, List<Genre> genres,
                          @JsonProperty("release_dates") ReleaseDates releaseDates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Genre(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseDates(List<CountryReleases> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CountryReleases(@JsonProperty("iso_3166_1") String country,
                                  @JsonProperty("release_dates") List<Release> releaseDates) {
    }

    /** type: 1 premiere, 2 limited theatrical, 3 theatrical, 4 digital, 5 physical, 6 TV. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(String certification, @JsonProperty("release_date") String releaseDate, Integer type) {
    }
}

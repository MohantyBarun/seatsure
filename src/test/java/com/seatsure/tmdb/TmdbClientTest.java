package com.seatsure.tmdb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The TMDB calls against a mock server: the token, the query and TMDB's JSON. */
class TmdbClientTest {

    private static final String BASE = "https://tmdb.test/3";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TmdbClient client = new TmdbClient("secret-token", BASE, "IN", builder);

    @Test
    @DisplayName("now playing: asks for the region with the token, and reads the fields the demo uses")
    void nowPlaying() {
        server.expect(requestTo(BASE + "/movie/now_playing?region=IN&language=en-US&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess("""
                        {"page":1,"results":[{"id":42,"title":"A Real Film","overview":"It happens.",
                          "poster_path":"/p.jpg","backdrop_path":"/b.jpg","vote_average":7.84,"vote_count":1234,
                          "release_date":"2026-11-07","original_language":"hi","popularity":99.1,"adult":false}],
                         "total_pages":1}
                        """, MediaType.APPLICATION_JSON));

        List<TmdbClient.Summary> movies = client.nowPlaying();

        assertThat(movies).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(42);
            assertThat(m.posterPath()).isEqualTo("/p.jpg");
            assertThat(m.voteAverage()).isEqualTo(7.84);
            assertThat(m.originalLanguage()).isEqualTo("hi");
        });
        server.verify();
    }

    @Test
    @DisplayName("details: runtime, genres and each country's release dates and certificates")
    void details() {
        server.expect(requestTo(BASE + "/movie/42?append_to_response=release_dates&language=en-US"))
                .andRespond(withSuccess("""
                        {"id":42,"runtime":151,"genres":[{"id":28,"name":"Action"},{"id":53,"name":"Thriller"}],
                         "release_dates":{"results":[{"iso_3166_1":"IN","release_dates":[
                           {"certification":"UA 13+","release_date":"2026-11-07T00:00:00.000Z","type":3}]}]}}
                        """, MediaType.APPLICATION_JSON));

        TmdbClient.Details details = client.details(42);

        assertThat(details.runtime()).isEqualTo(151);
        assertThat(details.genres()).extracting(TmdbClient.Genre::name).containsExactly("Action", "Thriller");
        assertThat(details.releaseDates().results().get(0).releaseDates().get(0).certification()).isEqualTo("UA 13+");
    }

    @Test
    void anErrorFromTmdb_isThrown_soTheCallerCanFallBack() {
        server.expect(requestTo(BASE + "/movie/upcoming?region=IN&language=en-US&page=1"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(client::upcoming).isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void configuredOnlyWithAToken() {
        assertThat(client.isConfigured()).isTrue();
        assertThat(new TmdbClient(" ", BASE, "IN", RestClient.builder()).isConfigured()).isFalse();
        assertThat(new TmdbClient(null, BASE, "IN", RestClient.builder()).isConfigured()).isFalse();
    }
}

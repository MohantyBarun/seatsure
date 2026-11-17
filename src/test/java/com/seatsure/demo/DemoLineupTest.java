package com.seatsure.demo;

import com.seatsure.entity.Certificate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which five real movies each cinema shows. */
class DemoLineupTest {

    private static final List<DemoCatalog.Movie> NOW_SHOWING = List.of(
            movie("English 1", "English"), movie("Hindi 1", "Hindi"), movie("Tamil 1", "Tamil"),
            movie("Hindi 2", "Hindi"), movie("Kannada 1", "Kannada"), movie("Malayalam 1", "Malayalam"));

    @Test
    @DisplayName("the city's languages first, then the rest, each in popularity order")
    void cityLanguagesFirst() {
        assertThat(DemoDataService.lineup(NOW_SHOWING, List.of("Hindi", "Marathi", "English"), 0))
                .containsExactly("English 1", "Hindi 1", "Hindi 2", "Tamil 1", "Kannada 1");
        assertThat(DemoDataService.lineup(NOW_SHOWING, List.of("Kannada", "English", "Tamil"), 0))
                .containsExactly("English 1", "Tamil 1", "Kannada 1", "Hindi 1", "Hindi 2");
    }

    @Test
    @DisplayName("the second cinema in a city starts one further along")
    void secondCinemaDiffers() {
        assertThat(DemoDataService.lineup(NOW_SHOWING, List.of("Hindi", "Marathi", "English"), 1))
                .containsExactly("Hindi 1", "Hindi 2", "Tamil 1", "Kannada 1", "Malayalam 1");
    }

    @Test
    @DisplayName("with exactly five movies, every cinema shows all five")
    void exactlyFive() {
        List<DemoCatalog.Movie> five = NOW_SHOWING.subList(0, 5);

        assertThat(DemoDataService.lineup(five, List.of("Odia"), 1)).hasSize(5).containsExactlyInAnyOrderElementsOf(
                five.stream().map(DemoCatalog.Movie::title).toList());
    }

    static DemoCatalog.Movie movie(String title, String language) {
        return new DemoCatalog.Movie(title, "What happens in " + title + ".", language, Certificate.UA, 140,
                List.of("Drama"), LocalDate.now().minusDays(3), "https://img.test/p.jpg", "https://img.test/b.jpg",
                7.5, 900, Math.abs(title.hashCode()));
    }
}

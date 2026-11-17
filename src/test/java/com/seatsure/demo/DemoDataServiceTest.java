package com.seatsure.demo;

import com.seatsure.config.JpaAuditingConfig;
import com.seatsure.config.TimeConfig;
import com.seatsure.entity.LoginSecurity;
import com.seatsure.entity.User;
import com.seatsure.repository.LoginSecurityRepository;
import com.seatsure.repository.UserRepository;
import com.seatsure.service.Roles;
import com.seatsure.support.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real demo seeding and reset against PostgreSQL (Testcontainers; skipped without Docker). */
@DataJpaTest(properties = "app.demo.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({DemoDataService.class, JpaAuditingConfig.class, TimeConfig.class, DemoDataServiceTest.Config.class,
        PostgresTestcontainer.class})
class DemoDataServiceTest {

    @TestConfiguration
    static class Config {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired private DemoDataService demo;
    @Autowired private UserRepository users;
    @Autowired private LoginSecurityRepository loginSecurity;
    @Autowired private JdbcTemplate jdbc;

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void seed_createsAConsistentDemoDataset() {
        demo.seedIfEmptyOrStale();

        assertThat(count("SELECT COUNT(*) FROM cinemas")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM screens")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM movies")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM shows")).isEqualTo(16 * 4 * 4); // 16 screens, 4 slots, 4 days
        assertThat(count("SELECT COUNT(*) FROM show_seats"))
                .as("every show has a full seat inventory")
                .isEqualTo(count("SELECT SUM(n) FROM (SELECT COUNT(*) n FROM shows s JOIN seats st ON st.screen_id = s.screen_id GROUP BY s.id) x"));
        assertThat(count("SELECT COUNT(*) FROM bookings")).isGreaterThan(1000);

        // Inventory and bookings agree, seat for seat
        assertThat(count("SELECT COUNT(*) FROM show_seats WHERE status = 'BOOKED'"))
                .isEqualTo(count("SELECT COUNT(*) FROM booking_seats"))
                .isEqualTo(count("SELECT SUM(seat_count) FROM bookings"));
        assertThat(count("""
                SELECT COUNT(*) FROM show_seats ss JOIN booking_seats bs
                  ON bs.booking_id = ss.booking_id AND bs.seat_id = ss.seat_id
                """)).isEqualTo(count("SELECT COUNT(*) FROM booking_seats"));
        assertThat(count("SELECT COUNT(*) FROM bookings b WHERE tickets_paise <> "
                + "(SELECT SUM(price_paise) FROM booking_seats bs WHERE bs.booking_id = b.id)")).isZero();

        // Both demo accounts exist; the demo customer has bookings to show
        assertThat(users.findByEmail(demo.demoAdminEmail())).get()
                .satisfies(u -> assertThat(u.getRole()).isEqualTo(Roles.ROLE_ADMIN));
        User customer = users.findByEmail(demo.demoCustomerEmail()).orElseThrow();
        assertThat(customer.getRole()).isEqualTo(Roles.ROLE_CUSTOMER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE user_id = ?", Long.class, customer.getId()))
                .isEqualTo(2);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("seeded from real movies: ratings and artwork are kept, and lineups follow each city's languages")
    void seed_fromRealMovies() {
        List<DemoCatalog.Movie> nowShowing = List.of(
                DemoLineupTest.movie("Hindi Hit", "Hindi"), DemoLineupTest.movie("English Blockbuster", "English"),
                DemoLineupTest.movie("Tamil Drama", "Tamil"), DemoLineupTest.movie("Telugu Epic", "Telugu"),
                DemoLineupTest.movie("Kannada Thriller", "Kannada"), DemoLineupTest.movie("Odia Story", "Odia"));

        demo.seedIfEmptyOrStale(new DemoCatalog(nowShowing, List.of(), false));

        assertThat(count("SELECT COUNT(*) FROM movies")).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM shows")).isEqualTo(16 * 4 * 4);
        assertThat(jdbc.queryForMap("SELECT rating, vote_count, backdrop_url, poster_url FROM movies WHERE title = 'Hindi Hit'"))
                .containsEntry("rating", 7.5).containsEntry("vote_count", 900)
                .containsEntry("backdrop_url", "https://img.test/b.jpg").containsEntry("poster_url", "https://img.test/p.jpg");
        // Bhubaneswar favours Odia: the Odia film is on, though it's last in popularity
        assertThat(count("""
                SELECT COUNT(*) FROM shows s JOIN movies m ON m.id = s.movie_id JOIN screens sc ON sc.id = s.screen_id
                JOIN cinemas c ON c.id = sc.cinema_id JOIN cities ci ON ci.id = c.city_id
                WHERE ci.name = 'Bhubaneswar' AND m.title = 'Odia Story'
                """)).isPositive();
    }

    @Test
    void seed_isIdempotent() {
        demo.seedIfEmptyOrStale();
        demo.seedIfEmptyOrStale();

        assertThat(count("SELECT COUNT(*) FROM cinemas")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM movies")).isEqualTo(10);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("demo data whose shows have run out is reseeded at startup")
    void staleData_isReseeded() {
        demo.seedIfEmptyOrStale();
        // What two weeks asleep does to the demo: every show is in the past
        jdbc.update("UPDATE shows SET starts_at = starts_at - interval '14 days', ends_at = ends_at - interval '14 days'");
        assertThat(count("SELECT COUNT(*) FROM shows WHERE starts_at > now() + interval '1 day'")).isZero();

        demo.seedIfEmptyOrStale();

        assertThat(count("SELECT COUNT(*) FROM shows WHERE starts_at > now() + interval '1 day'")).isPositive();
        assertThat(count("SELECT COUNT(*) FROM cinemas")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM movies")).isEqualTo(10);
    }

    @Test
    void reset_reseedsAndKeepsAdmins() {
        User realAdmin = users.save(new User("Real Admin", "real.admin@seatsure.test", "x", Roles.ROLE_ADMIN));
        loginSecurity.save(new LoginSecurity(realAdmin));
        demo.seedIfEmptyOrStale();
        long showsBefore = count("SELECT COUNT(*) FROM shows");

        demo.reset();

        assertThat(count("SELECT COUNT(*) FROM shows")).isEqualTo(showsBefore);
        assertThat(count("SELECT COUNT(*) FROM cinemas")).isEqualTo(8);
        assertThat(users.findByEmail("real.admin@seatsure.test")).isPresent();
        assertThat(users.findByEmail(demo.demoAdminEmail())).isPresent();
        assertThat(users.findByEmail(demo.demoCustomerEmail())).isPresent();
    }
}

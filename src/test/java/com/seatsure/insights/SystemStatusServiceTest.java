package com.seatsure.insights;

import com.seatsure.ratelimit.RateLimitProperties;
import java.time.Clock;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatsure.entity.OutboxEvent;
import com.seatsure.notification.EmailSender;
import com.seatsure.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemStatusServiceTest {

    /** The registry production uses: unlike SimpleMeterRegistry, its snapshots include histogram buckets. */
    private final PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private SimpleCacheManager caches;
    private SystemStatusService service;

    @BeforeEach
    void setUp() {
        caches = new SimpleCacheManager();
        caches.setCaches(List.of(new CaffeineCache("nowShowing", Caffeine.newBuilder().recordStats().build())));
        caches.initializeCaches();
        when(emailSender.mode()).thenReturn("Logged only (demo mode)");
        service = new SystemStatusService(meters, new TransactionAwareCacheManagerProxy(caches), outbox, breakers,
                emailSender, false, false, true, "", new RateLimitProperties(true, 10, 5, 20, 30, 300), Clock.systemUTC());
    }

    /** Like production: application.properties turns on the percentile histogram for these timers. */
    private void request(String uri, String outcome, long millis) {
        Timer.builder("http.server.requests").tag("uri", uri).tag("outcome", outcome).publishPercentileHistogram()
                .register(meters).record(Duration.ofMillis(millis));
    }

    @Test
    void http_countsAppTrafficButNotThePollingOfAdminAndActuator() {
        request("/cities", "SUCCESS", 20);
        request("/cities", "SUCCESS", 40);
        request("/movies/{movieId}", "CLIENT_ERROR", 10);
        request("/auth/login", "SERVER_ERROR", 90);
        request("/admin/system", "SUCCESS", 500);
        request("/actuator/health/liveness", "SUCCESS", 500);

        SystemStatusService.Http http = service.http();

        assertThat(http.requests()).isEqualTo(4);
        assertThat(http.clientErrors()).isEqualTo(1);
        assertThat(http.serverErrors()).isEqualTo(1);
        assertThat(http.measuredRequests()).as("admin and actuator polling left out").isEqualTo(4);
        assertThat(http.warmingUp()).isTrue();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("latency is the median and p95, so one very slow request doesn't dominate it")
    void latency_isMedianAndP95_notMean() {
        for (int i = 0; i < 95; i++) {
            request("/cities", "SUCCESS", 20);
        }
        for (int i = 0; i < 4; i++) {
            request("/shows/{showId}/seats", "SUCCESS", 80);
        }
        request("/cities", "SUCCESS", 28_000); // a cold start

        SystemStatusService.Http http = service.http();

        // Histogram buckets: the answer is approximate, but in the right bucket and nowhere near 28 s
        assertThat(http.medianMs()).isBetween(15.0, 25.0);
        assertThat(http.p95Ms()).isBetween(15.0, 25.0);
        assertThat(http.measuredRequests()).isEqualTo(100);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("after the warm-up, the cold start no longer counts")
    void afterWarmup_coldStartIsLeftOut() {
        request("/cities", "SUCCESS", 28_000); // the request that woke the server
        service.endWarmup();
        for (int i = 0; i < 10; i++) {
            request("/cities", "SUCCESS", 50);
        }

        SystemStatusService.Http http = service.http();

        assertThat(http.warmingUp()).isFalse();
        assertThat(http.requests()).as("totals still count every request").isEqualTo(11);
        assertThat(http.measuredRequests()).isEqualTo(10);
        assertThat(http.p95Ms()).isBetween(40.0, 60.0);
    }

    @Test
    void latency_withNoTraffic_isUnknown() {
        assertThat(service.http().medianMs()).isNull();
        assertThat(service.http().p95Ms()).isNull();
    }

    @Test
    void caches_reportHitRateThroughTheTransactionAwareWrapper() {
        Cache cache = caches.getCache("nowShowing");
        cache.put("cs", List.of("x"));
        cache.get("cs");
        cache.get("cs");
        cache.get("cs");
        cache.get("missing");

        SystemStatusService.CacheStat stat = service.caches().get(0);

        assertThat(stat.name()).isEqualTo("nowShowing");
        assertThat(stat.hits()).isEqualTo(3);
        assertThat(stat.misses()).isEqualTo(1);
        assertThat(stat.hitRate()).isEqualTo(75.0);
        assertThat(stat.size()).isEqualTo(1);
    }

    @Test
    void outboxAndRateLimits_comeFromTheRepositoryAndCounters() {
        when(outbox.countByStatus(OutboxEvent.Status.PENDING)).thenReturn(2L);
        when(outbox.countByStatus(OutboxEvent.Status.FAILED)).thenReturn(1L);
        meters.counter("outbox.events.relayed", "outcome", "published").increment(5);
        meters.counter("outbox.events.relayed", "outcome", "retry").increment(2);
        meters.counter("ratelimit.rejected", "rule", "login").increment(3);

        assertThat(service.outbox()).isEqualTo(new SystemStatusService.Outbox(2, 1, 0, 5, 2, 0));
        SystemStatusService.RateLimits limits = service.rateLimits();
        assertThat(limits.rejectedByRule()).containsEntry("login", 3L).containsEntry("api", 0L);
        assertThat(limits.limitsPerMinute()).containsEntry("login", 10).containsEntry("api", 300);
    }

    @Test
    void operations_splitSuccessesFromFailuresByTheObservationErrorTag() {
        Timer.builder("auth.login").tag("error", "none").register(meters).record(Duration.ofMillis(100));
        Timer.builder("auth.login").tag("error", "none").register(meters).record(Duration.ofMillis(200));
        Timer.builder("auth.login").tag("error", "AuthenticationFailedException").register(meters)
                .record(Duration.ofMillis(60));

        SystemStatusService.Operation logins = service.operation("auth.login", "Logins");

        assertThat(logins.calls()).isEqualTo(3);
        assertThat(logins.failures()).isEqualTo(1);
        assertThat(logins.meanMs()).isEqualTo(120.0);
    }

    @Test
    void snapshot_describesTheInfrastructureAndBreakers() {
        breakers.circuitBreaker("smtp");

        SystemStatusService.Snapshot snapshot = service.snapshot();

        assertThat(snapshot.infrastructure().cache()).isEqualTo("Caffeine (in-process)");
        assertThat(snapshot.infrastructure().messaging()).contains("in-process");
        assertThat(snapshot.infrastructure().email()).isEqualTo("Logged only (demo mode)");
        assertThat(snapshot.infrastructure().demoMode()).isTrue();
        assertThat(snapshot.circuitBreakers()).extracting(SystemStatusService.Breaker::name, SystemStatusService.Breaker::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("smtp", "CLOSED"));
        assertThat(snapshot.jvm().heapMaxMb()).isPositive();
    }
}

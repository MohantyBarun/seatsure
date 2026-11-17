package com.seatsure.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

/**
 * LISTENs for seat changes on a dedicated database connection (not one borrowed from the pool,
 * which is small on the free tier) and hands them to {@link SeatStreams}.
 *
 * <p><b>Only while someone is watching.</b> The connection opens when the first seat map opens on
 * this instance and closes {@code app.realtime.idle-grace} after the last one closes. Held open
 * around the clock, it kept the free Neon database from ever going to sleep: Neon suspends after a
 * few minutes without queries and cuts the connection, and reconnecting woke it straight back up.
 *
 * <p>Seat maps opened while the connection isn't up get "ready" only once LISTEN is active
 * ({@link SeatStreams#listenerUp}), so the client never fetches a map whose later changes could be
 * missed. If the connection drops, it reconnects with backoff and the maps that were open reload.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class SeatChangeListener {

    private static final int POLL_MILLIS = 1000;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final JdbcConnectionDetails db;
    private final SeatStreams streams;
    private final ObjectMapper objectMapper;
    private volatile boolean running;
    private volatile boolean listening;
    private final Duration idleGrace;
    private Thread thread;

    public SeatChangeListener(JdbcConnectionDetails db, SeatStreams streams, ObjectMapper objectMapper,
                              @Value("${app.realtime.idle-grace:PT1M}") Duration idleGrace) {
        this.db = db;
        this.streams = streams;
        this.objectMapper = objectMapper;
        this.idleGrace = idleGrace;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("seat-change-listener").daemon().start(this::loop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** True once LISTEN is active (used by tests to avoid racing the first notification). */
    public boolean isListening() {
        return listening;
    }

    private void loop() {
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            if (!streams.awaitWatchers(Duration.ofSeconds(5))) {
                continue; // nobody is watching a seat map: no connection, so the database can sleep
            }
            try (Connection connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
                try (Statement st = connection.createStatement()) {
                    st.execute("LISTEN " + SeatEvents.CHANNEL);
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                try {
                    listening = true;
                    backoff = Duration.ofSeconds(1);
                    SeatStreams.ListenerUp up = streams.listenerUp();
                    log.info("Listening for seat changes ({} seat map(s) now ready, {} asked to reload)",
                            up.readied(), up.reloaded());
                    listenWhileWatched(pg);
                } finally {
                    // Before the connection closes, and before any backoff: new maps must wait from here on
                    listening = false;
                    streams.listenerDown();
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("Seat change listener lost its connection; retrying in {}s: {}", backoff.toSeconds(), e.getMessage());
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    /** Dispatches notifications until nobody has watched a seat map for {@link #idleGrace}. */
    private void listenWhileWatched(PGConnection pg) throws Exception {
        Instant idleSince = null;
        while (running) {
            PGNotification[] notifications = pg.getNotifications(POLL_MILLIS);
            if (notifications != null) {
                for (PGNotification n : notifications) {
                    dispatch(n.getParameter());
                }
            }
            if (streams.hasWatchers()) {
                idleSince = null;
            } else if (idleSince == null) {
                idleSince = Instant.now();
            } else if (Duration.between(idleSince, Instant.now()).compareTo(idleGrace) >= 0) {
                log.info("No seat maps open for {}s; closing the listener connection", idleGrace.toSeconds());
                return;
            }
        }
    }

    void dispatch(String json) {
        try {
            streams.publish(objectMapper.readValue(json, SeatEvents.Payload.class));
        } catch (Exception e) {
            log.warn("Could not dispatch seat change {}: {}", json, e.getMessage());
        }
    }
}

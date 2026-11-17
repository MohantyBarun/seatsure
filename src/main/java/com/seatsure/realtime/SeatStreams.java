package com.seatsure.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextClosedEvent;
import com.seatsure.entity.ShowSeatStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The browsers watching each show's seat map on this instance, as Server-Sent Events streams.
 * SSE rather than WebSockets: updates only flow one way, it's plain HTTP (works through proxies),
 * and browsers reconnect on their own.
 *
 * <p>Servlet async: an open stream holds no request thread, only a socket.
 *
 * <p><b>"ready" means changes are being listened for.</b> The client fetches the seat map when it
 * gets "ready" and patches it with the events that follow, so "ready" must never come before the
 * database LISTEN is active, or a change committed in between would be lost. The listener only
 * holds its connection while someone has a seat map open (so an idle site lets the database
 * sleep); a stream opened while it isn't connected waits, and gets "ready" once it is
 * ({@link #listenerUp}).
 */
@Slf4j
@Component
public class SeatStreams {

    /** Browsers reconnect on their own when this runs out (and refetch the map). */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    public record SeatChange(Long seatId, ShowSeatStatus status) {
    }

    public record SeatsEvent(Long showId, List<SeatChange> changes) {
    }

    private final Map<Long, Set<SseEmitter>> byShow = new ConcurrentHashMap<>();
    /** Streams opened while the listener wasn't connected: they get "ready" when it is. */
    private final Map<SseEmitter, Long> waiting = new ConcurrentHashMap<>();
    /** Guards {@link #live} together with sending "ready", so no stream misses the switch. */
    private final Object gate = new Object();
    /** Signalled when a stream opens, to wake the listener. */
    private final Object demand = new Object();
    private final boolean realtimeEnabled;
    private boolean live;

    public record ListenerUp(int readied, int reloaded) {
    }

    public SeatStreams(MeterRegistry meters, @Value("${app.realtime.enabled:true}") boolean realtimeEnabled) {
        this.realtimeEnabled = realtimeEnabled;
        Gauge.builder("seatmap.streams.open", this, SeatStreams::openStreams)
                .description("Seat-map SSE streams open on this instance").register(meters);
    }

    public SseEmitter subscribe(Long showId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        // Added inside compute(): atomic with drop(), which removes a show whose last stream closed
        byShow.compute(showId, (id, set) -> {
            Set<SseEmitter> watchers = set == null ? ConcurrentHashMap.newKeySet() : set;
            watchers.add(emitter);
            return watchers;
        });
        Runnable remove = () -> drop(showId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        synchronized (gate) {
            if (live || !realtimeEnabled) {
                sendReady(emitter, showId, remove);
            } else {
                waiting.put(emitter, showId);
            }
        }
        synchronized (demand) {
            demand.notifyAll();
        }
        return emitter;
    }

    /** Tells the client the stream is live; it then fetches the map (and applies events after it). */
    private void sendReady(SseEmitter emitter, Long showId, Runnable onFailure) {
        send(emitter, SseEmitter.event().name("ready").data(Map.of("showId", showId)), onFailure);
    }

    /**
     * Forgets a stream: it closed, timed out, or a write to it failed (the browser went away). Every
     * removal goes through here, so a show with no streams left disappears and {@link #hasWatchers}
     * turns false (and the listener can let go of its connection).
     */
    private void drop(Long showId, SseEmitter emitter) {
        waiting.remove(emitter);
        byShow.computeIfPresent(showId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }

    /** True while at least one seat map is open on this instance. */
    public boolean hasWatchers() {
        return byShow.values().stream().anyMatch(set -> !set.isEmpty());
    }

    /** Blocks until a seat map is open (true) or {@code max} has passed (false). */
    public boolean awaitWatchers(Duration max) {
        synchronized (demand) {
            if (!hasWatchers()) {
                try {
                    demand.wait(max.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return hasWatchers();
        }
    }

    /**
     * The listener's LISTEN is active. Streams that were waiting get "ready" (they now fetch the
     * map). Streams that were already open get "reload": changes committed while the listener was
     * down were never delivered, so their maps can't be patched, only refetched.
     */
    public ListenerUp listenerUp() {
        synchronized (gate) {
            live = true;
            int[] counts = {0, 0};
            byShow.forEach((showId, watchers) -> watchers.forEach(emitter -> {
                Runnable drop = () -> drop(showId, emitter);
                if (waiting.remove(emitter) != null) {
                    sendReady(emitter, showId, drop);
                    counts[0]++;
                } else {
                    send(emitter, SseEmitter.event().name("reload").data(Map.of("showId", showId)), drop);
                    counts[1]++;
                }
            }));
            return new ListenerUp(counts[0], counts[1]);
        }
    }

    /** The listener's connection is gone: new streams wait for it before getting "ready". */
    public void listenerDown() {
        synchronized (gate) {
            live = false;
        }
    }

    int waitingStreams() {
        return waiting.size();
    }

    /** Fans a committed change out to this instance's watchers of the show. */
    void publish(SeatEvents.Payload payload) {
        Set<SseEmitter> watchers = byShow.get(payload.s());
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder event;
        if (Boolean.TRUE.equals(payload.r()) || payload.c() == null) {
            event = SseEmitter.event().name("reload").data(Map.of("showId", payload.s()));
        } else {
            List<SeatChange> changes = payload.c().stream()
                    .map(pair -> new SeatChange(((Number) pair.get(0)).longValue(), SeatEvents.status((String) pair.get(1))))
                    .toList();
            event = SseEmitter.event().name("seats").data(new SeatsEvent(payload.s(), changes), MediaType.APPLICATION_JSON);
        }
        for (SseEmitter emitter : watchers) {
            send(emitter, event, () -> drop(payload.s(), emitter));
        }
    }


    /**
     * On shutdown (a deploy), end every stream first. Graceful shutdown waits for in-flight requests,
     * and an open stream never finishes on its own, so each would hold the old instance for the full
     * shutdown timeout. Browsers' EventSource reconnects by itself, to an instance that's still up.
     * ContextClosedEvent comes before the web server's graceful shutdown starts.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        byShow.values().forEach(watchers -> watchers.forEach(SseEmitter::complete));
        byShow.clear();
        waiting.clear();
    }

    /** A comment line every 20 seconds, so proxies don't close streams that are quiet. */
    @Scheduled(fixedDelay = 20_000)
    void heartbeat() {
        byShow.forEach((showId, watchers) -> watchers.forEach(emitter ->
                send(emitter, SseEmitter.event().comment("keep-alive"), () -> drop(showId, emitter))));
    }

    int openStreams() {
        return byShow.values().stream().mapToInt(Set::size).sum();
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event, Runnable onFailure) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The browser went away (or the stream already completed)
            onFailure.run();
            emitter.completeWithError(e);
        }
    }
}

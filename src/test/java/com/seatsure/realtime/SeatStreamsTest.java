package com.seatsure.realtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SeatStreamsTest {

    private final SeatStreams streams = new SeatStreams(new SimpleMeterRegistry(), true);

    @Test
    @DisplayName("a seat map opened before the listener is up waits for it before it's told to fetch")
    void streamsWaitForTheListener() {
        streams.subscribe(1L);
        streams.subscribe(2L);

        assertThat(streams.waitingStreams()).isEqualTo(2);
        assertThat(streams.listenerUp()).isEqualTo(new SeatStreams.ListenerUp(2, 0));
        assertThat(streams.waitingStreams()).isZero();
    }

    @Test
    @DisplayName("while the listener is up, new seat maps are ready straight away")
    void streamsOpenedWhileListening_areReadyAtOnce() {
        streams.listenerUp();
        streams.subscribe(1L);

        assertThat(streams.waitingStreams()).isZero();
    }

    @Test
    @DisplayName("when the listener comes back, maps that were already open reload; new ones become ready")
    void reconnect_reloadsOpenMaps() {
        streams.listenerUp();
        streams.subscribe(1L);
        streams.subscribe(1L);
        streams.listenerDown();
        streams.subscribe(2L);

        assertThat(streams.listenerUp()).isEqualTo(new SeatStreams.ListenerUp(1, 2));
    }

    @Test
    @DisplayName("with realtime turned off there is no listener to wait for")
    void realtimeOff_readyAtOnce() {
        SeatStreams off = new SeatStreams(new SimpleMeterRegistry(), false);
        off.subscribe(1L);

        assertThat(off.waitingStreams()).isZero();
    }

    @Test
    @DisplayName("the listener only connects while someone is watching")
    void awaitWatchers() {
        assertThat(streams.hasWatchers()).isFalse();
        assertThat(streams.awaitWatchers(Duration.ofMillis(50))).isFalse();

        streams.subscribe(1L);

        assertThat(streams.awaitWatchers(Duration.ofMillis(50))).isTrue();
        streams.closeAll();
        assertThat(streams.hasWatchers()).isFalse();
    }

    @Test
    void closeAll_endsEveryStream_soShutdownDoesntWaitOnThem() {
        streams.subscribe(1L);
        streams.subscribe(2L);

        streams.closeAll();

        assertThat(streams.openStreams()).isZero();
        assertThat(streams.waitingStreams()).isZero();
    }
}

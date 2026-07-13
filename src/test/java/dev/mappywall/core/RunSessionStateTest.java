package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunSessionStateTest {
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(15);
    private static final Instant STARTED_AT = Instant.parse("2026-07-13T00:00:00Z");

    @Test
    void clearsTimedOutTransactionsBeforeAnExplicitRetry() {
        RunSessionState session = sessionWithPendingTransactions(STARTED_AT);

        RunSessionState recovered = session.clearTimedOutTransactions(
                STARTED_AT.plus(ACK_TIMEOUT).plusMillis(1),
                ACK_TIMEOUT
        );

        assertNull(recovered.pendingMapOpening());
        assertNull(recovered.pendingMapZoom());
    }

    @Test
    void clearsTransactionsAtTheTimeoutBoundary() {
        RunSessionState session = sessionWithPendingTransactions(STARTED_AT);

        RunSessionState recovered = session.clearTimedOutTransactions(
                STARTED_AT.plus(ACK_TIMEOUT),
                ACK_TIMEOUT
        );

        assertNull(recovered.pendingMapOpening());
        assertNull(recovered.pendingMapZoom());
    }

    @Test
    void clearsTransactionsWhoseTimestampIsInTheFutureAfterAClockRollback() {
        RunSessionState session = sessionWithPendingTransactions(STARTED_AT.plusSeconds(60));

        RunSessionState recovered = session.clearTimedOutTransactions(STARTED_AT, ACK_TIMEOUT);

        assertNull(recovered.pendingMapOpening());
        assertNull(recovered.pendingMapZoom());
    }

    @Test
    void keepsRecentTransactionsWhileWaitingForAcknowledgement() {
        RunSessionState session = sessionWithPendingTransactions(STARTED_AT);

        RunSessionState recovered = session.clearTimedOutTransactions(
                STARTED_AT.plus(ACK_TIMEOUT).minusMillis(1),
                ACK_TIMEOUT
        );

        assertNotNull(recovered.pendingMapOpening());
        assertNotNull(recovered.pendingMapZoom());
    }

    @Test
    void preservesKnownMapScaleLineageAcrossSessionUpdates() {
        RunSessionState session = sessionWithPendingTransactions(STARTED_AT)
                .withKnownMapScale(12, 2)
                .withPaused(false)
                .withPendingMapZoom(null);

        assertEquals(2, session.knownScaleForMapId(12));
        assertNull(session.knownScaleForMapId(99));
    }

    private RunSessionState sessionWithPendingTransactions(Instant startedAt) {
        PendingMapOpening opening = new PendingMapOpening("region", Set.of(4), 7, startedAt);
        PendingMapZoom zoom = new PendingMapZoom("region", 4, 1, Set.of(4), 1, startedAt);
        return new RunSessionState(
                0,
                true,
                null,
                "default",
                0,
                List.of(),
                zoom,
                opening,
                Map.of(),
                RunSessionState.CURRENT_BINDING_DATA_VERSION
        );
    }
}

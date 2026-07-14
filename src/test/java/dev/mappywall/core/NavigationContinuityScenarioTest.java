package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import dev.mappywall.core.PathSegmentCoordinator.LookaheadRequest;
import dev.mappywall.core.PathSegmentCoordinator.Segment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NavigationContinuityScenarioTest {
    @Test
    void keepsLongRouteContinuousAndRejectsLateCompletionAfterTargetReset() {
        PathSegmentCoordinator<Integer> coordinator = new PathSegmentCoordinator<>();
        FakeDriver driver = new FakeDriver(coordinator);
        List<Integer> visited = new ArrayList<>();

        coordinator.resetTarget("long-route");
        coordinator.installInitial(segment(0, 21));

        LookaheadRequest delayed = coordinator.beginLookahead(true).orElseThrow();
        List<Integer> initialSnapshot = coordinator.remainingStepSnapshot();
        assertEquals(range(1, 21), initialSnapshot);
        assertThrows(UnsupportedOperationException.class, () -> initialSnapshot.add(22));

        consume(driver, visited, 1, 7);
        assertEquals(range(1, 7), visited);
        assertEquals(range(1, 21), initialSnapshot);
        assertEquals(range(8, 21), coordinator.remainingStepSnapshot());
        assertFalse(coordinator.hasBuffered());

        assertTrue(coordinator.acceptLookahead(delayed, segment(21, 42)));
        assertEquals(range(8, 21), coordinator.remainingStepSnapshot());
        consume(driver, visited, 8, 21);

        assertEquals(Optional.of(22), driver.nextStep());
        assertEquals(range(22, 42), coordinator.remainingStepSnapshot());

        LookaheadRequest third = coordinator.beginLookahead(true).orElseThrow();
        assertTrue(coordinator.acceptLookahead(third, segment(42, 63)));
        consume(driver, visited, 22, 42);
        assertEquals(Optional.of(43), driver.nextStep());

        LookaheadRequest fourth = coordinator.beginLookahead(true).orElseThrow();
        assertTrue(coordinator.acceptLookahead(fourth, segment(63, 84)));
        consume(driver, visited, 43, 63);
        assertEquals(Optional.of(64), driver.nextStep());
        consume(driver, visited, 64, 70);

        assertEquals(range(1, 70), visited);
        assertEquals(range(71, 84), coordinator.remainingStepSnapshot());

        LookaheadRequest obsolete = coordinator.beginLookahead(true).orElseThrow();
        coordinator.resetTarget("replacement-route");

        assertTrue(coordinator.remainingStepSnapshot().isEmpty());
        assertFalse(coordinator.acceptLookahead(obsolete, segment(84, 105)));

        coordinator.installInitial(segment(1_000, 1_003, true, false));
        assertFalse(coordinator.acceptLookahead(obsolete, segment(84, 105)));
        consume(driver, new ArrayList<>(), 1_001, 1_003);

        assertTrue(driver.nextStep().isEmpty());
        assertTrue(coordinator.remainingStepSnapshot().isEmpty());
        assertFalse(coordinator.hasBuffered());
    }

    private static void consume(FakeDriver driver, List<Integer> visited, int first, int lastInclusive) {
        for (int expected = first; expected <= lastInclusive; expected++) {
            int step = driver.nextStep().orElseThrow();
            assertEquals(expected, step);
            visited.add(step);
            assertTrue(driver.advance());
        }
    }

    private static Segment<Integer> segment(int start, int end) {
        return segment(start, end, false, true);
    }

    private static Segment<Integer> segment(
            int start,
            int end,
            boolean terminal,
            boolean prefetchable
    ) {
        return new Segment<>(
                anchor(start),
                anchor(end),
                range(start + 1, end),
                terminal,
                prefetchable
        );
    }

    private static Anchor anchor(int x) {
        return new Anchor(x, 64, 0);
    }

    private static List<Integer> range(int first, int lastInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int value = first; value <= lastInclusive; value++) {
            values.add(value);
        }
        return values;
    }

    private static final class FakeDriver {
        private final PathSegmentCoordinator<Integer> coordinator;

        private FakeDriver(PathSegmentCoordinator<Integer> coordinator) {
            this.coordinator = coordinator;
        }

        private Optional<Integer> nextStep() {
            return coordinator.currentStepOrPromote();
        }

        private boolean advance() {
            return coordinator.advance();
        }
    }
}

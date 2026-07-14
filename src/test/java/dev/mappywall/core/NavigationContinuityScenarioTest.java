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
    void sixStepSegmentsRemainContinuousWithSevenTickCaptureAndFiveTickPlanner() {
        NavigationPlanningCadence cadence = NavigationPlanningCadence.defaults();
        NavigationPlanningRetryState retry = new NavigationPlanningRetryState(cadence);
        PathSegmentCoordinator<Integer> coordinator = new PathSegmentCoordinator<>();
        SixStepPipeline pipeline = new SixStepPipeline(coordinator, retry, cadence);

        coordinator.resetTarget("six-step-route");
        coordinator.installInitial(sixStepSegment(1));

        pipeline.runUntilComplete();

        assertEquals(range(1, 96), pipeline.completedSteps());
        assertEquals(7, pipeline.captureTicks());
        assertEquals(5, pipeline.plannerTicks());
        assertEquals(288, pipeline.logicalTicks());
        assertEquals(0, pipeline.emptyCurrentStepTicks());
        assertEquals(0, retry.remainingTicks());
        assertTrue(retry.canSubmit());
        assertEquals(pipeline.firstPromotionTick(), pipeline.secondLookaheadStartTick());
    }

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

    private static Segment<Integer> sixStepSegment(int oneBasedSegment) {
        int start = (oneBasedSegment - 1) * 6;
        int end = oneBasedSegment * 6;
        boolean terminal = oneBasedSegment == 16;
        return segment(start, end, terminal, !terminal);
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

    private static final class SixStepPipeline {
        private static final int TOTAL_SEGMENTS = 16;
        private static final int TICKS_PER_STEP = 3;
        private static final int SNAPSHOT_COLUMNS = 3_249;
        private static final int FAKE_PLANNER_TICKS = 5;

        private final PathSegmentCoordinator<Integer> coordinator;
        private final NavigationPlanningRetryState retry;
        private final int captureTicks;
        private final List<Integer> completedSteps = new ArrayList<>();
        private PendingContinuation pending;
        private int logicalTicks;
        private int ticksOnCurrentStep;
        private int emptyCurrentStepTicks;
        private int firstPromotionTick = -1;
        private int secondLookaheadStartTick = -1;

        private SixStepPipeline(
                PathSegmentCoordinator<Integer> coordinator,
                NavigationPlanningRetryState retry,
                NavigationPlanningCadence cadence
        ) {
            this.coordinator = coordinator;
            this.retry = retry;
            captureTicks = (SNAPSHOT_COLUMNS + cadence.snapshotColumnsPerTick() - 1)
                    / cadence.snapshotColumnsPerTick();
        }

        private void runUntilComplete() {
            while (completedSteps.size() < TOTAL_SEGMENTS * 6 && logicalTicks < 1_000) {
                retry.beginTick();
                acceptReadyContinuation();

                boolean promoted = coordinator.promoteBuffered();
                if (promoted && firstPromotionTick < 0) {
                    firstPromotionTick = logicalTicks;
                }

                beginEligibleLookahead();

                Optional<Integer> current = coordinator.currentStepOrPromote();
                if (current.isEmpty()) {
                    emptyCurrentStepTicks++;
                    logicalTicks++;
                    continue;
                }

                int step = current.orElseThrow();
                ticksOnCurrentStep++;
                if (ticksOnCurrentStep == TICKS_PER_STEP) {
                    assertTrue(coordinator.advance());
                    completedSteps.add(step);
                    ticksOnCurrentStep = 0;
                    boolean canPromoteNow = coordinator.remainingSteps() == 0
                            && coordinator.hasBuffered();
                    Optional<Integer> afterAdvance = coordinator.currentStepOrPromote();
                    if (canPromoteNow && afterAdvance.isPresent() && firstPromotionTick < 0) {
                        firstPromotionTick = logicalTicks;
                    }
                }
                beginEligibleLookahead();
                assertEquals(0, retry.remainingTicks());
                assertTrue(retry.canSubmit());
                logicalTicks++;
            }
        }

        private void acceptReadyContinuation() {
            if (pending == null || logicalTicks < pending.readyTick()) {
                return;
            }
            assertTrue(coordinator.acceptLookahead(
                    pending.request(),
                    sixStepSegment(pending.nextSegment())
            ));
            retry.onSuccess();
            pending = null;
        }

        private void beginEligibleLookahead() {
            if (pending != null || !retry.canSubmit()) {
                return;
            }
            coordinator.beginLookahead(true).ifPresent(request -> {
                int nextSegment = request.seam().x() / 6 + 1;
                pending = new PendingContinuation(
                        request,
                        nextSegment,
                        logicalTicks + captureTicks + FAKE_PLANNER_TICKS
                );
                if (nextSegment == 3) {
                    secondLookaheadStartTick = logicalTicks;
                }
            });
        }

        private List<Integer> completedSteps() {
            return List.copyOf(completedSteps);
        }

        private int captureTicks() {
            return captureTicks;
        }

        private int plannerTicks() {
            return FAKE_PLANNER_TICKS;
        }

        private int logicalTicks() {
            return logicalTicks;
        }

        private int emptyCurrentStepTicks() {
            return emptyCurrentStepTicks;
        }

        private int firstPromotionTick() {
            return firstPromotionTick;
        }

        private int secondLookaheadStartTick() {
            return secondLookaheadStartTick;
        }

        private record PendingContinuation(
                LookaheadRequest request,
                int nextSegment,
                int readyTick
        ) {
        }
    }
}

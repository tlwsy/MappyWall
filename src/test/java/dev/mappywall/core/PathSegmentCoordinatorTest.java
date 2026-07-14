package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import dev.mappywall.core.PathSegmentCoordinator.LookaheadRequest;
import dev.mappywall.core.PathSegmentCoordinator.Segment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSegmentCoordinatorTest {
    private final PathSegmentCoordinator<Integer> coordinator = new PathSegmentCoordinator<>();

    @Test
    void keepsExecutingActiveSegmentWhileLookaheadIsPending() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(20), range(1, 20), false, true));

        for (int index = 0; index < 7; index++) {
            assertTrue(coordinator.advance());
        }

        assertTrue(coordinator.beginLookahead(true).isPresent());
        assertEquals(8, coordinator.currentStep().orElseThrow());
        assertEquals(13, coordinator.remainingSteps());
    }

    @Test
    void swapsBufferedSegmentAtSeamWithoutEmptyStep() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest request = coordinator.beginLookahead(true).orElseThrow();

        assertTrue(coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(4), List.of(3, 4), true, false)
        ));
        assertTrue(coordinator.advance());
        assertTrue(coordinator.advance());
        assertTrue(coordinator.promoteBuffered());

        assertEquals(3, coordinator.currentStep().orElseThrow());
        assertEquals(2, coordinator.remainingSteps());
    }

    @Test
    void rejectsLateCompletionFromOldGeneration() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest old = coordinator.beginLookahead(true).orElseThrow();

        coordinator.resetTarget("target-b");

        assertFalse(coordinator.acceptLookahead(
                old,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));
    }

    @Test
    void repeatedTargetTextStillInvalidatesTheOldGeneration() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest old = coordinator.beginLookahead(true).orElseThrow();

        coordinator.resetTarget("target-a");

        assertTrue(coordinator.currentStep().isEmpty());
        assertFalse(coordinator.acceptLookahead(
                old,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));
    }

    @Test
    void targetChangeInvalidatesBothSegments() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), false, true));
        coordinator.acceptLookahead(
                coordinator.beginLookahead(true).orElseThrow(),
                segment(anchor(1), anchor(2), List.of(2), false, true)
        );

        coordinator.resetTarget("target-b");

        assertTrue(coordinator.currentStep().isEmpty());
        assertFalse(coordinator.hasBuffered());
        assertEquals(0, coordinator.remainingSteps());
    }

    @Test
    void doesNotRequestLookaheadForUnstableSuffix() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(3), List.of(1, 2, 3), false, true));

        assertTrue(coordinator.beginLookahead(false).isEmpty());
    }

    @Test
    void doesNotRequestLookaheadForNonPrefetchableSegment() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), false, false));

        assertTrue(coordinator.beginLookahead(true).isEmpty());
    }

    @Test
    void doesNotRequestLookaheadForTerminalSegment() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), true, true));

        assertTrue(coordinator.beginLookahead(true).isEmpty());
    }

    @Test
    void doesNotRequestLookaheadForExhaustedSegment() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), false, true));
        assertTrue(coordinator.advance());

        assertTrue(coordinator.beginLookahead(true).isEmpty());
    }

    @Test
    void rejectsAContinuationWhoseStartDoesNotMatchTheExactSeam() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest request = coordinator.beginLookahead(true).orElseThrow();

        assertFalse(coordinator.acceptLookahead(
                request,
                segment(new Anchor(2, 65, 0), anchor(4), List.of(3, 4), false, true)
        ));
        assertTrue(coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));
    }

    @Test
    void rejectsDuplicatePendingAndBufferedLookaheadRequests() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest request = coordinator.beginLookahead(true).orElseThrow();

        assertTrue(coordinator.beginLookahead(true).isEmpty());
        assertTrue(coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));
        assertTrue(coordinator.beginLookahead(true).isEmpty());
        assertTrue(coordinator.hasBuffered());
    }

    @Test
    void failedRequestCleanupAllowsAnotherLookahead() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest failed = coordinator.beginLookahead(true).orElseThrow();

        assertTrue(coordinator.failLookahead(failed));
        LookaheadRequest retry = coordinator.beginLookahead(true).orElseThrow();

        assertNotEquals(failed.id(), retry.id());
        assertFalse(coordinator.acceptLookahead(
                failed,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));
    }

    @Test
    void requiresTheExactPendingRequestInstance() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest pending = coordinator.beginLookahead(true).orElseThrow();
        LookaheadRequest equalButForged = new LookaheadRequest(
                pending.id(),
                pending.generation(),
                pending.targetKey(),
                pending.seam()
        );

        assertFalse(coordinator.acceptLookahead(
                equalButForged,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));
        assertTrue(coordinator.acceptLookahead(
                pending,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));
    }

    @Test
    void segmentDefensivelyCopiesItsSteps() {
        List<Integer> source = new ArrayList<>(List.of(1, 2));

        Segment<Integer> segment = segment(anchor(0), anchor(2), source, false, true);
        source.add(3);

        assertEquals(List.of(1, 2), segment.steps());
        assertThrows(UnsupportedOperationException.class, () -> segment.steps().add(4));
    }

    @Test
    void segmentRejectsNullAnchorsAndSteps() {
        assertThrows(NullPointerException.class,
                () -> new Segment<Integer>(null, anchor(1), List.of(1), false, true));
        assertThrows(NullPointerException.class,
                () -> new Segment<Integer>(anchor(0), null, List.of(1), false, true));
        assertThrows(NullPointerException.class,
                () -> new Segment<Integer>(anchor(0), anchor(1), null, false, true));

        List<Integer> stepsWithNull = new ArrayList<>();
        stepsWithNull.add(1);
        stepsWithNull.add(null);
        assertThrows(NullPointerException.class,
                () -> new Segment<>(anchor(0), anchor(1), stepsWithNull, false, true));
    }

    @Test
    void clearInvalidatesLateResultAndPreservesTargetForReinstall() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest old = coordinator.beginLookahead(true).orElseThrow();

        coordinator.clear();

        assertTrue(coordinator.currentStep().isEmpty());
        assertFalse(coordinator.hasBuffered());
        assertFalse(coordinator.acceptLookahead(
                old,
                segment(anchor(2), anchor(4), List.of(3, 4), false, true)
        ));

        coordinator.installInitial(segment(anchor(10), anchor(12), List.of(10, 11), false, true));
        LookaheadRequest fresh = coordinator.beginLookahead(true).orElseThrow();
        assertEquals("target-a", fresh.targetKey());
        assertNotEquals(old.generation(), fresh.generation());
    }

    @Test
    void doesNotPromoteUntilTheActiveSegmentIsExhausted() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
        LookaheadRequest request = coordinator.beginLookahead(true).orElseThrow();
        coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(3), List.of(3), true, false)
        );

        assertFalse(coordinator.promoteBuffered());
        assertEquals(1, coordinator.currentStep().orElseThrow());
    }

    @Test
    void advancingExhaustedOrMissingSegmentIsANoOp() {
        assertFalse(coordinator.advance());
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), true, false));

        assertTrue(coordinator.advance());
        assertFalse(coordinator.advance());
        assertTrue(coordinator.currentStep().isEmpty());
        assertEquals(0, coordinator.remainingSteps());
    }

    private static Anchor anchor(int x) {
        return new Anchor(x, 64, 0);
    }

    private static Segment<Integer> segment(
            Anchor start,
            Anchor end,
            List<Integer> steps,
            boolean terminal,
            boolean prefetchable
    ) {
        return new Segment<>(start, end, steps, terminal, prefetchable);
    }

    private static List<Integer> range(int first, int lastInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int value = first; value <= lastInclusive; value++) {
            values.add(value);
        }
        return values;
    }
}

package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import dev.mappywall.core.PathSegmentCoordinator.ContinuationCandidate;
import dev.mappywall.core.PathSegmentCoordinator.ContinuationPolicy;
import dev.mappywall.core.PathSegmentCoordinator.LookaheadRequest;
import dev.mappywall.core.PathSegmentCoordinator.PreviewPolicy;
import dev.mappywall.core.PathSegmentCoordinator.Segment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSegmentCoordinatorTest {
    private final PathSegmentCoordinator<Integer> coordinator = new PathSegmentCoordinator<>();

    @Test
    void keepsExecutingActiveSegmentWhileLookaheadIsPending() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(20), range(1, 20), false, ContinuationPolicy.IMMEDIATE));

        for (int index = 0; index < 7; index++) {
            assertTrue(coordinator.advance());
        }

        assertTrue(coordinator.beginLookahead(true, false).isPresent());
        assertEquals(8, coordinator.currentStep().orElseThrow());
        assertEquals(13, coordinator.remainingSteps());
    }

    @Test
    void swapsBufferedSegmentAtSeamWithoutEmptyStep() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest request = coordinator.beginLookahead(true, false).orElseThrow();

        assertTrue(coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(4), List.of(3, 4), true, ContinuationPolicy.NONE)
        ));
        assertTrue(coordinator.advance());
        assertTrue(coordinator.advance());
        assertTrue(coordinator.promoteBuffered());

        assertEquals(3, coordinator.currentStep().orElseThrow());
        assertEquals(2, coordinator.remainingSteps());
    }

    @Test
    void continuousBufferedSegmentIsIncludedInPreviewBeforePromotion() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(
                anchor(0),
                anchor(3),
                List.of(1, 2, 3),
                false,
                ContinuationPolicy.IMMEDIATE
        ));
        assertTrue(coordinator.advance());
        LookaheadRequest request = coordinator.beginLookahead(true, false).orElseThrow();
        assertTrue(coordinator.acceptLookahead(
                request,
                segment(
                        anchor(3),
                        anchor(5),
                        List.of(4, 5),
                        true,
                        ContinuationPolicy.NONE,
                        PreviewPolicy.CONTINUOUS
                )
        ));

        List<Integer> preview = coordinator.previewStepSnapshot();

        assertEquals(List.of(2, 3), coordinator.remainingStepSnapshot());
        assertEquals(List.of(2, 3, 4, 5), preview);
        assertEquals(2, coordinator.currentStep().orElseThrow());
        assertTrue(coordinator.hasBuffered());
        assertThrows(UnsupportedOperationException.class, () -> preview.add(6));
    }

    @Test
    void retreatBufferedSegmentIsHiddenUntilPromotion() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(
                anchor(0),
                anchor(1),
                List.of(1),
                false,
                ContinuationPolicy.IMMEDIATE
        ));
        LookaheadRequest request = coordinator.beginLookahead(true, false).orElseThrow();
        assertTrue(coordinator.acceptLookahead(
                request,
                segment(
                        anchor(1),
                        anchor(3),
                        List.of(2, 3),
                        true,
                        ContinuationPolicy.NONE,
                        PreviewPolicy.AFTER_PROMOTION
                )
        ));

        assertEquals(List.of(1), coordinator.previewStepSnapshot());
        assertEquals(List.of(1), coordinator.remainingStepSnapshot());
        assertTrue(coordinator.advance());
        assertTrue(coordinator.previewStepSnapshot().isEmpty());

        assertTrue(coordinator.promoteBuffered());
        assertEquals(List.of(2, 3), coordinator.previewStepSnapshot());
        assertEquals(2, coordinator.currentStep().orElseThrow());
    }

    @Test
    void targetResetClearsBufferedPreview() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(
                anchor(0),
                anchor(1),
                List.of(1),
                false,
                ContinuationPolicy.IMMEDIATE
        ));
        assertTrue(coordinator.acceptLookahead(
                coordinator.beginLookahead(true, false).orElseThrow(),
                segment(
                        anchor(1),
                        anchor(2),
                        List.of(2),
                        true,
                        ContinuationPolicy.NONE,
                        PreviewPolicy.CONTINUOUS
                )
        ));
        assertEquals(List.of(1, 2), coordinator.previewStepSnapshot());

        coordinator.resetTarget("target-b");

        assertTrue(coordinator.previewStepSnapshot().isEmpty());
        assertFalse(coordinator.hasBuffered());
    }

    @Test
    void clearClearsBufferedPreview() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(
                anchor(0),
                anchor(1),
                List.of(1),
                false,
                ContinuationPolicy.IMMEDIATE
        ));
        assertTrue(coordinator.acceptLookahead(
                coordinator.beginLookahead(true, false).orElseThrow(),
                segment(
                        anchor(1),
                        anchor(2),
                        List.of(2),
                        true,
                        ContinuationPolicy.NONE,
                        PreviewPolicy.CONTINUOUS
                )
        ));
        assertEquals(List.of(1, 2), coordinator.previewStepSnapshot());

        coordinator.clear();

        assertTrue(coordinator.previewStepSnapshot().isEmpty());
        assertFalse(coordinator.hasBuffered());
    }

    @Test
    void rejectsLateCompletionFromOldGeneration() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest old = coordinator.beginLookahead(true, false).orElseThrow();

        coordinator.resetTarget("target-b");

        assertFalse(coordinator.acceptLookahead(
                old,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
    }

    @Test
    void repeatedTargetTextStillInvalidatesTheOldGeneration() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest old = coordinator.beginLookahead(true, false).orElseThrow();

        coordinator.resetTarget("target-a");

        assertTrue(coordinator.currentStep().isEmpty());
        assertFalse(coordinator.acceptLookahead(
                old,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
    }

    @Test
    void targetChangeInvalidatesBothSegments() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), false, ContinuationPolicy.IMMEDIATE));
        coordinator.acceptLookahead(
                coordinator.beginLookahead(true, false).orElseThrow(),
                segment(anchor(1), anchor(2), List.of(2), false, ContinuationPolicy.IMMEDIATE)
        );

        coordinator.resetTarget("target-b");

        assertTrue(coordinator.currentStep().isEmpty());
        assertFalse(coordinator.hasBuffered());
        assertEquals(0, coordinator.remainingSteps());
    }

    @Test
    void doesNotRequestLookaheadForUnstableSuffix() {
        coordinator.resetTarget("target-a");
        for (ContinuationPolicy policy : ContinuationPolicy.values()) {
            coordinator.installInitial(segment(anchor(0), anchor(3), List.of(1, 2, 3), false, policy));
            assertTrue(coordinator.beginLookahead(false, true).isEmpty());
        }
    }

    @Test
    void unloadedSegmentWaitsUntilTerrainReadyThenBeginsLookahead() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(
                anchor(0),
                anchor(6),
                range(1, 6),
                false,
                ContinuationPolicy.WHEN_TERRAIN_READY
        ));

        ContinuationCandidate candidate = coordinator.continuationCandidate().orElseThrow();
        assertEquals(anchor(6), candidate.seam());
        assertEquals(ContinuationPolicy.WHEN_TERRAIN_READY, candidate.policy());
        assertTrue(coordinator.beginLookahead(true, false).isEmpty());
        assertTrue(coordinator.beginLookahead(true, false).isEmpty());
        assertEquals(candidate, coordinator.continuationCandidate().orElseThrow());

        LookaheadRequest request = coordinator.beginLookahead(true, true).orElseThrow();
        assertEquals(1L, request.id());
        assertEquals(anchor(6), request.seam());
        assertTrue(coordinator.continuationCandidate().isEmpty());
    }

    @Test
    void immediatePolicyBeginsLookaheadWithoutTerrainReadiness() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(
                anchor(0),
                anchor(2),
                List.of(1, 2),
                false,
                ContinuationPolicy.IMMEDIATE
        ));

        assertTrue(coordinator.beginLookahead(true, false).isPresent());
    }

    @Test
    void nonePolicyNeverRequestsLookaheadEvenWhenTerrainIsReady() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), false, ContinuationPolicy.NONE));

        assertTrue(coordinator.beginLookahead(true, true).isEmpty());
    }

    @Test
    void doesNotRequestLookaheadForTerminalSegment() {
        coordinator.resetTarget("target-a");
        for (ContinuationPolicy policy : ContinuationPolicy.values()) {
            coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), true, policy));
            assertTrue(coordinator.beginLookahead(true, true).isEmpty());
        }
    }

    @Test
    void doesNotRequestLookaheadForExhaustedSegment() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), false, ContinuationPolicy.IMMEDIATE));
        assertTrue(coordinator.advance());

        assertTrue(coordinator.beginLookahead(true, true).isEmpty());
    }

    @Test
    void rejectsAContinuationWhoseStartDoesNotMatchTheExactSeam() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest request = coordinator.beginLookahead(true, false).orElseThrow();

        assertFalse(coordinator.acceptLookahead(
                request,
                segment(new Anchor(2, 65, 0), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
        assertTrue(coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
    }

    @Test
    void rejectsDuplicatePendingAndBufferedLookaheadRequests() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest request = coordinator.beginLookahead(true, false).orElseThrow();

        assertTrue(coordinator.beginLookahead(true, true).isEmpty());
        assertTrue(coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
        assertTrue(coordinator.beginLookahead(true, true).isEmpty());
        assertTrue(coordinator.hasBuffered());
    }

    @Test
    void failedRequestCleanupAllowsAnotherLookahead() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest failed = coordinator.beginLookahead(true, false).orElseThrow();

        assertTrue(coordinator.failLookahead(failed));
        LookaheadRequest retry = coordinator.beginLookahead(true, false).orElseThrow();

        assertNotEquals(failed.id(), retry.id());
        assertFalse(coordinator.acceptLookahead(
                failed,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
    }

    @Test
    void requiresTheExactPendingRequestInstance() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest pending = coordinator.beginLookahead(true, false).orElseThrow();
        LookaheadRequest equalButForged = new LookaheadRequest(
                pending.id(),
                pending.generation(),
                pending.targetKey(),
                pending.seam()
        );

        assertFalse(coordinator.acceptLookahead(
                equalButForged,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
        assertTrue(coordinator.acceptLookahead(
                pending,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));
    }

    @Test
    void segmentDefensivelyCopiesItsSteps() {
        List<Integer> source = new ArrayList<>(List.of(1, 2));

        Segment<Integer> segment = segment(anchor(0), anchor(2), source, false, ContinuationPolicy.IMMEDIATE);
        source.add(3);

        assertEquals(List.of(1, 2), segment.steps());
        assertEquals(PreviewPolicy.CONTINUOUS, segment.previewPolicy());
        assertThrows(UnsupportedOperationException.class, () -> segment.steps().add(4));
    }

    @Test
    void segmentRejectsNullAnchorsAndSteps() {
        assertThrows(NullPointerException.class,
                () -> new Segment<Integer>(null, anchor(1), List.of(1), false, ContinuationPolicy.IMMEDIATE));
        assertThrows(NullPointerException.class,
                () -> new Segment<Integer>(anchor(0), null, List.of(1), false, ContinuationPolicy.IMMEDIATE));
        assertThrows(NullPointerException.class,
                () -> new Segment<Integer>(anchor(0), anchor(1), null, false, ContinuationPolicy.IMMEDIATE));

        List<Integer> stepsWithNull = new ArrayList<>();
        stepsWithNull.add(1);
        stepsWithNull.add(null);
        assertThrows(NullPointerException.class,
                () -> new Segment<>(anchor(0), anchor(1), stepsWithNull, false, ContinuationPolicy.IMMEDIATE));
        assertThrows(NullPointerException.class,
                () -> new Segment<>(anchor(0), anchor(1), List.of(1), false, null));
        assertThrows(NullPointerException.class,
                () -> new Segment<>(
                        anchor(0),
                        anchor(1),
                        List.of(1),
                        false,
                        ContinuationPolicy.IMMEDIATE,
                        null
                ));
    }

    @Test
    void continuationCandidateExposesOnlyAnEligibleActiveSegmentsExactSeamAndPolicy() {
        assertTrue(coordinator.continuationCandidate().isEmpty());

        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(
                anchor(0),
                new Anchor(7, 66, -3),
                List.of(1, 2),
                false,
                ContinuationPolicy.WHEN_TERRAIN_READY
        ));

        assertEquals(
                new ContinuationCandidate(
                        new Anchor(7, 66, -3),
                        ContinuationPolicy.WHEN_TERRAIN_READY
                ),
                coordinator.continuationCandidate().orElseThrow()
        );

        assertTrue(coordinator.advance());
        assertTrue(coordinator.advance());
        assertTrue(coordinator.continuationCandidate().isEmpty());

        coordinator.installInitial(segment(
                anchor(0),
                anchor(1),
                List.of(1),
                false,
                ContinuationPolicy.NONE
        ));
        assertTrue(coordinator.continuationCandidate().isEmpty());

        coordinator.installInitial(segment(
                anchor(0),
                anchor(1),
                List.of(1),
                true,
                ContinuationPolicy.IMMEDIATE
        ));
        assertTrue(coordinator.continuationCandidate().isEmpty());
    }

    @Test
    void clearInvalidatesLateResultAndPreservesTargetForReinstall() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest old = coordinator.beginLookahead(true, false).orElseThrow();

        coordinator.clear();

        assertTrue(coordinator.currentStep().isEmpty());
        assertFalse(coordinator.hasBuffered());
        assertFalse(coordinator.acceptLookahead(
                old,
                segment(anchor(2), anchor(4), List.of(3, 4), false, ContinuationPolicy.IMMEDIATE)
        ));

        coordinator.installInitial(segment(anchor(10), anchor(12), List.of(10, 11), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest fresh = coordinator.beginLookahead(true, false).orElseThrow();
        assertEquals("target-a", fresh.targetKey());
        assertNotEquals(old.generation(), fresh.generation());
    }

    @Test
    void doesNotPromoteUntilTheActiveSegmentIsExhausted() {
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, ContinuationPolicy.IMMEDIATE));
        LookaheadRequest request = coordinator.beginLookahead(true, false).orElseThrow();
        coordinator.acceptLookahead(
                request,
                segment(anchor(2), anchor(3), List.of(3), true, ContinuationPolicy.NONE)
        );

        assertFalse(coordinator.promoteBuffered());
        assertEquals(1, coordinator.currentStep().orElseThrow());
    }

    @Test
    void advancingExhaustedOrMissingSegmentIsANoOp() {
        assertFalse(coordinator.advance());
        coordinator.resetTarget("target-a");
        coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), true, ContinuationPolicy.NONE));

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
            ContinuationPolicy continuationPolicy
    ) {
        return new Segment<>(start, end, steps, terminal, continuationPolicy);
    }

    private static Segment<Integer> segment(
            Anchor start,
            Anchor end,
            List<Integer> steps,
            boolean terminal,
            ContinuationPolicy continuationPolicy,
            PreviewPolicy previewPolicy
    ) {
        return new Segment<>(start, end, steps, terminal, continuationPolicy, previewPolicy);
    }

    private static List<Integer> range(int first, int lastInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int value = first; value <= lastInclusive; value++) {
            values.add(value);
        }
        return values;
    }
}

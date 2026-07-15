package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class InitialPathPlanPreparerTest {
    private final InitialPathPlanPreparer preparer = new InitialPathPlanPreparer();
    private final MovementController controller = new MovementController();

    @Test
    void initialAcceptanceResolvesOnceAndReusesNormalizedFeet() {
        AtomicInteger resolutions = new AtomicInteger();
        BlockPos actualFeet = new BlockPos(0, 64, 0);
        LocalPathPlanner.PathPlan plan = plan(List.of(walk(1)));

        MovementController.InitialPlanAcceptance acceptance = controller.evaluateInitialPlanAcceptance(
                () -> {
                    resolutions.incrementAndGet();
                    return actualFeet;
                },
                plan.plannedStart(),
                plan,
                (validatedFeet, preparedPlan) -> {
                    assertSame(actualFeet, validatedFeet);
                    assertSame(actualFeet, preparedPlan.plannedStart());
                    return true;
                }
        );

        assertEquals(1, resolutions.get());
        assertNull(acceptance.failure());
        assertSame(actualFeet, acceptance.plan().plannedStart());
        assertEquals(LocalPathPlanner.StepAction.WALK, acceptance.plan().steps().getFirst().action());
        assertEquals(new BlockPos(1, 64, 0), acceptance.plan().steps().getFirst().pos());
    }

    @Test
    void initialAcceptanceClassifiesRawPartialBlockFeetAsInvalidPrefix() {
        LocalPathPlanner.PathPlan plan = plan(List.of(walk(1)));

        MovementController.InitialPlanAcceptance acceptance = controller.evaluateInitialPlanAcceptance(
                () -> new BlockPos(0, 63, 0),
                plan.plannedStart(),
                plan,
                (ignoredFeet, ignoredPlan) -> {
                    throw new AssertionError("rejected preparation must not reach live validation");
                }
        );

        assertEquals(MovementController.PlanningFailure.INITIAL_PREFIX, acceptance.failure());
        assertNull(acceptance.plan());
    }

    @Test
    void initialAcceptanceClassifiesRequestDriftAsLiveInvalidated() {
        LocalPathPlanner.PathPlan plan = plan(List.of(walk(1)));

        MovementController.InitialPlanAcceptance acceptance = controller.evaluateInitialPlanAcceptance(
                () -> new BlockPos(0, 66, 0),
                plan.plannedStart(),
                plan,
                (ignoredFeet, ignoredPlan) -> {
                    throw new AssertionError("drift must short-circuit prefix validation");
                }
        );

        assertEquals(MovementController.PlanningFailure.LIVE_INVALIDATED, acceptance.failure());
        assertNull(acceptance.plan());
    }

    @Test
    void initialAcceptanceClassifiesRejectedLivePrefixAsInvalidPrefix() {
        LocalPathPlanner.PathPlan plan = plan(List.of(walk(1)));

        MovementController.InitialPlanAcceptance acceptance = controller.evaluateInitialPlanAcceptance(
                () -> new BlockPos(0, 64, 0),
                plan.plannedStart(),
                plan,
                (ignoredFeet, ignoredPlan) -> false
        );

        assertEquals(MovementController.PlanningFailure.INITIAL_PREFIX, acceptance.failure());
        assertNull(acceptance.plan());
    }

    @Test
    void initialAcceptanceKeepsNonExecutablePlanClassification() {
        BlockPos start = new BlockPos(0, 64, 0);
        LocalPathPlanner.PathPlan plan = new LocalPathPlanner.PathPlan(
                start,
                List.of(),
                start,
                LocalPathPlanner.PathOutcome.NO_PATH,
                0
        );

        MovementController.InitialPlanAcceptance acceptance = controller.evaluateInitialPlanAcceptance(
                () -> start,
                start,
                plan,
                (ignoredFeet, ignoredPlan) -> {
                    throw new AssertionError("invalid plan must short-circuit prefix validation");
                }
        );

        assertEquals(MovementController.PlanningFailure.INVALID_PLAN, acceptance.failure());
        assertNull(acceptance.plan());
    }

    @Test
    void trimsConsumedMovementPrefixAndRebasesPlan() {
        LocalPathPlanner.PathStep remainingBreak = step(
                3,
                64,
                0,
                LocalPathPlanner.StepAction.BREAK,
                new BlockPos(3, 65, 0)
        );
        LocalPathPlanner.PathPlan original = new LocalPathPlanner.PathPlan(
                new BlockPos(0, 64, 0),
                List.of(walk(1), walk(2), remainingBreak),
                new BlockPos(3, 64, 0),
                LocalPathPlanner.PathOutcome.SAFE_FRONTIER,
                17
        );

        InitialPathPlanPreparer.PreparedInitialPath prepared = preparer.prepare(
                new BlockPos(2, 64, 0),
                original
        ).orElseThrow();

        assertEquals(2, prepared.trimCount());
        assertEquals(new BlockPos(2, 64, 0), prepared.plan().plannedStart());
        assertEquals(List.of(remainingBreak), prepared.plan().steps());
        assertEquals(original.plannedEnd(), prepared.plan().plannedEnd());
        assertEquals(original.outcome(), prepared.plan().outcome());
        assertEquals(17, prepared.plan().expandedNodes());
    }

    @Test
    void rejectedPrefixProducesNoPreparedPlan() {
        LocalPathPlanner.PathPlan original = plan(List.of(walkAt(2, 64, 0)));

        assertTrue(preparer.prepare(new BlockPos(0, 64, 0), original).isEmpty());
    }

    @Test
    void fullyConsumedPrefixProducesNoEmptyPreparedPlan() {
        LocalPathPlanner.PathPlan original = plan(List.of(walk(1), walk(2)));

        assertTrue(preparer.prepare(new BlockPos(2, 64, 0), original).isEmpty());
    }

    @Test
    void modificationEndpointIsNeverTreatedAsConsumed() {
        LocalPathPlanner.PathPlan original = plan(List.of(step(
                1,
                64,
                0,
                LocalPathPlanner.StepAction.PLACE,
                new BlockPos(1, 63, 0)
        )));

        assertTrue(preparer.prepare(new BlockPos(1, 64, 0), original).isEmpty());
    }

    @Test
    void acceptedDriftRebasesWithoutChangingSteps() {
        LocalPathPlanner.PathPlan original = plan(List.of(walk(1), walk(2)));
        BlockPos actual = new BlockPos(0, 64, 1);

        InitialPathPlanPreparer.PreparedInitialPath prepared =
                preparer.prepare(actual, original).orElseThrow();

        assertEquals(0, prepared.trimCount());
        assertEquals(actual, prepared.plan().plannedStart());
        assertEquals(original.steps(), prepared.plan().steps());
    }

    private static LocalPathPlanner.PathPlan plan(List<LocalPathPlanner.PathStep> steps) {
        return new LocalPathPlanner.PathPlan(
                new BlockPos(0, 64, 0),
                steps,
                steps.get(steps.size() - 1).pos(),
                LocalPathPlanner.PathOutcome.SAFE_FRONTIER,
                9
        );
    }

    private static LocalPathPlanner.PathStep walk(int x) {
        return walkAt(x, 64, 0);
    }

    private static LocalPathPlanner.PathStep walkAt(int x, int y, int z) {
        return step(x, y, z, LocalPathPlanner.StepAction.WALK, null);
    }

    private static LocalPathPlanner.PathStep step(
            int x,
            int y,
            int z,
            LocalPathPlanner.StepAction action,
            BlockPos actionBlock
    ) {
        return new LocalPathPlanner.PathStep(new BlockPos(x, y, z), action, actionBlock);
    }
}

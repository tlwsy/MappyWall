package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class InitialPathPlanPreparerTest {
    private final InitialPathPlanPreparer preparer = new InitialPathPlanPreparer();

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

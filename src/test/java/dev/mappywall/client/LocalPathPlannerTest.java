package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.client.LocalPathPlanner.Cell;
import dev.mappywall.client.LocalPathPlanner.ContinuationContext;
import dev.mappywall.client.LocalPathPlanner.NavigationSnapshot;
import dev.mappywall.client.LocalPathPlanner.PathOutcome;
import dev.mappywall.client.LocalPathPlanner.PathPlan;
import dev.mappywall.client.LocalPathPlanner.PathStep;
import dev.mappywall.client.LocalPathPlanner.StepAction;
import dev.mappywall.core.BlockTarget;
import dev.mappywall.core.MapBounds;
import dev.mappywall.core.MapRegion;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.WallPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LocalPathPlannerTest {
    private final LocalPathPlanner planner = new LocalPathPlanner();

    @Test
    void clientPlannerIsAvailableToNavigationTests() {
        assertNotNull(planner);
    }

    @Test
    void normalizedSnapshotStartKeepsFlatFirstWalkAtLogicalFeetY() {
        BlockPos start = new BlockPos(0, 64, 0);

        PathPlan plan = planner.plan(
                new TestTerrain().flatSurface(63).snapshot(start),
                routeTo(8, 0),
                config()
        );

        assertEquals(start, plan.plannedStart());
        assertFalse(plan.steps().isEmpty());
        assertEquals(StepAction.WALK, plan.steps().getFirst().action());
        assertEquals(64, plan.steps().getFirst().pos().getY());
    }

    @Test
    void continuationContextKeepsOnlyEightPreSeamNodesAndEntryDirection() {
        ArrayList<PathStep> suffix = new ArrayList<>();
        for (int x = 0; x <= 10; x++) {
            suffix.add(new PathStep(new BlockPos(x, 65, 0), StepAction.WALK, null));
        }
        BlockPos seam = new BlockPos(10, 65, 0);

        ContinuationContext context = ContinuationContext.fromSuffix(suffix, seam);

        assertTrue(context.constrained());
        assertEquals(1, context.approachDx());
        assertEquals(0, context.approachDz());
        assertEquals(8, context.recentTrail().size());
        assertTrue(context.recentTrail().contains(new BlockPos(2, 65, 0)));
        assertTrue(context.recentTrail().contains(new BlockPos(9, 65, 0)));
        assertFalse(context.recentTrail().contains(new BlockPos(1, 65, 0)));
        assertFalse(context.recentTrail().contains(seam));
        assertTrue(context.allows(seam, seam.offset(0, 0, 1)));
        assertTrue(context.allows(seam, seam.offset(1, 0, 0)));
        assertFalse(context.allows(seam, seam.offset(-1, 0, 1)));
    }

    @Test
    void invalidContinuationSuffixSafelyProducesNoConstraint() {
        BlockPos seam = new BlockPos(4, 65, 3);
        List<PathStep> verticalSuffix = List.of(
                new PathStep(seam.below(), StepAction.JUMP, null),
                new PathStep(seam, StepAction.JUMP, null)
        );

        assertEquals(ContinuationContext.none(), ContinuationContext.fromSuffix(List.of(), seam));
        assertEquals(
                ContinuationContext.none(),
                ContinuationContext.fromSuffix(List.of(new PathStep(seam, StepAction.WALK, null)), seam)
        );
        assertEquals(ContinuationContext.none(), ContinuationContext.fromSuffix(verticalSuffix, seam));
        assertEquals(
                ContinuationContext.none(),
                ContinuationContext.fromSuffix(
                        List.of(
                                new PathStep(seam.offset(-1, 0, 0), StepAction.WALK, null),
                                new PathStep(seam.offset(1, 0, 0), StepAction.WALK, null)
                        ),
                        seam
                )
        );
        assertFalse(ContinuationContext.none().constrained());
        assertTrue(ContinuationContext.none().allows(seam, seam.offset(-1, 0, 0)));
    }

    @Test
    void continuationContextRejectsRecentTrailInsideAllowedHalfPlane() {
        BlockPos seam = new BlockPos(0, 65, 0);
        BlockPos recentForwardNode = new BlockPos(1, 65, 1);
        ContinuationContext context = new ContinuationContext(
                1,
                0,
                Set.of(recentForwardNode)
        );

        assertFalse(context.allows(seam, recentForwardNode));
        assertTrue(context.allows(seam, new BlockPos(1, 65, -1)));
    }

    @Test
    void constrainedPlacementIgnoresForbiddenWalkWhenChoosingAllowedBridge() {
        BlockPos seam = new BlockPos(0, 65, 0);
        BlockPos recentSideNode = new BlockPos(0, 65, 1);
        TestTerrain terrain = new TestTerrain().isolatedSurface(
                seam,
                new BlockPos(-1, 65, 0)
        );
        ContinuationContext context = new ContinuationContext(
                1,
                0,
                Set.of(recentSideNode)
        );

        PathPlan plan = planner.plan(
                terrain.snapshot(seam),
                routeTo(0, 8),
                AutoNavigationConfig.aggressiveDefaults(),
                context
        );

        assertFalse(plan.usedRetreatFallback());
        assertFalse(plan.steps().isEmpty());
        assertEquals(StepAction.PLACE, plan.steps().getFirst().action());
        assertTrue(plan.steps().stream().allMatch(step -> context.allows(seam, step.pos())));
    }

    @Test
    void constrainedContinuationDoesNotReenterRecentTrailOrCrossBehindSeam() {
        BlockPos seam = new BlockPos(0, 65, 0);
        TestTerrain terrain = TestTerrain.twoCorridorContinuationDetour();
        NavigationSnapshot snapshot = terrain.snapshot(seam);
        RouteStep target = routeTo(0, 10);
        ContinuationContext context = new ContinuationContext(
                1,
                0,
                Set.of(new BlockPos(-1, 65, 0))
        );

        PathPlan unconstrained = planner.plan(snapshot, target, config());
        PathPlan constrained = planner.plan(snapshot, target, config(), context);

        assertEquals(PathOutcome.REACHED_TARGET, unconstrained.outcome());
        assertEquals(PathOutcome.REACHED_TARGET, constrained.outcome());
        assertEquals(new BlockPos(-1, 65, 0), unconstrained.steps().getFirst().pos());
        assertEquals(new BlockPos(1, 65, 0), constrained.steps().getFirst().pos());
        assertTrue(unconstrained.steps().size() < constrained.steps().size());
        assertTrue(constrained.steps().stream().allMatch(step -> context.allows(seam, step.pos())));
        assertFalse(constrained.usedRetreatFallback());
    }

    @Test
    void exhaustiveConstrainedNoPathFallsBackAndMarksNecessaryRetreat() {
        BlockPos seam = new BlockPos(0, 65, 0);
        TestTerrain terrain = new TestTerrain().isolatedSurface(
                seam,
                new BlockPos(-1, 65, 0),
                new BlockPos(-2, 65, 0),
                new BlockPos(-3, 65, 0),
                new BlockPos(-4, 65, 0),
                new BlockPos(-5, 65, 0)
        );
        NavigationSnapshot snapshot = terrain.snapshot(seam);
        RouteStep target = routeTo(-8, 0);
        ContinuationContext context = new ContinuationContext(
                1,
                0,
                Set.of(new BlockPos(-1, 65, 0))
        );
        PathPlan unconstrained = planner.plan(snapshot, target, config());

        PathPlan plan = planner.plan(snapshot, target, config(), context);

        assertEquals(PathOutcome.REACHED_TARGET, plan.outcome());
        assertEquals(new BlockPos(-1, 65, 0), plan.steps().getFirst().pos());
        assertTrue(plan.usedRetreatFallback());
        assertEquals(unconstrained.expandedNodes() + 1, plan.expandedNodes());
    }

    @Test
    void nodeLimitedConstrainedSearchNeverRelaxesTrailConstraint() {
        LocalPathPlanner budgetedPlanner = new LocalPathPlanner(1);
        BlockPos seam = new BlockPos(0, 65, 0);
        ContinuationContext context = new ContinuationContext(
                1,
                0,
                Set.of(new BlockPos(-1, 65, 0))
        );

        PathPlan plan = budgetedPlanner.plan(
                new TestTerrain().flatSurface(64).snapshot(seam),
                routeTo(80, 0),
                config(),
                context
        );

        assertEquals(PathOutcome.NODE_LIMIT, plan.outcome());
        assertTrue(plan.steps().isEmpty());
        assertFalse(plan.usedRetreatFallback());
        assertEquals(1, plan.expandedNodes());
    }

    @Test
    void prefersReversibleSurfaceDetourOverCloserCaveFrontier() {
        TestTerrain terrain = new TestTerrain()
                .flatSurface(64)
                .roof(2, 27, 69)
                .wall(27, -1, 1, 64);

        PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(80, 0), config());

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertTrue(plan.steps().stream().anyMatch(step -> Math.abs(step.pos().getZ()) >= 2));
        assertTrue(plan.steps().stream().noneMatch(step -> terrain.coveredDepth(step.pos()) > 3));
    }

    @Test
    void doesNotCommitThreeBlockDropWhenLocalPlanIsPartial() {
        TestTerrain terrain = TestTerrain.threeBlockDropTowardTargetWithNoReturn();

        PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(80, 0), config());

        assertTrue(plan.steps().stream().noneMatch(step -> step.action() == StepAction.DROP));
        assertNotEquals(new BlockPos(8, 62, 0), plan.plannedEnd());
        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
    }

    @Test
    void acceptsCoveredPassageWhenSamePlanProvesSurfaceExit() {
        TestTerrain terrain = TestTerrain.shortTunnelWithSurfaceExit();

        PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(24, 0), config());

        assertEquals(PathOutcome.REACHED_TARGET, plan.outcome());
        assertTrue(plan.steps().stream().anyMatch(step -> terrain.coveredDepth(step.pos()) > 3));
        assertEquals(0, terrain.coveredDepth(plan.plannedEnd()));
    }

    @Test
    void undergroundRecoveryMayInitiallyIncreaseTargetDistance() {
        TestTerrain terrain = TestTerrain.caveWithExitBehindStart();

        PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 48, 0)), routeTo(80, 0), config());

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertFalse(plan.steps().isEmpty());
        assertTrue(plan.steps().getFirst().pos().getX() < 0);
        assertTrue(terrain.coveredDepth(plan.plannedEnd()) < terrain.coveredDepth(plan.plannedStart()));
    }

    @Test
    void reportsUnloadedFrontierSeparately() {
        TestTerrain terrain = new TestTerrain().flatSurface(64).unloadBandX(12);

        PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(80, 0), config());

        assertEquals(PathOutcome.UNLOADED_FRONTIER, plan.outcome());
        assertFalse(plan.steps().isEmpty());
    }

    @Test
    void nodeLimitWithoutSafeProgressRemainsNonExecutable() {
        LocalPathPlanner budgetedPlanner = new LocalPathPlanner(1);
        TestTerrain terrain = new TestTerrain().flatSurface(64);

        PathPlan plan = budgetedPlanner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                config()
        );

        assertEquals(PathOutcome.NODE_LIMIT, plan.outcome());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    void nodeLimitDoesNotTriggerModificationSearch() {
        LocalPathPlanner budgetedPlanner = new LocalPathPlanner(1);
        TestTerrain terrain = new TestTerrain().flatSurface(64);
        terrain.solid(1, 65, 0);

        PathPlan plan = budgetedPlanner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                AutoNavigationConfig.aggressiveDefaults()
        );

        assertEquals(PathOutcome.NODE_LIMIT, plan.outcome());
        assertTrue(plan.steps().isEmpty());
        assertTrue(plan.steps().stream().noneMatch(step ->
                step.action() == StepAction.BREAK || step.action() == StepAction.PLACE));
        assertEquals(1, plan.expandedNodes());
    }

    @Test
    void nodeBudgetReturnsBestSafeProgress() {
        LocalPathPlanner budgetedPlanner = new LocalPathPlanner(4);
        TestTerrain terrain = new TestTerrain().flatSurface(64);

        PathPlan plan = budgetedPlanner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                config()
        );

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertFalse(plan.steps().isEmpty());
        assertEquals(new BlockPos(3, 65, 0), plan.plannedEnd());
    }

    @Test
    void cliffFrontierUsesBoundedProofBudget() {
        TestTerrain terrain = TestTerrain.threeBlockDropTowardTargetWithNoReturn();

        PathPlan plan = planner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                config()
        );

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertEquals(new BlockPos(4, 65, 0), plan.plannedEnd());
        assertFalse(containsMultiBlockDrop(plan));
        assertTrue(plan.expandedNodes() > 0);
        assertTrue(
                plan.expandedNodes() < 1_000,
                () -> "expandedNodes=" + plan.expandedNodes()
        );
    }

    @Test
    void safeDetourBeatsNearbyCliffFrontierWithinProofBudget() {
        TestTerrain terrain = TestTerrain.finiteThreeBlockTrench();

        PathPlan plan = planner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                config()
        );

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertTrue(plan.plannedEnd().getX() > 5);
        assertFalse(containsMultiBlockDrop(plan));
    }

    @Test
    void unsafeDebtFreeDeadEndDoesNotCancelCliffProof() {
        TestTerrain terrain = TestTerrain.threeBlockDropWithUnsafeOneBlockDeadEnd();
        assertEquals(4, terrain.coveredDepth(new BlockPos(7, 64, 1)));

        PathPlan plan = planner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                config()
        );

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertEquals(new BlockPos(4, 65, 0), plan.plannedEnd());
        assertFalse(containsMultiBlockDrop(plan));
        assertTrue(
                plan.expandedNodes() < 1_000,
                () -> "expandedNodes=" + plan.expandedNodes()
        );
    }

    @Test
    void reportsNoPathWhenStartHasNoReachableNeighbor() {
        LocalPathPlanner budgetedPlanner = new LocalPathPlanner(64);
        TestTerrain terrain = new TestTerrain().flatSurface(64).encloseFeet(new BlockPos(0, 65, 0));

        PathPlan plan = budgetedPlanner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                config()
        );

        assertEquals(PathOutcome.NO_PATH, plan.outcome());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    void partialFrontierMaximizesProgressAtEqualSafety() {
        TestTerrain terrain = new TestTerrain().isolatedSurface(
                new BlockPos(0, 65, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(0, 65, 1),
                new BlockPos(1, 65, 1),
                new BlockPos(2, 65, 1),
                new BlockPos(3, 65, 1)
        );

        PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(20, 0), config());

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertEquals(new BlockPos(3, 65, 1), plan.plannedEnd());
    }

    @Test
    void dropDebtDominanceKeepsRecoveredRoute() {
        BlockPos start = new BlockPos(0, 65, 0);
        TestTerrain terrain = new TestTerrain().isolatedSurface(
                start,
                new BlockPos(1, 65, 0),
                new BlockPos(-1, 65, 0),
                new BlockPos(-2, 65, 0),
                new BlockPos(-2, 65, 1),
                new BlockPos(-2, 65, 2),
                new BlockPos(-1, 64, 2),
                new BlockPos(0, 63, 2),
                new BlockPos(1, 62, 2),
                new BlockPos(2, 62, 2),
                new BlockPos(2, 62, 1),
                new BlockPos(2, 62, 0),
                new BlockPos(3, 62, 0),
                new BlockPos(4, 62, 0),
                new BlockPos(5, 62, 0),
                new BlockPos(6, 62, 0),
                new BlockPos(7, 62, 0),
                new BlockPos(8, 62, 0)
        ).unloadBandX(9);

        PathPlan plan = planner.plan(terrain.snapshot(start), routeTo(20, 0), config());

        assertEquals(PathOutcome.UNLOADED_FRONTIER, plan.outcome());
        assertEquals(new BlockPos(8, 62, 0), plan.plannedEnd());
        assertFalse(containsMultiBlockDrop(plan));
    }

    @Test
    void dropDebtCannotSatisfyReachedTarget() {
        TestTerrain terrain = TestTerrain.threeBlockDropTowardTargetWithNoReturn();

        PathPlan plan = planner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(8, 0),
                config()
        );

        assertNotEquals(PathOutcome.REACHED_TARGET, plan.outcome());
        assertFalse(containsMultiBlockDrop(plan));
    }

    private static boolean containsMultiBlockDrop(PathPlan plan) {
        int previousY = plan.plannedStart().getY();
        for (LocalPathPlanner.PathStep step : plan.steps()) {
            if (step.action() == StepAction.DROP && previousY - step.pos().getY() >= 2) {
                return true;
            }
            previousY = step.pos().getY();
        }
        return false;
    }

    private static AutoNavigationConfig config() {
        return AutoNavigationConfig.defaults();
    }

    private static RouteStep routeTo(int x, int z) {
        MapBounds bounds = new MapBounds(x, z, x, z);
        return new RouteStep(
                new WallPos(0, 0),
                new MapRegion("minecraft:overworld", 0, 0, 0, x, z, bounds),
                new BlockTarget(x, z),
                RouteStepState.OPENED
        );
    }

    private static final class TestTerrain {
        private static final int MIN_X = -32;
        private static final int MAX_X = 32;
        private static final int MIN_Z = -32;
        private static final int MAX_Z = 32;
        private static final int MIN_Y = 40;
        private static final int MAX_Y = 76;
        private static final Cell SOLID = new Cell(
                false,
                false,
                false,
                false,
                "minecraft:stone",
                true,
                true
        );

        private final Map<Long, Cell> cells = new HashMap<>();
        private final Set<Long> loadedColumns = new HashSet<>();
        private final Map<Long, Integer> surfaceHeights = new HashMap<>();

        private TestTerrain() {
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    loadedColumns.add(columnKey(x, z));
                }
            }
        }

        static TestTerrain threeBlockDropTowardTargetWithNoReturn() {
            TestTerrain terrain = new TestTerrain().flatSurface(64);
            for (int x = 5; x <= MAX_X; x++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    terrain.clear(x, 64, z);
                    terrain.solid(x, 61, z);
                    terrain.surfaceHeights.put(columnKey(x, z), 62);
                }
            }
            return terrain;
        }

        static TestTerrain finiteThreeBlockTrench() {
            TestTerrain terrain = new TestTerrain().flatSurface(64);
            for (int x = 5; x <= 9; x++) {
                for (int z = -1; z <= 1; z++) {
                    terrain.clear(x, 64, z);
                    terrain.solid(x, 61, z);
                    terrain.surfaceHeights.put(columnKey(x, z), 62);
                }
            }
            return terrain;
        }

        static TestTerrain threeBlockDropWithUnsafeOneBlockDeadEnd() {
            TestTerrain terrain = threeBlockDropTowardTargetWithNoReturn();
            for (int x = 5; x <= 7; x++) {
                terrain.solid(x, 63, 1);
                terrain.surfaceHeights.put(columnKey(x, 1), 68);
            }
            return terrain;
        }

        static TestTerrain shortTunnelWithSurfaceExit() {
            TestTerrain terrain = new TestTerrain().flatSurface(64).roof(2, 18, 69);
            for (int x = MIN_X; x <= 20; x++) {
                terrain.columnWall(x, -2, 64, 70);
                terrain.columnWall(x, 2, 64, 70);
            }
            return terrain;
        }

        static TestTerrain caveWithExitBehindStart() {
            TestTerrain terrain = new TestTerrain().flatSurface(64);
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    terrain.solid(x, 47, z);
                }
            }
            for (int step = 1; step <= 8; step++) {
                terrain.solid(-step, 47 + step, 0);
            }
            return terrain;
        }

        static TestTerrain twoCorridorContinuationDetour() {
            TestTerrain terrain = new TestTerrain().isolatedSurface(
                    new BlockPos(-1, 65, 0),
                    new BlockPos(0, 65, 0),
                    new BlockPos(1, 65, 0),
                    new BlockPos(2, 65, 0)
            );
            for (int z = 1; z <= 7; z++) {
                terrain.isolatedSurface(
                        new BlockPos(-1, 65, z),
                        new BlockPos(2, 65, z)
                );
            }
            return terrain;
        }

        TestTerrain flatSurface(int y) {
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    solid(x, y, z);
                    surfaceHeights.put(columnKey(x, z), y + 1);
                }
            }
            return this;
        }

        TestTerrain isolatedSurface(BlockPos... feetPositions) {
            for (BlockPos feet : feetPositions) {
                solid(feet.getX(), feet.getY() - 1, feet.getZ());
                surfaceHeights.put(columnKey(feet.getX(), feet.getZ()), feet.getY());
            }
            return this;
        }

        TestTerrain roof(int minX, int maxX, int y) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = -1; z <= 1; z++) {
                    solid(x, y, z);
                    surfaceHeights.put(columnKey(x, z), y + 1);
                }
            }
            return this;
        }

        TestTerrain wall(int x, int minZ, int maxZ, int floorY) {
            for (int z = minZ; z <= maxZ; z++) {
                columnWall(x, z, floorY, floorY + 5);
            }
            return this;
        }

        TestTerrain clear(int x, int y, int z) {
            cells.remove(new BlockPos(x, y, z).asLong());
            return this;
        }

        TestTerrain unloadBandX(int x) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                loadedColumns.remove(columnKey(x, z));
            }
            return this;
        }

        TestTerrain encloseFeet(BlockPos feet) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    solid(feet.getX() + dx, feet.getY(), feet.getZ() + dz);
                    solid(feet.getX() + dx, feet.getY() + 1, feet.getZ() + dz);
                }
            }
            return this;
        }

        NavigationSnapshot snapshot(BlockPos start) {
            return new NavigationSnapshot(
                    start,
                    MIN_X,
                    MAX_X,
                    MIN_Y,
                    MAX_Y,
                    MIN_Z,
                    MAX_Z,
                    MIN_Y,
                    MAX_Y,
                    Map.copyOf(cells),
                    Set.copyOf(loadedColumns),
                    true,
                    Map.copyOf(surfaceHeights)
            );
        }

        int coveredDepth(BlockPos pos) {
            Integer surface = surfaceHeights.get(columnKey(pos.getX(), pos.getZ()));
            return surface == null ? 0 : Math.max(0, surface - pos.getY());
        }

        private void columnWall(int x, int z, int minY, int maxY) {
            for (int y = minY + 1; y <= maxY; y++) {
                solid(x, y, z);
            }
            surfaceHeights.put(columnKey(x, z), maxY + 1);
        }

        private void solid(int x, int y, int z) {
            cells.put(new BlockPos(x, y, z).asLong(), SOLID);
        }

        private static long columnKey(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }
}

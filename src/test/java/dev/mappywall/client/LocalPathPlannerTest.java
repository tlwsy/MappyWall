package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.client.LocalPathPlanner.Cell;
import dev.mappywall.client.LocalPathPlanner.NavigationSnapshot;
import dev.mappywall.client.LocalPathPlanner.PathOutcome;
import dev.mappywall.client.LocalPathPlanner.PathPlan;
import dev.mappywall.client.LocalPathPlanner.StepAction;
import dev.mappywall.core.BlockTarget;
import dev.mappywall.core.MapBounds;
import dev.mappywall.core.MapRegion;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.WallPos;
import java.util.HashMap;
import java.util.HashSet;
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
    void reportsNodeLimitWhenBudgetExpiresBeforeAUsableFrontier() {
        LocalPathPlanner budgetedPlanner = new LocalPathPlanner(2);
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
    void stopsAtSafeCliffEdgeBeforeExhaustingNodeBudget() {
        LocalPathPlanner budgetedPlanner = new LocalPathPlanner(128);
        TestTerrain terrain = TestTerrain.threeBlockDropTowardTargetWithNoReturn();

        PathPlan plan = budgetedPlanner.plan(
                terrain.snapshot(new BlockPos(0, 65, 0)),
                routeTo(80, 0),
                config()
        );

        assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
        assertEquals(new BlockPos(4, 65, 0), plan.plannedEnd());
        assertTrue(plan.steps().stream().noneMatch(step -> step.action() == StepAction.DROP));
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
    void partialFrontierPrefersLowerCostBeforeTargetHeuristicAtEqualSurfaceRisk() {
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
        assertEquals(new BlockPos(1, 65, 0), plan.plannedEnd());
    }

    @Test
    void debtFreeDescentStateSurvivesCheaperThreeBlockDropToSamePosition() {
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

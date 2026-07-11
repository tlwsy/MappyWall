package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.MapBounds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class LocalPathPlanner {
    private static final int MAX_NODES = 4500;
    private static final int MAX_HORIZONTAL_RANGE = 28;
    private static final int MAX_VERTICAL_RANGE = 8;
    private static final int MAX_DROP = 3;
    private static final int MAX_BREAK_BLOCKS = 9;
    private static final int MAX_PLACE_BLOCKS = 16;
    private static final double BREAK_COST = 140.0;
    private static final double PLACE_COST = 96.0;
    private static final Set<String> DANGEROUS_BLOCKS = Set.of(
            "minecraft:cactus",
            "minecraft:magma_block",
            "minecraft:campfire",
            "minecraft:soul_campfire",
            "minecraft:fire",
            "minecraft:soul_fire",
            "minecraft:powder_snow",
            "minecraft:sweet_berry_bush",
            "minecraft:wither_rose"
    );
    private static final int REACHED_TARGET_RADIUS = 3;
    private static final int REGION_ENTRY_INSET_BLOCKS = 8;

    private static final int[][] DIRECTIONS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1},
            {1, 1},
            {1, -1},
            {-1, 1},
            {-1, -1}
    };

    public PathPlan plan(LocalPlayer player, RouteStep routeStep, AutoNavigationConfig config) {
        return plan(NavigationSnapshot.capture(player), routeStep, config);
    }

    public PathPlan plan(NavigationSnapshot snapshot, RouteStep routeStep, AutoNavigationConfig config) {
        AutoNavigationConfig nonModifying = new AutoNavigationConfig(
                false,
                config.breakListMode(),
                config.breakBlocks(),
                false,
                config.placeListMode(),
                config.placeBlocks(),
                config.eatingEnabled(),
                config.foodListMode(),
                config.foods(),
                config.eatAtFoodLevel()
        );
        PathPlan safePlan = search(snapshot, routeStep, nonModifying);
        if (safePlan.reachedTarget()
                || !safePlan.isEmpty()
                || (!config.blockBreakingEnabled() && !config.blockPlacingEnabled())) {
            return safePlan;
        }
        // Only consider modifying the world once an exhaustive local search cannot
        // make even one step of progress without doing so.
        return search(snapshot, routeStep, config);
    }

    private PathPlan search(NavigationSnapshot snapshot, RouteStep routeStep, AutoNavigationConfig config) {
        BlockPos start = stableFeetPos(snapshot, snapshot.start());
        BlockPos target = nearestRegionTarget(start, routeStep);
        SearchNode startNode = new SearchNode(start, null, StepAction.WALK, null, 0, 0, 0.0, heuristic(start, target));
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<SearchKey, Double> bestCost = new HashMap<>();
        open.add(startNode);
        bestCost.put(new SearchKey(start, 0, 0), 0.0);

        SearchNode best = startNode;
        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_NODES) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            SearchNode current = open.poll();
            if (current.heuristic < best.heuristic) {
                best = current;
            }
            if (reached(current.pos, routeStep, target)) {
                return new PathPlan(toSteps(current), current.pos, true);
            }

            for (SearchNode next : neighbors(snapshot, current, start, target, config)) {
                Double known = bestCost.get(new SearchKey(next.pos, next.breakCount, next.placeCount));
                if (known != null && known <= next.cost) {
                    continue;
                }
                bestCost.put(new SearchKey(next.pos, next.breakCount, next.placeCount), next.cost);
                open.add(next);
            }
        }

        List<PathStep> bestPath = toSteps(best);
        if (bestPath.isEmpty()) {
            Optional<PathStep> immediateBreak = immediateBreakStep(snapshot, start, target, config);
            if (immediateBreak.isPresent()) {
                return new PathPlan(List.of(immediateBreak.get()), start, false);
            }
        }
        return new PathPlan(bestPath, best.pos, false);
    }

    private List<PathStep> toSteps(SearchNode node) {
        ArrayList<PathStep> reversed = new ArrayList<>();
        SearchNode cursor = node;
        while (cursor != null && cursor.parent != null) {
            reversed.add(new PathStep(cursor.pos, cursor.action, cursor.actionBlock));
            cursor = cursor.parent;
        }

        ArrayList<PathStep> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return path;
    }

    private List<SearchNode> neighbors(
            NavigationSnapshot world,
            SearchNode current,
            BlockPos start,
            BlockPos target,
            AutoNavigationConfig config
    ) {
        ArrayList<SearchNode> result = new ArrayList<>(20);
        for (int[] direction : DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            if (!withinSearchRange(current.pos.offset(dx, 0, dz), start)) {
                continue;
            }
            if (Math.abs(dx) + Math.abs(dz) == 2 && clipsDiagonal(world, current.pos, dx, dz)) {
                continue;
            }
            boolean diagonal = Math.abs(dx) + Math.abs(dz) == 2;

            addMove(world, result, current, target, current.pos.offset(dx, 0, dz), StepAction.WALK, 1.0 + diagonalCost(dx, dz), config);
            if (!diagonal) {
                addMove(world, result, current, target, current.pos.offset(dx, 1, dz), StepAction.JUMP, 2.2, config);
                BlockPos dropEntry = current.pos.offset(dx, 0, dz);
                if (isPassable(world, dropEntry) && isPassable(world, dropEntry.above())) {
                    for (int drop = 1; drop <= MAX_DROP; drop++) {
                        BlockPos down = current.pos.offset(dx, -drop, dz);
                        if (down.getY() < world.getBottomY()) {
                            break;
                        }
                        if (standable(world, down) || swimmable(world, down)) {
                            addMove(world, result, current, target, down, StepAction.DROP, 1.5 + drop * drop, config);
                            break;
                        }
                        if (!isPassable(world, down) || !isPassable(world, down.above())) {
                            break;
                        }
                    }
                }
            }

            addBreakMove(world, result, current, target, current.pos.offset(dx, 0, dz), config, dx, dz);
        }

        boolean hasReasonableNonPlaceRoute = current.action != StepAction.PLACE
                && result.stream().anyMatch(next ->
                        next.action != StepAction.PLACE
                                && next.heuristic <= current.heuristic + 0.75
                                && (current.parent == null || !next.pos.equals(current.parent.pos)));
        if (!hasReasonableNonPlaceRoute) {
            for (int[] direction : DIRECTIONS) {
                if (Math.abs(direction[0]) + Math.abs(direction[1]) != 1) {
                    continue;
                }
                addPlaceMove(
                        world,
                        result,
                        current,
                        target,
                        current.pos.offset(direction[0], 0, direction[1]),
                        config
                );
            }
        }
        return result;
    }

    private void addMove(
            NavigationSnapshot world,
            List<SearchNode> result,
            SearchNode current,
            BlockPos target,
            BlockPos pos,
            StepAction action,
            double extraCost,
            AutoNavigationConfig config
    ) {
        if (!withinWorld(world, pos) || isDangerous(world, pos)) {
            return;
        }
        boolean swimming = swimmable(world, pos);
        if (!standable(world, pos) && !swimming) {
            return;
        }
        if (action == StepAction.JUMP && !headClearForJump(world, current.pos)) {
            return;
        }
        double cost = current.cost + extraCost + terrainCost(world, pos);
        StepAction plannedAction = swimming ? StepAction.SWIM : action;
        result.add(new SearchNode(
                pos,
                current,
                plannedAction,
                null,
                current.breakCount,
                current.placeCount,
                cost,
                heuristic(pos, target)
        ));
    }

    private void addBreakMove(
            NavigationSnapshot world,
            List<SearchNode> result,
            SearchNode current,
            BlockPos target,
            BlockPos pos,
            AutoNavigationConfig config,
            int dx,
            int dz
    ) {
        if (!config.blockBreakingEnabled() || !withinWorld(world, pos) || current.breakCount >= MAX_BREAK_BLOCKS) {
            return;
        }
        Optional<BlockPos> obstacle = firstObstacle(world, pos);
        if (obstacle.isEmpty()) {
            return;
        }
        BlockPos block = obstacle.get();
        Cell obstacleCell = world.cell(block);
        String blockId = obstacleCell.blockId();
        if (!obstacleCell.loaded() || obstacleCell.water() || obstacleCell.lava()
                || obstacleCell.passable() || "minecraft:air".equals(blockId)
                || !config.allowsBreak(blockId)) {
            return;
        }
        if (heuristic(pos, target) > current.heuristic + 1.0) {
            return;
        }

        BlockPos simulated = pos;
        if (!standableIfBroken(world, simulated, block) && !swimmable(world, simulated)) {
            return;
        }
        double cost = current.cost + BREAK_COST + diagonalCost(dx, dz) + heuristic(simulated, target) * 0.05;
        result.add(new SearchNode(
                simulated,
                current,
                StepAction.BREAK,
                block,
                current.breakCount + 1,
                current.placeCount,
                cost,
                heuristic(simulated, target)
        ));
    }

    private void addPlaceMove(
            NavigationSnapshot world,
            List<SearchNode> result,
            SearchNode current,
            BlockPos target,
            BlockPos pos,
            AutoNavigationConfig config
    ) {
        if (!config.blockPlacingEnabled()
                || current.placeCount >= MAX_PLACE_BLOCKS
                || !withinWorld(world, pos)
                || !isPassable(world, pos)
                || !isPassable(world, pos.above())) {
            return;
        }
        BlockPos support = pos.below();
        if (!isReplaceable(world, support)) {
            return;
        }
        if (world.cell(support).water() || world.cell(support).lava()) {
            return;
        }
        double cost = current.cost + PLACE_COST + terrainCost(world, pos);
        result.add(new SearchNode(
                pos,
                current,
                StepAction.PLACE,
                support,
                current.breakCount,
                current.placeCount + 1,
                cost,
                heuristic(pos, target)
        ));
    }

    private Optional<PathStep> immediateBreakStep(
            NavigationSnapshot world,
            BlockPos start,
            BlockPos target,
            AutoNavigationConfig config
    ) {
        PathStep best = null;
        double bestScore = Double.MAX_VALUE;
        for (int[] direction : DIRECTIONS) {
            BlockPos pos = start.offset(direction[0], 0, direction[1]);
            Optional<BlockPos> obstacle = firstObstacle(world, pos);
            if (obstacle.isEmpty()) {
                continue;
            }
            BlockPos block = obstacle.get();
            Cell obstacleCell = world.cell(block);
            String blockId = obstacleCell.blockId();
            if (!obstacleCell.loaded() || obstacleCell.water() || obstacleCell.lava()
                    || obstacleCell.passable() || "minecraft:air".equals(blockId)
                    || !config.allowsBreak(blockId)) {
                continue;
            }
            if (heuristic(pos, target) >= heuristic(start, target)) {
                continue;
            }
            double score = heuristic(pos, target) + diagonalCost(direction[0], direction[1]);
            if (score < bestScore) {
                bestScore = score;
                best = new PathStep(pos, StepAction.BREAK, block);
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean standable(NavigationSnapshot world, BlockPos feet) {
        return isPassable(world, feet)
                && isPassable(world, feet.above())
                && hasSupport(world, feet.below());
    }

    private boolean standableIfBroken(NavigationSnapshot world, BlockPos feet, BlockPos brokenBlock) {
        return isPassableIfBroken(world, feet, brokenBlock)
                && isPassableIfBroken(world, feet.above(), brokenBlock)
                && hasSupport(world, feet.below());
    }

    private boolean swimmable(NavigationSnapshot world, BlockPos feet) {
        return !isDangerous(world, feet)
                && world.cell(feet).water()
                && isPassable(world, feet.above());
    }

    private boolean isPassable(NavigationSnapshot world, BlockPos pos) {
        if (!withinWorld(world, pos) || !world.cell(pos).loaded() || world.cell(pos).lava()) {
            return false;
        }
        return world.cell(pos).passable();
    }

    private boolean isPassableIfBroken(NavigationSnapshot world, BlockPos pos, BlockPos brokenBlock) {
        return pos.equals(brokenBlock) || isPassable(world, pos);
    }

    private boolean hasSupport(NavigationSnapshot world, BlockPos pos) {
        if (!withinWorld(world, pos)) {
            return false;
        }
        if (!world.cell(pos).loaded()) {
            return false;
        }
        if (world.cell(pos.above()).water()) {
            return true;
        }
        return !world.cell(pos).passable() && !isUnsafeSupport(world.cell(pos).blockId());
    }

    private boolean isUnsafeSupport(String blockId) {
        return blockId.endsWith("_fence")
                || blockId.endsWith("_fence_gate")
                || blockId.endsWith("_wall")
                || blockId.endsWith("_pane")
                || blockId.equals("minecraft:iron_bars")
                || blockId.equals("minecraft:chain")
                || blockId.equals("minecraft:pointed_dripstone");
    }

    private boolean isReplaceable(NavigationSnapshot world, BlockPos pos) {
        return withinWorld(world, pos)
                && world.cell(pos).loaded()
                && !world.cell(pos).lava()
                && !world.cell(pos).water()
                && world.cell(pos).replaceable()
                && "minecraft:air".equals(world.cell(pos).blockId());
    }

    private boolean isDangerous(NavigationSnapshot world, BlockPos pos) {
        return !world.cell(pos).loaded()
                || !world.cell(pos.above()).loaded()
                || world.cell(pos).lava()
                || world.cell(pos.above()).lava()
                || isHazardCell(world.cell(pos))
                || isHazardCell(world.cell(pos.above()))
                || isHazardCell(world.cell(pos.below()));
    }

    private boolean isHazardCell(Cell cell) {
        return DANGEROUS_BLOCKS.contains(cell.blockId());
    }

    private Optional<BlockPos> firstObstacle(NavigationSnapshot world, BlockPos feet) {
        if (!isPassable(world, feet)) {
            return Optional.of(feet);
        }
        if (!isPassable(world, feet.above())) {
            return Optional.of(feet.above());
        }
        return Optional.empty();
    }

    private boolean headClearForJump(NavigationSnapshot world, BlockPos currentFeet) {
        return isPassable(world, currentFeet.above(2));
    }

    private boolean clipsDiagonal(NavigationSnapshot world, BlockPos current, int dx, int dz) {
        return !isPassable(world, current.offset(dx, 0, 0))
                || !isPassable(world, current.offset(0, 0, dz));
    }

    private double terrainCost(NavigationSnapshot world, BlockPos pos) {
        if (world.cell(pos).water()) {
            return 4.0;
        }
        String blockId = world.cell(pos).blockId();
        if (blockId.contains("vine") || blockId.equals("minecraft:weeping_vines") || blockId.equals("minecraft:twisting_vines")) {
            return 12.0;
        }
        return 0.0;
    }

    private boolean withinSearchRange(BlockPos pos, BlockPos start) {
        return Math.abs(pos.getX() - start.getX()) <= MAX_HORIZONTAL_RANGE
                && Math.abs(pos.getZ() - start.getZ()) <= MAX_HORIZONTAL_RANGE
                && Math.abs(pos.getY() - start.getY()) <= MAX_VERTICAL_RANGE;
    }

    private boolean withinWorld(NavigationSnapshot world, BlockPos pos) {
        return pos.getY() >= world.getBottomY() && pos.getY() <= world.getTopYInclusive();
    }

    private BlockPos stableFeetPos(NavigationSnapshot world, BlockPos pos) {
        BlockPos feet = pos;
        if (standable(world, feet) || swimmable(world, feet)) {
            return feet;
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos up = pos.above(dy);
            if (standable(world, up) || swimmable(world, up)) {
                return up;
            }
        }
        for (int dy = 1; dy <= MAX_DROP; dy++) {
            BlockPos down = pos.below(dy);
            if (standable(world, down) || swimmable(world, down)) {
                return down;
            }
        }
        return feet;
    }

    private boolean reached(BlockPos pos, RouteStep routeStep, BlockPos target) {
        return Mth.floor(Math.sqrt(pos.distSqr(target))) <= REACHED_TARGET_RADIUS;
    }

    private BlockPos nearestRegionTarget(BlockPos start, RouteStep routeStep) {
        if (routeStep.state() == RouteStepState.OPENED) {
            return new BlockPos(routeStep.targetBlock().x(), start.getY(), routeStep.targetBlock().z());
        }
        MapBounds bounds = routeStep.region().bounds();
        int targetX = interiorCoordinate(start.getX(), bounds.minX(), bounds.maxX());
        int targetZ = interiorCoordinate(start.getZ(), bounds.minZ(), bounds.maxZ());
        return new BlockPos(targetX, start.getY(), targetZ);
    }

    private int interiorCoordinate(int current, int min, int max) {
        if (max - min + 1 <= REGION_ENTRY_INSET_BLOCKS * 2) {
            return Mth.clamp(current, min, max);
        }
        return Mth.clamp(current, min + REGION_ENTRY_INSET_BLOCKS, max - REGION_ENTRY_INSET_BLOCKS);
    }

    private double heuristic(BlockPos pos, BlockPos target) {
        double dx = (double) pos.getX() - target.getX();
        double dz = (double) pos.getZ() - target.getZ();
        int dy = Math.abs(pos.getY() - target.getY());
        return Math.sqrt(dx * dx + dz * dz) + dy * 2.0;
    }

    private double diagonalCost(int dx, int dz) {
        return Math.abs(dx) + Math.abs(dz) == 2 ? 0.45 : 0.0;
    }

    private record SearchNode(
            BlockPos pos,
            SearchNode parent,
            StepAction action,
            BlockPos actionBlock,
            int breakCount,
            int placeCount,
            double cost,
            double heuristic
    ) {
        double score() {
            return cost + heuristic;
        }
    }

    private record SearchKey(BlockPos pos, int breakCount, int placeCount) {
    }

    public record PathPlan(List<PathStep> steps, BlockPos plannedEnd, boolean reachedTarget) {
        boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    public record PathStep(BlockPos pos, StepAction action, BlockPos actionBlock) {
    }

    public record NavigationSnapshot(
            BlockPos start,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            int bottomY,
            int topYInclusive,
            Map<Long, Cell> cells,
            Set<Long> loadedColumns
    ) {
        private static final Cell DEFAULT_AIR = new Cell(true, true, false, false, "minecraft:air", true);
        private static final Cell OUT_OF_RANGE = new Cell(false, false, false, false, "minecraft:bedrock", false);
        private static final Cell UNLOADED = new Cell(false, false, false, false, "minecraft:void_air", false);

        static NavigationSnapshot capture(LocalPlayer player) {
            Level world = player.level();
            BlockPos start = player.blockPosition();
            int minX = start.getX() - MAX_HORIZONTAL_RANGE;
            int maxX = start.getX() + MAX_HORIZONTAL_RANGE;
            int minZ = start.getZ() - MAX_HORIZONTAL_RANGE;
            int maxZ = start.getZ() + MAX_HORIZONTAL_RANGE;
            int minY = Math.max(world.getMinY(), start.getY() - MAX_VERTICAL_RANGE - MAX_DROP - 2);
            int maxY = Math.min(world.getMaxY() - 1, start.getY() + MAX_VERTICAL_RANGE + 2);
            Map<Long, Cell> cells = new HashMap<>();
            Set<Long> loadedColumns = new HashSet<>();
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, start.getY(), z);
                    if (!world.hasChunkAt(mutable)) {
                        continue;
                    }
                    loadedColumns.add(columnKey(x, z));
                    for (int y = minY; y <= maxY; y++) {
                        mutable.set(x, y, z);
                        BlockState state = world.getBlockState(mutable);
                        boolean water = world.getFluidState(mutable).is(FluidTags.WATER);
                        boolean lava = world.getFluidState(mutable).is(FluidTags.LAVA);
                        boolean passable = state.getCollisionShape(world, mutable).isEmpty();
                        boolean replaceable = state.canBeReplaced();
                        String blockId = state.isAir()
                                ? "minecraft:air"
                                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (state.isAir() && !water && !lava) {
                            continue;
                        }
                        cells.put(mutable.asLong(), new Cell(passable, replaceable, water, lava, blockId, true));
                    }
                }
            }

            return new NavigationSnapshot(
                    start,
                    minX,
                    maxX,
                    minY,
                    maxY,
                    minZ,
                    maxZ,
                    world.getMinY(),
                    world.getMaxY() - 1,
                    Collections.unmodifiableMap(cells),
                    Collections.unmodifiableSet(loadedColumns)
            );
        }

        Cell cell(BlockPos pos) {
            if (pos.getX() < minX
                    || pos.getX() > maxX
                    || pos.getY() < minY
                    || pos.getY() > maxY
                    || pos.getZ() < minZ
                    || pos.getZ() > maxZ) {
                return OUT_OF_RANGE;
            }
            if (!loadedColumns.contains(columnKey(pos.getX(), pos.getZ()))) {
                return UNLOADED;
            }
            return cells.getOrDefault(pos.asLong(), DEFAULT_AIR);
        }

        boolean isLoaded(BlockPos pos) {
            return cell(pos).loaded();
        }

        private static long columnKey(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        int getBottomY() {
            return bottomY;
        }

        int getTopYInclusive() {
            return topYInclusive;
        }
    }

    private record Cell(
            boolean passable,
            boolean replaceable,
            boolean water,
            boolean lava,
            String blockId,
            boolean loaded
    ) {
    }

    public enum StepAction {
        WALK,
        JUMP,
        DROP,
        SWIM,
        BREAK,
        PLACE
    }
}

package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

public final class LocalPathPlanner {
    private static final int MAX_NODES = 3000;
    private static final int MAX_HORIZONTAL_RANGE = 36;
    private static final int MAX_VERTICAL_RANGE = 10;
    private static final int MAX_DROP = 5;
    private static final int REACHED_TARGET_RADIUS = 3;

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

    public PathPlan plan(ClientPlayerEntity player, RouteStep routeStep, AutoNavigationConfig config) {
        return plan(NavigationSnapshot.capture(player), routeStep, config);
    }

    public PathPlan plan(NavigationSnapshot snapshot, RouteStep routeStep, AutoNavigationConfig config) {
        BlockPos start = stableFeetPos(snapshot, snapshot.start());
        BlockPos target = nearestRegionTarget(start, routeStep);
        SearchNode startNode = new SearchNode(start, null, StepAction.WALK, null, 0.0, heuristic(start, target));
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<BlockPos, Double> bestCost = new HashMap<>();
        open.add(startNode);
        bestCost.put(start, 0.0);

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
                Double known = bestCost.get(next.pos);
                if (known != null && known <= next.cost) {
                    continue;
                }
                bestCost.put(next.pos, next.cost);
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
        ArrayList<SearchNode> result = new ArrayList<>(16);
        for (int[] direction : DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            if (!withinSearchRange(current.pos.add(dx, 0, dz), start)) {
                continue;
            }
            if (Math.abs(dx) + Math.abs(dz) == 2 && clipsDiagonal(world, current.pos, dx, dz)) {
                continue;
            }

            addMove(world, result, current, target, current.pos.add(dx, 0, dz), StepAction.WALK, 1.0 + diagonalCost(dx, dz), config);
            addMove(world, result, current, target, current.pos.add(dx, 1, dz), StepAction.JUMP, 2.2 + diagonalCost(dx, dz), config);
            for (int drop = 1; drop <= MAX_DROP; drop++) {
                BlockPos down = current.pos.add(dx, -drop, dz);
                if (down.getY() < world.getBottomY()) {
                    break;
                }
                if (standable(world, down) || swimmable(world, down)) {
                    addMove(world, result, current, target, down, StepAction.DROP, 1.5 + drop + diagonalCost(dx, dz), config);
                    break;
                }
                if (!isPassable(world, down)) {
                    break;
                }
            }

            addBreakMove(world, result, current, target, current.pos.add(dx, 0, dz), config, dx, dz);
            addPlaceMove(world, result, current, target, current.pos.add(dx, 0, dz), config);
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
        result.add(new SearchNode(pos, current, plannedAction, null, cost, heuristic(pos, target)));
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
        if (!config.blockBreakingEnabled() || !withinWorld(world, pos)) {
            return;
        }
        Optional<BlockPos> obstacle = firstObstacle(world, pos);
        if (obstacle.isEmpty()) {
            return;
        }
        BlockPos block = obstacle.get();
        String blockId = world.cell(block).blockId();
        if (!config.allowsBreak(blockId)) {
            return;
        }

        BlockPos simulated = pos;
        if (!standableIfBroken(world, simulated, block) && !swimmable(world, simulated)) {
            return;
        }
        double cost = current.cost + 18.0 + diagonalCost(dx, dz) + heuristic(simulated, target) * 0.02;
        result.add(new SearchNode(simulated, current, StepAction.BREAK, block, cost, heuristic(simulated, target)));
    }

    private void addPlaceMove(
            NavigationSnapshot world,
            List<SearchNode> result,
            SearchNode current,
            BlockPos target,
            BlockPos pos,
            AutoNavigationConfig config
    ) {
        if (!config.blockPlacingEnabled() || !withinWorld(world, pos) || !isPassable(world, pos) || !isPassable(world, pos.up())) {
            return;
        }
        BlockPos support = pos.down();
        if (!isReplaceable(world, support)) {
            return;
        }
        double cost = current.cost + 12.0 + terrainCost(world, pos);
        result.add(new SearchNode(pos, current, StepAction.PLACE, support, cost, heuristic(pos, target)));
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
            BlockPos pos = start.add(direction[0], 0, direction[1]);
            Optional<BlockPos> obstacle = firstObstacle(world, pos);
            if (obstacle.isEmpty()) {
                continue;
            }
            BlockPos block = obstacle.get();
            String blockId = world.cell(block).blockId();
            if (!config.allowsBreak(blockId)) {
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
                && isPassable(world, feet.up())
                && hasSupport(world, feet.down());
    }

    private boolean standableIfBroken(NavigationSnapshot world, BlockPos feet, BlockPos brokenBlock) {
        return isPassableIfBroken(world, feet, brokenBlock)
                && isPassableIfBroken(world, feet.up(), brokenBlock)
                && hasSupport(world, feet.down());
    }

    private boolean swimmable(NavigationSnapshot world, BlockPos feet) {
        return !isDangerous(world, feet)
                && world.cell(feet).water()
                && isPassable(world, feet.up());
    }

    private boolean isPassable(NavigationSnapshot world, BlockPos pos) {
        if (!withinWorld(world, pos) || world.cell(pos).lava()) {
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
        if (world.cell(pos.up()).water()) {
            return true;
        }
        return !world.cell(pos).passable();
    }

    private boolean isReplaceable(NavigationSnapshot world, BlockPos pos) {
        return withinWorld(world, pos)
                && !world.cell(pos).lava()
                && world.cell(pos).replaceable();
    }

    private boolean isDangerous(NavigationSnapshot world, BlockPos pos) {
        return world.cell(pos).lava()
                || world.cell(pos.up()).lava();
    }

    private Optional<BlockPos> firstObstacle(NavigationSnapshot world, BlockPos feet) {
        if (!isPassable(world, feet)) {
            return Optional.of(feet);
        }
        if (!isPassable(world, feet.up())) {
            return Optional.of(feet.up());
        }
        return Optional.empty();
    }

    private boolean headClearForJump(NavigationSnapshot world, BlockPos currentFeet) {
        return isPassable(world, currentFeet.up(2));
    }

    private boolean clipsDiagonal(NavigationSnapshot world, BlockPos current, int dx, int dz) {
        return !isPassable(world, current.add(dx, 0, 0))
                || !isPassable(world, current.add(0, 0, dz));
    }

    private double terrainCost(NavigationSnapshot world, BlockPos pos) {
        if (world.cell(pos).water()) {
            return 4.0;
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
            BlockPos up = pos.up(dy);
            if (standable(world, up) || swimmable(world, up)) {
                return up;
            }
        }
        for (int dy = 1; dy <= MAX_DROP; dy++) {
            BlockPos down = pos.down(dy);
            if (standable(world, down) || swimmable(world, down)) {
                return down;
            }
        }
        return feet;
    }

    private boolean reached(BlockPos pos, RouteStep routeStep, BlockPos target) {
        if (routeStep.region().bounds().contains(pos.getX(), pos.getZ())) {
            return true;
        }
        return MathHelper.floor(Math.sqrt(pos.getSquaredDistance(target))) <= REACHED_TARGET_RADIUS;
    }

    private BlockPos nearestRegionTarget(BlockPos start, RouteStep routeStep) {
        int targetX = MathHelper.clamp(start.getX(), routeStep.region().bounds().minX(), routeStep.region().bounds().maxX());
        int targetZ = MathHelper.clamp(start.getZ(), routeStep.region().bounds().minZ(), routeStep.region().bounds().maxZ());
        return new BlockPos(targetX, start.getY(), targetZ);
    }

    private double heuristic(BlockPos pos, BlockPos target) {
        int dx = pos.getX() - target.getX();
        int dz = pos.getZ() - target.getZ();
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
            double cost,
            double heuristic
    ) {
        double score() {
            return cost + heuristic;
        }
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
            Map<Long, Cell> cells
    ) {
        private static final Cell DEFAULT_AIR = new Cell(true, true, false, false, "minecraft:air");
        private static final Cell OUT_OF_RANGE = new Cell(false, false, false, false, "minecraft:bedrock");

        static NavigationSnapshot capture(ClientPlayerEntity player) {
            World world = player.getEntityWorld();
            BlockPos start = player.getBlockPos();
            int minX = start.getX() - MAX_HORIZONTAL_RANGE;
            int maxX = start.getX() + MAX_HORIZONTAL_RANGE;
            int minZ = start.getZ() - MAX_HORIZONTAL_RANGE;
            int maxZ = start.getZ() + MAX_HORIZONTAL_RANGE;
            int minY = Math.max(world.getBottomY(), start.getY() - MAX_VERTICAL_RANGE - MAX_DROP - 2);
            int maxY = Math.min(world.getTopYInclusive(), start.getY() + MAX_VERTICAL_RANGE + 2);
            Map<Long, Cell> cells = new HashMap<>();
            BlockPos.Mutable mutable = new BlockPos.Mutable();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        mutable.set(x, y, z);
                        BlockState state = world.getBlockState(mutable);
                        boolean water = world.getFluidState(mutable).isIn(FluidTags.WATER);
                        boolean lava = world.getFluidState(mutable).isIn(FluidTags.LAVA);
                        boolean passable = state.getCollisionShape(world, mutable).isEmpty();
                        boolean replaceable = state.isReplaceable();
                        if (passable && replaceable && !water && !lava) {
                            continue;
                        }

                        String blockId = passable ? "minecraft:air" : Registries.BLOCK.getId(state.getBlock()).toString();
                        cells.put(mutable.asLong(), new Cell(passable, replaceable, water, lava, blockId));
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
                    world.getBottomY(),
                    world.getTopYInclusive(),
                    Map.copyOf(cells)
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
            return cells.getOrDefault(pos.asLong(), DEFAULT_AIR);
        }

        int getBottomY() {
            return bottomY;
        }

        int getTopYInclusive() {
            return topYInclusive;
        }
    }

    private record Cell(boolean passable, boolean replaceable, boolean water, boolean lava, String blockId) {
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

package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.MapBounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

public final class LocalPathPlanner {
    private static final int MAX_NODES = 4500;
    static final int MAX_HORIZONTAL_RANGE = 28;
    static final int MAX_VERTICAL_RANGE = 8;
    static final int MAX_DROP = 3;
    private static final int MAX_BREAK_BLOCKS = 9;
    private static final int MAX_PLACE_BLOCKS = 16;
    private static final int SHALLOW_COVERED_DEPTH = 3;
    private static final int NO_DROP_DEBT = Integer.MIN_VALUE;
    private static final double BREAK_COST = 140.0;
    private static final double PLACE_COST = 96.0;
    private static final double DROP_DEBT_COST = 64.0;
    private static final double COVERED_STEP_COST = 4.0;
    private static final int CLIFF_PROOF_NODE_BUDGET = 384;
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

    private final int maxNodes;

    public LocalPathPlanner() {
        this(MAX_NODES);
    }

    LocalPathPlanner(int maxNodes) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive");
        }
        this.maxNodes = maxNodes;
    }

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
        if (safePlan.outcome() != PathOutcome.NO_PATH
                || (!config.blockBreakingEnabled() && !config.blockPlacingEnabled())) {
            return safePlan;
        }
        // Only consider modifying the world once an exhaustive local search cannot
        // make even one step of progress without doing so.
        PathPlan modifyingPlan = search(snapshot, routeStep, config);
        return new PathPlan(
                modifyingPlan.plannedStart(),
                modifyingPlan.steps(),
                modifyingPlan.plannedEnd(),
                modifyingPlan.outcome(),
                safePlan.expandedNodes() + modifyingPlan.expandedNodes()
        );
    }

    private PathPlan search(NavigationSnapshot snapshot, RouteStep routeStep, AutoNavigationConfig config) {
        BlockPos start = stableFeetPos(snapshot, snapshot.start());
        BlockPos target = nearestRegionTarget(snapshot, start, routeStep);
        int startCoveredDepth = snapshot.coveredDepth(start);
        boolean surfaceStart = !snapshot.surfaceAware() || snapshot.surfaceLike(start);
        SearchNode startNode = new SearchNode(
                start,
                null,
                StepAction.WALK,
                null,
                0,
                0,
                0.0,
                heuristic(start, target),
                coveredExposure(startCoveredDepth),
                startCoveredDepth,
                NO_DROP_DEBT
        );
        PriorityQueue<SearchNode> open = new PriorityQueue<>(searchOrder());
        Map<BaseSearchKey, List<DebtState>> paretoStates = new HashMap<>();
        open.add(startNode);
        registerParetoState(paretoStates, startNode);

        SearchNode bestSafe = null;
        SearchNode bestUnloaded = null;
        SearchNode cliffCandidate = null;
        int cliffProofExpansions = 0;
        boolean cliffProofResolved = false;
        int expandedNodes = 0;
        while (!open.isEmpty() && expandedNodes < maxNodes) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            SearchNode current = open.poll();
            if (!isCurrentParetoState(paretoStates, current)) {
                continue;
            }
            expandedNodes++;
            boolean safeCandidate = isSafePartialCandidate(
                    snapshot,
                    current,
                    startNode,
                    surfaceStart,
                    startCoveredDepth
            );
            if (safeCandidate
                    && betterPartial(snapshot, current, bestSafe, surfaceStart)) {
                bestSafe = current;
            }
            if (safeCandidate
                    && touchesUnloadedFrontier(snapshot, current.pos, target)
                    && betterPartial(snapshot, current, bestUnloaded, surfaceStart)) {
                bestUnloaded = current;
            }
            if (current.dropRecoveryY == NO_DROP_DEBT
                    && reached(snapshot, current.pos, routeStep, target)) {
                return new PathPlan(
                        start,
                        toSteps(current),
                        current.pos,
                        PathOutcome.REACHED_TARGET,
                        expandedNodes
                );
            }
            if (safeCandidate) {
                if (touchesUnloadedFrontier(snapshot, current.pos, target)) {
                    return new PathPlan(
                            start,
                            toSteps(current),
                            current.pos,
                            PathOutcome.UNLOADED_FRONTIER,
                            expandedNodes
                    );
                }
                if (touchesLocalSearchSeam(current.pos, start)) {
                    return new PathPlan(
                            start,
                            toSteps(current),
                            current.pos,
                            PathOutcome.SAFE_FRONTIER,
                            expandedNodes
                    );
                }
            }

            if (cliffCandidate != null) {
                if (safeCandidate
                        && current.dropRecoveryY == NO_DROP_DEBT
                        && current.heuristic + 1.0e-6 < cliffCandidate.heuristic) {
                    cliffCandidate = null;
                    cliffProofResolved = true;
                } else {
                    cliffProofExpansions++;
                }
            }

            List<SearchNode> nextNodes = neighbors(snapshot, current, start, target, config);
            if (!cliffProofResolved
                    && cliffCandidate == null
                    && safeCandidate
                    && onlyTargetImprovingContinuationAddsDropDebt(current, nextNodes, target)) {
                cliffCandidate = current;
                cliffProofExpansions = 0;
            }
            for (SearchNode next : nextNodes) {
                if (registerParetoState(paretoStates, next)) {
                    open.add(next);
                }
            }
            if (cliffCandidate != null && cliffProofExpansions >= CLIFF_PROOF_NODE_BUDGET) {
                return new PathPlan(
                        start,
                        toSteps(bestSafe),
                        bestSafe.pos,
                        PathOutcome.SAFE_FRONTIER,
                        expandedNodes
                );
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            return new PathPlan(start, List.of(), start, PathOutcome.NODE_LIMIT, expandedNodes);
        }
        if (!open.isEmpty() && expandedNodes >= maxNodes) {
            if (bestUnloaded != null) {
                return new PathPlan(
                        start,
                        toSteps(bestUnloaded),
                        bestUnloaded.pos,
                        PathOutcome.UNLOADED_FRONTIER,
                        expandedNodes
                );
            }
            if (bestSafe != null) {
                return new PathPlan(
                        start,
                        toSteps(bestSafe),
                        bestSafe.pos,
                        PathOutcome.SAFE_FRONTIER,
                        expandedNodes
                );
            }
            return new PathPlan(start, List.of(), start, PathOutcome.NODE_LIMIT, expandedNodes);
        }
        if (bestUnloaded != null) {
            return new PathPlan(
                    start,
                    toSteps(bestUnloaded),
                    bestUnloaded.pos,
                    PathOutcome.UNLOADED_FRONTIER,
                    expandedNodes
            );
        }
        if (bestSafe != null) {
            return new PathPlan(
                    start,
                    toSteps(bestSafe),
                    bestSafe.pos,
                    PathOutcome.SAFE_FRONTIER,
                    expandedNodes
            );
        }
        Optional<PathStep> immediateBreak = immediateBreakStep(snapshot, start, target, config);
        if (immediateBreak.isPresent()) {
            return new PathPlan(
                    start,
                    List.of(immediateBreak.get()),
                    start,
                    PathOutcome.SAFE_FRONTIER,
                    expandedNodes
            );
        }
        return new PathPlan(start, List.of(), start, PathOutcome.NO_PATH, expandedNodes);
    }

    private Comparator<SearchNode> searchOrder() {
        return Comparator.<SearchNode>comparingDouble(SearchNode::score)
                .thenComparingDouble(SearchNode::heuristic)
                .thenComparingDouble(SearchNode::cost)
                .thenComparingInt(node -> node.pos.getX())
                .thenComparingInt(node -> node.pos.getY())
                .thenComparingInt(node -> node.pos.getZ())
                .thenComparingInt(SearchNode::breakCount)
                .thenComparingInt(SearchNode::placeCount)
                .thenComparingInt(SearchNode::dropRecoveryY)
                .thenComparingInt(node -> node.action.ordinal());
    }

    private boolean registerParetoState(
            Map<BaseSearchKey, List<DebtState>> paretoStates,
            SearchNode node
    ) {
        BaseSearchKey key = new BaseSearchKey(node.pos, node.breakCount, node.placeCount);
        List<DebtState> states = paretoStates.computeIfAbsent(key, ignored -> new ArrayList<>());
        DebtState candidate = new DebtState(node.dropRecoveryY, node.cost);
        for (DebtState state : states) {
            if (sameDebtState(state, candidate) || dominates(state, candidate)) {
                return false;
            }
        }
        states.removeIf(state -> dominates(candidate, state));
        states.add(candidate);
        return true;
    }

    private boolean isCurrentParetoState(
            Map<BaseSearchKey, List<DebtState>> paretoStates,
            SearchNode node
    ) {
        List<DebtState> states = paretoStates.get(
                new BaseSearchKey(node.pos, node.breakCount, node.placeCount)
        );
        if (states == null) {
            return false;
        }
        DebtState candidate = new DebtState(node.dropRecoveryY, node.cost);
        return states.stream().anyMatch(state -> sameDebtState(state, candidate));
    }

    private boolean dominates(DebtState candidate, DebtState other) {
        if (candidate.recoveryY == NO_DROP_DEBT) {
            return candidate.cost <= other.cost;
        }
        if (other.recoveryY == NO_DROP_DEBT) {
            return false;
        }
        return candidate.recoveryY <= other.recoveryY
                && candidate.cost <= other.cost
                && (candidate.recoveryY < other.recoveryY || candidate.cost < other.cost);
    }

    private boolean sameDebtState(DebtState first, DebtState second) {
        return first.recoveryY == second.recoveryY
                && Double.compare(first.cost, second.cost) == 0;
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
                                && next.action != StepAction.BREAK
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
        result.add(nextNode(
                world,
                pos,
                current,
                plannedAction,
                null,
                current.breakCount,
                current.placeCount,
                cost,
                target
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
                || obstacleCell.passable() || !obstacleCell.breakable() || "minecraft:air".equals(blockId)
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
        result.add(nextNode(
                world,
                simulated,
                current,
                StepAction.BREAK,
                block,
                current.breakCount + 1,
                current.placeCount,
                cost,
                target
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
        result.add(nextNode(
                world,
                pos,
                current,
                StepAction.PLACE,
                support,
                current.breakCount,
                current.placeCount + 1,
                cost,
                target
        ));
    }

    private SearchNode nextNode(
            NavigationSnapshot world,
            BlockPos pos,
            SearchNode current,
            StepAction action,
            BlockPos actionBlock,
            int breakCount,
            int placeCount,
            double baseCost,
            BlockPos target
    ) {
        int coveredDepth = world.coveredDepth(pos);
        int exposure = coveredExposure(coveredDepth);
        int dropRecoveryY = nextDropRecoveryY(current, pos, action);
        double dropDebtCost = current.dropRecoveryY == NO_DROP_DEBT
                && dropRecoveryY != NO_DROP_DEBT
                ? DROP_DEBT_COST
                : 0.0;
        return new SearchNode(
                pos,
                current,
                action,
                actionBlock,
                breakCount,
                placeCount,
                baseCost + exposure * COVERED_STEP_COST + dropDebtCost,
                heuristic(pos, target),
                current.undergroundExposure + exposure,
                Math.max(current.maxCoveredDepth, coveredDepth),
                dropRecoveryY
        );
    }

    private int nextDropRecoveryY(SearchNode current, BlockPos pos, StepAction action) {
        int recoveryY = current.dropRecoveryY;
        if (action == StepAction.DROP && current.pos.getY() - pos.getY() >= 2) {
            recoveryY = recoveryY == NO_DROP_DEBT
                    ? current.pos.getY()
                    : Math.max(recoveryY, current.pos.getY());
        }
        if (recoveryY != NO_DROP_DEBT && pos.getY() >= recoveryY) {
            return NO_DROP_DEBT;
        }
        return recoveryY;
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
            Optional<BlockPos> obstacle = immediateBreakObstacle(world, pos, config);
            if (obstacle.isEmpty()) {
                continue;
            }
            BlockPos block = obstacle.get();
            Cell obstacleCell = world.cell(block);
            String blockId = obstacleCell.blockId();
            if (!obstacleCell.loaded() || obstacleCell.water() || obstacleCell.lava()
                    || obstacleCell.passable() || !obstacleCell.breakable() || "minecraft:air".equals(blockId)
                    || !config.allowsBreak(blockId)) {
                continue;
            }
            boolean stagedHeadBreak = block.equals(pos.above())
                    && !isPassable(world, pos)
                    && isAllowedBreakCell(world.cell(pos), config)
                    && hasSupport(world, pos.below());
            if (!standableIfBroken(world, pos, block)
                    && !swimmable(world, pos)
                    && !stagedHeadBreak) {
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

    private Optional<BlockPos> immediateBreakObstacle(
            NavigationSnapshot world,
            BlockPos feet,
            AutoNavigationConfig config
    ) {
        Cell feetCell = world.cell(feet);
        Cell headCell = world.cell(feet.above());
        if (!feetCell.passable()
                && !headCell.passable()
                && isAllowedBreakCell(feetCell, config)
                && isAllowedBreakCell(headCell, config)) {
            // Open headroom first. Once acknowledged, live validation deliberately
            // refuses entry and replans; the next plan can then break the foot block.
            return Optional.of(feet.above());
        }
        return firstObstacle(world, feet);
    }

    private boolean isAllowedBreakCell(Cell cell, AutoNavigationConfig config) {
        return cell.loaded()
                && cell.breakable()
                && !cell.passable()
                && !cell.water()
                && !cell.lava()
                && config.allowsBreak(cell.blockId());
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
        return !world.cell(pos).passable()
                && !isUnsafeSupport(world.cell(pos).blockId())
                && !isHazardCell(world.cell(pos));
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
        return !safeDiagonalSide(world, current.offset(dx, 0, 0))
                || !safeDiagonalSide(world, current.offset(0, 0, dz));
    }

    private boolean safeDiagonalSide(NavigationSnapshot world, BlockPos feet) {
        return (isPassable(world, feet) && isPassable(world, feet.above()))
                && (standable(world, feet) || swimmable(world, feet));
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

    private boolean isSafePartialCandidate(
            NavigationSnapshot world,
            SearchNode candidate,
            SearchNode start,
            boolean surfaceStart,
            int startCoveredDepth
    ) {
        if (candidate.parent == null || candidate.dropRecoveryY != NO_DROP_DEBT) {
            return false;
        }
        if (surfaceStart) {
            return world.surfaceLike(candidate.pos) && candidate.heuristic + 1.0e-6 < start.heuristic;
        }
        return world.coveredDepth(candidate.pos) < startCoveredDepth;
    }

    private boolean betterPartial(
            NavigationSnapshot world,
            SearchNode candidate,
            SearchNode incumbent,
            boolean surfaceStart
    ) {
        if (incumbent == null) {
            return true;
        }
        if (surfaceStart) {
            int risk = Integer.compare(candidate.maxCoveredDepth, incumbent.maxCoveredDepth);
            if (risk != 0) {
                return risk < 0;
            }
            int exposure = Integer.compare(candidate.undergroundExposure, incumbent.undergroundExposure);
            if (exposure != 0) {
                return exposure < 0;
            }
        } else {
            int recovery = Integer.compare(
                    world.coveredDepth(candidate.pos),
                    world.coveredDepth(incumbent.pos)
            );
            if (recovery != 0) {
                return recovery < 0;
            }
        }
        int progress = Double.compare(candidate.heuristic, incumbent.heuristic);
        if (progress != 0) {
            return progress < 0;
        }
        int cost = Double.compare(candidate.cost, incumbent.cost);
        if (cost != 0) {
            return cost < 0;
        }
        int x = Integer.compare(candidate.pos.getX(), incumbent.pos.getX());
        if (x != 0) {
            return x < 0;
        }
        int y = Integer.compare(candidate.pos.getY(), incumbent.pos.getY());
        if (y != 0) {
            return y < 0;
        }
        return candidate.pos.getZ() < incumbent.pos.getZ();
    }

    private boolean touchesUnloadedFrontier(NavigationSnapshot world, BlockPos pos, BlockPos target) {
        double currentHeuristic = heuristic(pos, target);
        for (int[] direction : DIRECTIONS) {
            int x = pos.getX() + direction[0];
            int z = pos.getZ() + direction[1];
            if (!world.containsColumn(x, z) || world.isColumnLoaded(x, z)) {
                continue;
            }
            BlockPos neighbor = new BlockPos(x, pos.getY(), z);
            if (heuristic(neighbor, target) < currentHeuristic) {
                return true;
            }
        }
        return false;
    }

    private boolean touchesLocalSearchSeam(BlockPos pos, BlockPos start) {
        return Math.abs(pos.getX() - start.getX()) == MAX_HORIZONTAL_RANGE
                || Math.abs(pos.getZ() - start.getZ()) == MAX_HORIZONTAL_RANGE
                || Math.abs(pos.getY() - start.getY()) == MAX_VERTICAL_RANGE;
    }

    private boolean onlyTargetImprovingContinuationAddsDropDebt(
            SearchNode current,
            List<SearchNode> continuations,
            BlockPos target
    ) {
        double currentDistance = horizontalHeuristic(current.pos, target);
        boolean foundImprovingContinuation = false;
        for (SearchNode continuation : continuations) {
            if (horizontalHeuristic(continuation.pos, target) + 1.0e-6 >= currentDistance) {
                continue;
            }
            foundImprovingContinuation = true;
            if (continuation.action != StepAction.DROP
                    || current.pos.getY() - continuation.pos.getY() < 2
                    || continuation.dropRecoveryY == NO_DROP_DEBT) {
                return false;
            }
        }
        return foundImprovingContinuation;
    }

    private int coveredExposure(int coveredDepth) {
        return Math.max(0, coveredDepth - SHALLOW_COVERED_DEPTH);
    }

    private boolean reached(NavigationSnapshot world, BlockPos pos, RouteStep routeStep, BlockPos target) {
        return Mth.floor(Math.sqrt(pos.distSqr(target))) <= REACHED_TARGET_RADIUS
                && (!world.surfaceAware() || world.surfaceLike(pos));
    }

    private BlockPos nearestRegionTarget(NavigationSnapshot world, BlockPos start, RouteStep routeStep) {
        int targetX;
        int targetZ;
        if (routeStep.state() == RouteStepState.OPENED) {
            targetX = routeStep.targetBlock().x();
            targetZ = routeStep.targetBlock().z();
        } else {
            MapBounds bounds = routeStep.region().bounds();
            targetX = interiorCoordinate(start.getX(), bounds.minX(), bounds.maxX());
            targetZ = interiorCoordinate(start.getZ(), bounds.minZ(), bounds.maxZ());
        }
        int targetY = world.hasSurfaceHeight(targetX, targetZ)
                ? world.surfaceHeight(targetX, targetZ)
                : start.getY();
        return new BlockPos(targetX, targetY, targetZ);
    }

    private int interiorCoordinate(int current, int min, int max) {
        if (max - min + 1 <= REGION_ENTRY_INSET_BLOCKS * 2) {
            return Mth.clamp(current, min, max);
        }
        return Mth.clamp(current, min + REGION_ENTRY_INSET_BLOCKS, max - REGION_ENTRY_INSET_BLOCKS);
    }

    private double heuristic(BlockPos pos, BlockPos target) {
        double horizontal = horizontalHeuristic(pos, target);
        int dy = Math.abs(pos.getY() - target.getY());
        return horizontal + dy * 2.0;
    }

    private double horizontalHeuristic(BlockPos pos, BlockPos target) {
        double dx = (double) pos.getX() - target.getX();
        double dz = (double) pos.getZ() - target.getZ();
        return Math.sqrt(dx * dx + dz * dz);
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
            double heuristic,
            int undergroundExposure,
            int maxCoveredDepth,
            int dropRecoveryY
    ) {
        double score() {
            return cost + heuristic;
        }
    }

    private record BaseSearchKey(BlockPos pos, int breakCount, int placeCount) {
    }

    private record DebtState(int recoveryY, double cost) {
    }

    public record PathPlan(
            BlockPos plannedStart,
            List<PathStep> steps,
            BlockPos plannedEnd,
            PathOutcome outcome,
            int expandedNodes
    ) {
        public PathPlan {
            steps = List.copyOf(steps);
            if (expandedNodes < 0) {
                throw new IllegalArgumentException("expandedNodes must be non-negative");
            }
        }

        public PathPlan(
                BlockPos plannedStart,
                List<PathStep> steps,
                BlockPos plannedEnd,
                PathOutcome outcome
        ) {
            this(plannedStart, steps, plannedEnd, outcome, 0);
        }

        boolean isEmpty() {
            return steps.isEmpty();
        }

        boolean isExecutable() {
            return outcome == PathOutcome.REACHED_TARGET
                    || (!steps.isEmpty()
                    && (outcome == PathOutcome.SAFE_FRONTIER || outcome == PathOutcome.UNLOADED_FRONTIER));
        }

        public boolean reachedTarget() {
            return outcome == PathOutcome.REACHED_TARGET;
        }
    }

    public enum PathOutcome {
        REACHED_TARGET,
        SAFE_FRONTIER,
        UNLOADED_FRONTIER,
        NODE_LIMIT,
        NO_PATH
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
            Set<Long> loadedColumns,
            boolean surfaceAware,
            Map<Long, Integer> surfaceHeights
    ) {
        private static final Cell DEFAULT_AIR = new Cell(true, true, false, false, "minecraft:air", true, false);
        private static final Cell OUT_OF_RANGE = new Cell(false, false, false, false, "minecraft:bedrock", false, false);
        private static final Cell UNLOADED = new Cell(false, false, false, false, "minecraft:void_air", false, false);

        public NavigationSnapshot {
            cells = Map.copyOf(cells);
            loadedColumns = Set.copyOf(loadedColumns);
            surfaceHeights = Map.copyOf(surfaceHeights);
        }

        static NavigationSnapshot capture(LocalPlayer player) {
            NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                    player.level(),
                    player.blockPosition()
            );
            capture.advance(Integer.MAX_VALUE);
            return capture.finish();
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

        boolean containsColumn(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean isColumnLoaded(int x, int z) {
            return containsColumn(x, z) && loadedColumns.contains(columnKey(x, z));
        }

        boolean hasSurfaceHeight(int x, int z) {
            return surfaceAware && surfaceHeights.containsKey(columnKey(x, z));
        }

        int surfaceHeight(int x, int z) {
            Integer height = surfaceHeights.get(columnKey(x, z));
            if (height == null) {
                throw new IllegalArgumentException("No surface height for column " + x + "," + z);
            }
            return height;
        }

        int coveredDepth(BlockPos pos) {
            if (!hasSurfaceHeight(pos.getX(), pos.getZ())) {
                return 0;
            }
            return Math.max(0, surfaceHeight(pos.getX(), pos.getZ()) - pos.getY());
        }

        boolean surfaceLike(BlockPos pos) {
            return !surfaceAware || coveredDepth(pos) <= SHALLOW_COVERED_DEPTH;
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

    record Cell(
            boolean passable,
            boolean replaceable,
            boolean water,
            boolean lava,
            String blockId,
            boolean loaded,
            boolean breakable
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

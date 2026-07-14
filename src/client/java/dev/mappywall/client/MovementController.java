package dev.mappywall.client;

import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.PathSegmentCoordinator;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.RunMode;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public final class MovementController {
    private static final double ARRIVAL_DISTANCE_BLOCKS = 4.0;
    private static final double WALK_WAYPOINT_DISTANCE_BLOCKS = 0.42;
    private static final double JUMP_WAYPOINT_DISTANCE_BLOCKS = 0.52;
    private static final double DROP_WAYPOINT_DISTANCE_BLOCKS = 0.58;
    // Keep this below half a block. Advancing a fluid waypoint while the player
    // is still in the previous block makes the following grid step appear two
    // blocks away and causes an unnecessary stop/replan cycle for both swimmers
    // and boats.
    private static final double SWIM_WAYPOINT_DISTANCE_BLOCKS = 0.42;
    private static final double BOAT_PLACE_REACH_BLOCKS = 4.75;
    private static final int REGION_ENTRY_INSET_BLOCKS = 8;
    private static final float MOVE_ALIGNMENT_DEGREES = 75.0F;
    private static final float SPRINT_ALIGNMENT_DEGREES = 35.0F;
    private static final float NORMAL_INTERACT_ALIGNMENT_DEGREES = 12.0F;
    private static final float MOVEMENT_TURN_DEGREES = 12.0F;
    private static final double AGGRESSIVE_ELYTRA_CRUISE_SPEED = 1.18;
    private static final double AGGRESSIVE_ELYTRA_APPROACH_SPEED = 0.72;
    private static final double AGGRESSIVE_ELYTRA_CLIMB_SPEED = 0.56;
    private static final double AGGRESSIVE_ELYTRA_MAX_Y_SPEED = 0.72;
    private static final double AGGRESSIVE_GROUND_SPEED = 0.285;
    private static final double AGGRESSIVE_SWIM_SPEED = 0.18;
    private static final double AGGRESSIVE_SNEAK_SPEED = 0.12;
    private static final double AGGRESSIVE_JUMP_VELOCITY = 0.42;
    private static final double BOAT_DRIVE_SPEED = 0.36;
    private static final int ELYTRA_LAUNCH_VERTICAL_CLEARANCE = 14;
    private static final int ELYTRA_LAUNCH_CORRIDOR_DISTANCE = 28;
    private static final int ELYTRA_CLIMB_OBSTACLE_SCAN = 34;
    private static final double ELYTRA_NORMAL_CLIMB_ANGLE = 24.0;
    private static final double ELYTRA_STEEP_CLIMB_ANGLE = 48.0;
    private static final double STUCK_EPSILON = 0.06;
    private static final double PLAYER_MOVE_EPSILON = 0.015;
    private static final int STUCK_TICKS_LIMIT = 90;
    private static final int COLLISION_REPLAN_TICKS = 8;
    private static final int MAX_MOVEMENT_RECOVERY_FAILURES = 12;
    private static final int LOCAL_STALL_TICKS = 40;
    private static final int LOOP_STALL_TICKS = 100;
    private static final double LOCAL_STALL_AREA_BLOCKS = 1.0;
    private static final double LOOP_STALL_AREA_BLOCKS = 4.0;
    private static final int REPLAN_INTERVAL_TICKS = 50;
    private static final int PLACE_COOLDOWN_NORMAL_TICKS = 4;
    private static final int PLACE_COOLDOWN_AGGRESSIVE_TICKS = 1;
    private static final int PLACE_CONFIRM_TIMEOUT_TICKS = 30;
    private static final int BREAK_TIMEOUT_TICKS = 140;
    private static final int MAX_ACTION_FAILURES = 3;
    private static final double MAX_PLAN_START_DRIFT_SQR = 2.0;
    private static final int LOOKAHEAD_REMAINING_STEPS = 14;
    private static final int SNAPSHOT_COLUMNS_PER_TICK = 192;
    private static final int LOOKAHEAD_VALIDATION_STEPS = 3;
    private static final int MAX_BREAK_ACTIONS_PER_TARGET = 9;
    private static final int BOAT_COOLDOWN_TICKS = 40;
    private static final int EAT_COOLDOWN_TICKS = 20;
    private static final int ELYTRA_START_COOLDOWN_TICKS = 20;
    private static final int ELYTRA_FIREWORK_NORMAL_COOLDOWN_TICKS = 70;
    private static final int ELYTRA_FIREWORK_AGGRESSIVE_COOLDOWN_TICKS = 48;
    private static final double ELYTRA_FIREWORK_DISTANCE = 48.0;
    private static final double ELYTRA_LOW_SPEED = 0.55;
    private static final double ELYTRA_CRUISE_ALTITUDE = 192.0;
    private static final double ELYTRA_CLIMB_MARGIN = 8.0;
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
    private static final ExecutorService PATH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MappyWall Path Planner");
        thread.setDaemon(true);
        return thread;
    });

    private final AutoNavigationConfig normalConfig = AutoNavigationConfig.defaults();
    private volatile AutoNavigationConfig aggressiveConfig;
    private final LocalPathPlanner pathPlanner = new LocalPathPlanner();
    private final PathSegmentCoordinator<LocalPathPlanner.PathStep> pathSegments =
            new PathSegmentCoordinator<>();
    private final ArrayDeque<MovementSample> movementSamples = new ArrayDeque<>();

    private long currentStepOrdinal;
    private int replanCooldown;
    private int stuckTicks;
    private int horizontalCollisionTicks;
    private int failedReplans;
    private int placeCooldown;
    private int boatCooldown;
    private int eatCooldown;
    private int elytraStartCooldown;
    private int fireworkCooldown;
    private int dismountCooldown;
    private double lastDistance = Double.MAX_VALUE;
    private double lastWaypointDistance = Double.MAX_VALUE;
    private Vec3 lastPlayerPos = Vec3.ZERO;
    private String targetSignature;
    private BlockPos breakingBlock;
    private String breakBudgetTargetSignature;
    private int breakActionsForTarget;
    private NavigationSnapshotCapture pendingCapture;
    private Future<LocalPathPlanner.PathPlan> pendingPlan;
    private PlanningRequest pendingPlanningRequest;
    private long planGeneration;
    private AutomationStyle automationStyle = AutomationStyle.NORMAL;
    private boolean movementKeysHeld;
    private boolean directSprintHeld;
    private boolean useKeyHeld;
    private boolean waitingForChunk;
    private DirectInput lastDirectInput = DirectInput.NEUTRAL;
    private BoatInput lastBoatInput = BoatInput.NEUTRAL;
    private String activeStepSignature;
    private int activeStepTicks;
    private int actionFailures;
    private int movementRecoveryFailures;
    private BlockPos pendingPlacementBlock;
    private int pendingPlacementTicks;
    private boolean actionAcknowledged;
    private boolean dropCommitted;
    private boolean eatingSession;
    private int movementSampleTick;

    public MovementController() {
        this(AutoNavigationConfig.aggressiveDefaults());
    }

    MovementController(AutoNavigationConfig aggressiveConfig) {
        this.aggressiveConfig = Objects.requireNonNull(aggressiveConfig, "aggressiveConfig");
    }

    public void setAggressiveConfig(AutoNavigationConfig aggressiveConfig) {
        this.aggressiveConfig = Objects.requireNonNull(aggressiveConfig, "aggressiveConfig");
        forceLocalReplan();
    }

    public MovementResult tick(Minecraft client, MapWallSave save, RouteStep target) {
        if (client.level == null || client.player == null || target == null) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        automationStyle = save.project().automationStyle();
        waitingForChunk = false;
        resetBreakBudgetIfTargetChanged(target);
        if (save.project().mode() == RunMode.AUTO_ELYTRA) {
            return tickElytra(client, save, target);
        }

        if (save.project().mode() != RunMode.AUTO_WALK) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        LocalPlayer player = client.player;
        handleNavigationTargetChange(target);
        if (arrivedAtNavigationTarget(player, target)) {
            if (player.isPassenger() && tryDismountVehicle(client, player)) {
                return MovementResult.active(pathSnapshot());
            }
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        tickCooldowns();

        if (movementRecoveryFailures >= MAX_MOVEMENT_RECOVERY_FAILURES) {
            release(client);
            resetProgress();
            return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_stuck"));
        }

        advancePendingCapture();
        acceptCompletedPlan(client, player, target);
        startEligibleLookahead(client, target);
        startInitialCaptureIfNeeded(client, target);

        if (tryEat(client, player)) {
            return MovementResult.active(pathSnapshot());
        }

        LocalPathPlanner.PathStep waypoint = nextWaypoint(player);
        if (waypoint == null) {
            stopMovement(client);
            if (isUnloadedAhead(client, player, target)) {
                waitingForChunk = true;
                failedReplans = 0;
                return MovementResult.waiting(pathSnapshot());
            }
            if (failedReplans >= MAX_ACTION_FAILURES) {
                release(client);
                resetProgress();
                return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_no_path"));
            }
            return MovementResult.active(pathSnapshot());
        }

        if (!isStepChunkReady(client, waypoint)) {
            waitingForChunk = true;
            stopMovement(client);
            return MovementResult.waiting(pathSnapshot());
        }
        if ((waypoint.action() == LocalPathPlanner.StepAction.BREAK
                        || waypoint.action() == LocalPathPlanner.StepAction.PLACE)
                && !isAdjacentActionStep(player, waypoint)) {
            actionFailures++;
            forceLocalReplan();
            stopMovement(client);
            if (actionFailures >= MAX_ACTION_FAILURES) {
                release(client);
                resetProgress();
                return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_stuck"));
            }
            return MovementResult.active(pathSnapshot());
        }
        if (isMovementAction(waypoint.action()) && !isMovementStepSafe(client, player, waypoint)) {
            movementRecoveryFailures++;
            forceLocalReplan();
            stopMovement(client);
            if (movementRecoveryFailures >= MAX_MOVEMENT_RECOVERY_FAILURES) {
                release(client);
                resetProgress();
                return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_stuck"));
            }
            return MovementResult.active(pathSnapshot());
        }

        if (player.isPassenger() && waypoint.action() != LocalPathPlanner.StepAction.SWIM
                && tryDismountVehicle(client, player)) {
            return MovementResult.active(pathSnapshot());
        }

        if (client.gui.screen() != null
                && (waypoint.action() == LocalPathPlanner.StepAction.BREAK
                        || waypoint.action() == LocalPathPlanner.StepAction.PLACE)) {
            // Aggressive mode may keep travelling with a screen open, but world and
            // inventory transactions wait until the player closes it.
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }

        failedReplans = 0;
        MovementResult actionResult = executeStep(client, player, waypoint);
        updateProgress(player, target, waypoint);
        return actionResult;
    }

    public void release(Minecraft client) {
        releaseMovementKeys(client);
        releaseDirectMovementState(client);
        releaseUseKey(client);
        releaseVehicleControls(client);
        breakingBlock = null;
        pendingPlacementBlock = null;
        pendingPlacementTicks = 0;
        actionAcknowledged = false;
        dropCommitted = false;
        eatingSession = false;
        waitingForChunk = false;
        movementSamples.clear();
        cancelPendingPlan();
        pathSegments.clear();
        currentStepOrdinal = 0;
        replanCooldown = 0;
    }

    public void hardReset(Minecraft client) {
        boolean neutralWasOnlyCached = lastDirectInput.equals(DirectInput.NEUTRAL);
        boolean boatNeutralWasOnlyCached = lastBoatInput.equals(BoatInput.NEUTRAL);
        release(client);
        if (neutralWasOnlyCached) {
            sendNeutralInput(client, true);
        }
        if (boatNeutralWasOnlyCached
                && client.player != null
                && client.player.getVehicle() instanceof AbstractBoat) {
            sendBoatPaddles(client, false, false, true);
        }
        resetProgress();
    }

    public boolean isWaitingForChunk() {
        return waitingForChunk;
    }

    public boolean isPlanningPath() {
        return pendingCapture != null || pendingPlan != null;
    }

    private AutoNavigationConfig navigationConfig() {
        return currentAutomationStyle() == AutomationStyle.AGGRESSIVE ? aggressiveConfig : normalConfig;
    }

    private boolean isStepChunkReady(Minecraft client, LocalPathPlanner.PathStep step) {
        if (client.level == null) {
            return false;
        }
        if (!client.level.hasChunkAt(step.pos())
                || !client.level.hasChunkAt(step.pos().above())
                || !client.level.hasChunkAt(step.pos().below())) {
            return false;
        }
        return step.actionBlock() == null || client.level.hasChunkAt(step.actionBlock());
    }

    private boolean isAdjacentActionStep(LocalPlayer player, LocalPathPlanner.PathStep step) {
        BlockPos current = player.blockPosition();
        return Math.abs(step.pos().getX() - current.getX()) <= 1
                && Math.abs(step.pos().getZ() - current.getZ()) <= 1
                && step.pos().getY() == current.getY();
    }

    private boolean isUnloadedAhead(Minecraft client, LocalPlayer player, RouteStep target) {
        if (client.level == null) {
            return true;
        }
        BlockPos destination = navigationTarget(player, target);
        double dx = destination.getX() + 0.5 - player.getX();
        double dz = destination.getZ() + 0.5 - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.001) {
            return false;
        }
        double probeDistance = Math.min(6.0, distance);
        BlockPos probe = BlockPos.containing(
                player.getX() + dx / distance * probeDistance,
                player.getY(),
                player.getZ() + dz / distance * probeDistance
        );
        return !client.level.hasChunkAt(probe);
    }

    private boolean isMovementStepSafe(
            Minecraft client,
            LocalPlayer player,
            LocalPathPlanner.PathStep step
    ) {
        if (client.level == null) {
            return false;
        }
        BlockPos feet = step.pos();
        BlockPos head = feet.above();
        BlockPos support = feet.below();
        BlockPos currentFeet = player.blockPosition();
        int edgeX = feet.getX() - currentFeet.getX();
        int edgeZ = feet.getZ() - currentFeet.getZ();
        if (Math.abs(edgeX) > 1 || Math.abs(edgeZ) > 1) {
            return false;
        }
        if (edgeX != 0 && edgeZ != 0
                && (!isLiveDiagonalSideSafe(client, currentFeet.offset(edgeX, 0, 0))
                        || !isLiveDiagonalSideSafe(client, currentFeet.offset(0, 0, edgeZ)))) {
            return false;
        }
        if (!client.level.getFluidState(feet).isEmpty()
                && !client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER)) {
            return false;
        }
        if (client.level.getFluidState(head).is(net.minecraft.tags.FluidTags.LAVA)
                || isDangerousLiveBlock(client, feet)
                || isDangerousLiveBlock(client, head)
                || isDangerousLiveBlock(client, support)) {
            return false;
        }
        if (!client.level.getBlockState(feet).getCollisionShape(client.level, feet).isEmpty()
                || !client.level.getBlockState(head).getCollisionShape(client.level, head).isEmpty()) {
            return false;
        }
        if (step.action() == LocalPathPlanner.StepAction.SWIM) {
            return client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER);
        }
        if (!isSafeSolidSupport(client, support)) {
            return false;
        }
        int verticalDelta = feet.getY() - player.blockPosition().getY();
        return switch (step.action()) {
            case JUMP -> verticalDelta >= 0
                    && verticalDelta <= 1
                    // Before take-off the current column needs two clear blocks
                    // through the upward transition. Once the entity's feet are
                    // already at target Y, the target body clearance checked
                    // above is sufficient; requiring another block would reject
                    // valid two-block-high passages in mid-jump.
                    && (verticalDelta == 0 || isLiveBodyClear(client, currentFeet.above()));
            case DROP -> verticalDelta >= -3
                    && verticalDelta <= 0
                    && isLiveDropShaftClear(client, currentFeet, feet);
            case WALK -> verticalDelta == 0;
            case SWIM -> true;
            case BREAK, PLACE -> false;
        };
    }

    private boolean isLookaheadContinuationSafe(
            Minecraft client,
            PathSegmentCoordinator.LookaheadRequest request,
            LocalPathPlanner.PathPlan plan
    ) {
        if (client.level == null || request == null || plan.steps().stream().anyMatch(this::isModifyingStep)) {
            return false;
        }

        BlockPos cursor = blockPos(request.seam());
        if (!isLoadedSafePosition(client, cursor)) {
            return false;
        }
        int validationCount = Math.min(LOOKAHEAD_VALIDATION_STEPS, plan.steps().size());
        for (int index = 0; index < validationCount; index++) {
            LocalPathPlanner.PathStep step = plan.steps().get(index);
            if (!isLookaheadTransitionSafe(client, cursor, step)) {
                return false;
            }
            cursor = step.pos();
        }
        return true;
    }

    private boolean isLookaheadTransitionSafe(
            Minecraft client,
            BlockPos from,
            LocalPathPlanner.PathStep step
    ) {
        if (client.level == null || isModifyingStep(step)) {
            return false;
        }
        BlockPos feet = step.pos();
        BlockPos head = feet.above();
        BlockPos support = feet.below();
        if (!areLiveBlocksLoaded(client, feet, head, support)
                || (step.actionBlock() != null && !client.level.hasChunkAt(step.actionBlock()))) {
            return false;
        }

        int edgeX = feet.getX() - from.getX();
        int edgeZ = feet.getZ() - from.getZ();
        if (Math.max(Math.abs(edgeX), Math.abs(edgeZ)) != 1) {
            return false;
        }
        if (edgeX != 0 && edgeZ != 0
                && (!isLookaheadDiagonalSideSafe(client, from.offset(edgeX, 0, 0))
                        || !isLookaheadDiagonalSideSafe(client, from.offset(0, 0, edgeZ)))) {
            return false;
        }
        if (!isLoadedSafePosition(client, feet)) {
            return false;
        }

        int verticalDelta = feet.getY() - from.getY();
        boolean cardinal = Math.abs(edgeX) + Math.abs(edgeZ) == 1;
        return switch (step.action()) {
            case WALK -> verticalDelta == 0;
            case JUMP -> cardinal
                    && verticalDelta >= 0
                    && verticalDelta <= 1
                    && (verticalDelta == 0 || isLiveBodyClear(client, from.above()));
            case DROP -> cardinal
                    && verticalDelta >= -3
                    && verticalDelta <= 0
                    && isLiveDropShaftClear(client, from, feet);
            case SWIM -> (verticalDelta == 0 || cardinal)
                    && verticalDelta >= -3
                    && verticalDelta <= 1
                    && client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER);
            case BREAK, PLACE -> false;
        };
    }

    private boolean isLoadedSafePosition(Minecraft client, BlockPos feet) {
        if (client.level == null
                || !areLiveBlocksLoaded(client, feet, feet.above(), feet.below())
                || !isLiveBodyClear(client, feet)
                || isDangerousLiveBlock(client, feet)
                || isDangerousLiveBlock(client, feet.above())
                || isDangerousLiveBlock(client, feet.below())) {
            return false;
        }
        if ((!client.level.getFluidState(feet).isEmpty()
                        && !client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER))
                || (!client.level.getFluidState(feet.above()).isEmpty()
                        && !client.level.getFluidState(feet.above()).is(net.minecraft.tags.FluidTags.WATER))) {
            return false;
        }
        return client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER)
                || isSafeSolidSupport(client, feet.below());
    }

    private boolean isLookaheadDiagonalSideSafe(Minecraft client, BlockPos feet) {
        return areLiveBlocksLoaded(client, feet, feet.above(), feet.below())
                && isLiveDiagonalSideSafe(client, feet)
                && !isDangerousLiveBlock(client, feet)
                && !isDangerousLiveBlock(client, feet.above())
                && !isDangerousLiveBlock(client, feet.below())
                && (client.level.getFluidState(feet).isEmpty()
                        || client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER))
                && (client.level.getFluidState(feet.above()).isEmpty()
                        || client.level.getFluidState(feet.above()).is(net.minecraft.tags.FluidTags.WATER));
    }

    private boolean areLiveBlocksLoaded(Minecraft client, BlockPos... positions) {
        if (client.level == null) {
            return false;
        }
        for (BlockPos position : positions) {
            if (!client.level.hasChunkAt(position)) {
                return false;
            }
        }
        return true;
    }

    private boolean isLiveBodyClear(Minecraft client, BlockPos feet) {
        if (client.level == null || !client.level.hasChunkAt(feet)) {
            return false;
        }
        return client.level.getBlockState(feet).getCollisionShape(client.level, feet).isEmpty()
                && client.level.getBlockState(feet.above()).getCollisionShape(client.level, feet.above()).isEmpty()
                && !client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.LAVA)
                && !client.level.getFluidState(feet.above()).is(net.minecraft.tags.FluidTags.LAVA);
    }

    private boolean isLiveDiagonalSideSafe(Minecraft client, BlockPos feet) {
        return isLiveBodyClear(client, feet)
                && (client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER)
                        || isSafeSolidSupport(client, feet.below()));
    }

    private boolean isLiveDropShaftClear(Minecraft client, BlockPos currentFeet, BlockPos targetFeet) {
        if (client.level == null) {
            return false;
        }
        int topY = Math.max(currentFeet.getY(), targetFeet.getY());
        for (int y = targetFeet.getY(); y <= topY; y++) {
            BlockPos shaftFeet = new BlockPos(targetFeet.getX(), y, targetFeet.getZ());
            if (!isLiveBodyClear(client, shaftFeet)
                    || isDangerousLiveBlock(client, shaftFeet)
                    || isDangerousLiveBlock(client, shaftFeet.above())) {
                return false;
            }
        }
        return true;
    }

    private boolean isDangerousLiveBlock(Minecraft client, BlockPos pos) {
        if (client.level == null) {
            return true;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
        return DANGEROUS_BLOCKS.contains(blockId)
                || client.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA);
    }

    private boolean isSafeSolidSupport(Minecraft client, BlockPos pos) {
        if (!isSolid(client, pos) || client.level == null) {
            return false;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
        return !blockId.endsWith("_fence")
                && !blockId.endsWith("_fence_gate")
                && !blockId.endsWith("_wall")
                && !blockId.endsWith("_pane")
                && !blockId.equals("minecraft:iron_bars")
                && !blockId.equals("minecraft:chain")
                && !blockId.equals("minecraft:pointed_dripstone");
    }

    private boolean trackStep(LocalPathPlanner.PathStep step) {
        String signature = currentStepOrdinal + ":" + step.action() + ":" + step.pos().asLong()
                + ":" + (step.actionBlock() == null ? "-" : step.actionBlock().asLong());
        if (!signature.equals(activeStepSignature)) {
            activeStepSignature = signature;
            activeStepTicks = 0;
            breakingBlock = null;
            pendingPlacementBlock = null;
            pendingPlacementTicks = 0;
            actionAcknowledged = false;
            dropCommitted = false;
        }
        activeStepTicks++;
        if (step.action() == LocalPathPlanner.StepAction.BREAK && breakingBlock != null) {
            return activeStepTicks <= BREAK_TIMEOUT_TICKS;
        }
        if (step.action() == LocalPathPlanner.StepAction.PLACE && !actionAcknowledged) {
            return true;
        }
        return activeStepTicks <= STUCK_TICKS_LIMIT * 2;
    }

    public List<BlockPos> pathSnapshot() {
        return pathSegments.remainingStepSnapshot().stream()
                .map(LocalPathPlanner.PathStep::pos)
                .toList();
    }

    private MovementResult executeStep(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        if (!trackStep(waypoint)) {
            boolean movementAction = isMovementAction(waypoint.action());
            if (movementAction) {
                movementRecoveryFailures++;
            } else {
                actionFailures++;
            }
            forceLocalReplan();
            stopMovement(client);
            if ((movementAction && movementRecoveryFailures >= MAX_MOVEMENT_RECOVERY_FAILURES)
                    || (!movementAction && actionFailures >= MAX_ACTION_FAILURES)) {
                release(client);
                resetProgress();
                return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_stuck"));
            }
            return MovementResult.active(pathSnapshot());
        }
        return switch (waypoint.action()) {
            case BREAK -> {
                if (breakActionsForTarget >= MAX_BREAK_ACTIONS_PER_TARGET) {
                    release(client);
                    resetProgress();
                    yield MovementResult.pause(Component.translatable("message.mappywall.auto_walk_too_many_breaks"));
                }
                yield breakBlock(client, player, waypoint);
            }
            case PLACE -> placeBlock(client, player, waypoint);
            case SWIM -> swimOrBoat(client, player, waypoint);
            case JUMP -> moveToward(client, player, waypoint, true);
            case DROP -> dropToward(client, player, waypoint);
            case WALK -> moveToward(client, player, waypoint, false);
        };
    }

    private MovementResult dropToward(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        double dx = waypoint.pos().getX() + 0.5 - player.getX();
        double dz = waypoint.pos().getZ() + 0.5 - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (!dropCommitted) {
            if (!player.onGround() || horizontalDistance <= 0.72) {
                dropCommitted = true;
            } else {
                return moveToward(client, player, waypoint, false, true, false);
            }
        }
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE && !player.onGround()) {
            applyAggressiveGroundVelocity(client, player, dx, dz, false, false, false);
            Vec3 velocity = player.getDeltaMovement();
            double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            if (horizontalSpeed > 0.12) {
                double scale = 0.12 / horizontalSpeed;
                player.setDeltaMovement(velocity.x * scale, velocity.y, velocity.z * scale);
            }
            return MovementResult.active(pathSnapshot());
        }
        return moveToward(client, player, waypoint, false, false, false);
    }

    private MovementResult moveToward(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint, boolean jump) {
        return moveToward(client, player, waypoint, jump, false, true);
    }

    private MovementResult moveToward(
            Minecraft client,
            LocalPlayer player,
            LocalPathPlanner.PathStep waypoint,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        BlockPos pos = waypoint.pos();
        double targetX = pos.getX() + 0.5;
        double targetZ = pos.getZ() + 0.5;
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            applyAggressiveGroundVelocity(
                    client,
                    player,
                    targetX - player.getX(),
                    targetZ - player.getZ(),
                    jump,
                    sneak,
                    sprint
            );
            return MovementResult.active(pathSnapshot());
        }
        float yawError = faceMovement(player, targetX - player.getX(), targetZ - player.getZ());
        boolean aligned = yawError <= MOVE_ALIGNMENT_DEGREES;
        boolean sprinting = sprint && yawError <= SPRINT_ALIGNMENT_DEGREES;
        setMovementKeys(client, aligned, false, false, false, aligned && jump, sneak, sprinting);
        return MovementResult.active(pathSnapshot());
    }

    private MovementResult tickElytra(Minecraft client, MapWallSave save, RouteStep target) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return MovementResult.none();
        }
        if (arrivedAtNavigationTarget(player, target)) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        tickCooldowns();
        BlockPos navigationTarget = navigationTarget(player, target);
        if (!hasEquippedElytra(player)) {
            release(client);
            resetProgress();
            return MovementResult.pause(Component.translatable("message.mappywall.auto_elytra_no_elytra"));
        }

        if (!player.isFallFlying()) {
            return startOrPrepareElytra(client, player, navigationTarget, save.project().automationStyle());
        }

        return flyElytraToward(client, player, navigationTarget, save.project().automationStyle());
    }

    private MovementResult startOrPrepareElytra(
            Minecraft client,
            LocalPlayer player,
            BlockPos navigationTarget,
            AutomationStyle style
    ) {
        if (!hasElytraLaunchSpace(client, player, navigationTarget)) {
            release(client);
            resetProgress();
            return MovementResult.pause(Component.translatable("message.mappywall.auto_elytra_no_launch_space"));
        }

        if (style == AutomationStyle.AGGRESSIVE) {
            if (player.onGround()) {
                applyAggressiveGroundVelocity(
                        client,
                        player,
                        navigationTarget.getX() + 0.5 - player.getX(),
                        navigationTarget.getZ() + 0.5 - player.getZ(),
                        true,
                        false,
                        true
                );
                return MovementResult.active(List.of(navigationTarget));
            }
            releaseMovementKeys(client);
            if (elytraStartCooldown <= 0) {
                player.connection.send(new ServerboundPlayerCommandPacket(
                        player,
                        ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                ));
                player.startFallFlying();
                elytraStartCooldown = ELYTRA_START_COOLDOWN_TICKS;
            }
            return MovementResult.active(List.of(navigationTarget));
        }

        float yawError = faceMovement(
                player,
                navigationTarget.getX() + 0.5 - player.getX(),
                navigationTarget.getZ() + 0.5 - player.getZ()
        );
        boolean aligned = yawError <= MOVE_ALIGNMENT_DEGREES;
        if (player.onGround()) {
            setMovementKeys(client, aligned, false, false, false, aligned, false, aligned);
            return MovementResult.active(List.of(navigationTarget));
        }

        releaseMovementKeys(client);
        if (elytraStartCooldown <= 0 && (style == AutomationStyle.AGGRESSIVE || aligned)) {
            player.connection.send(new ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ));
            player.startFallFlying();
            elytraStartCooldown = ELYTRA_START_COOLDOWN_TICKS;
        }
        return MovementResult.active(List.of(navigationTarget));
    }

    private MovementResult flyAggressiveElytraToward(
            Minecraft client,
            LocalPlayer player,
            BlockPos navigationTarget,
            double dx,
            double dz,
            double horizontalDistance,
            boolean climbing,
            boolean climbObstacleAhead
    ) {
        float[] look = elytraControlLook(player, dx, dz, horizontalDistance, climbing, climbObstacleAhead);
        sendServerLook(player, look[0], look[1]);
        applyAggressiveElytraVelocity(player, dx, dz, horizontalDistance, climbing, climbObstacleAhead);

        Vec3 velocity = player.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean needsBoost = horizontalDistance > ELYTRA_FIREWORK_DISTANCE
                || horizontalSpeed < ELYTRA_LOW_SPEED
                || climbing;
        if (needsBoost && fireworkCooldown <= 0) {
            if (client.gui.screen() != null) {
                return MovementResult.active(List.of(navigationTarget));
            }
            int slot = findFirework(player);
            if (slot < 0) {
                release(client);
                resetProgress();
                return MovementResult.pause(Component.translatable("message.mappywall.auto_elytra_no_firework"));
            }
            if (!selectOrMoveToHotbar(client, player, slot)) {
                fireworkCooldown = 4;
                return MovementResult.active(List.of(navigationTarget));
            }
            if (client.gameMode != null) {
                useItemWithTemporaryLook(client, player, look[0], look[1]);
                fireworkCooldown = ELYTRA_FIREWORK_AGGRESSIVE_COOLDOWN_TICKS;
            }
        }
        return MovementResult.active(List.of(navigationTarget));
    }

    private void applyAggressiveElytraVelocity(
            LocalPlayer player,
            double dx,
            double dz,
            double horizontalDistance,
            boolean climbing,
            boolean climbObstacleAhead
    ) {
        if (horizontalDistance <= 0.0001) {
            return;
        }
        Vec3 current = player.getDeltaMovement();
        double speed = climbObstacleAhead
                ? AGGRESSIVE_ELYTRA_APPROACH_SPEED * 0.55
                : horizontalDistance < 48.0 ? AGGRESSIVE_ELYTRA_APPROACH_SPEED : AGGRESSIVE_ELYTRA_CRUISE_SPEED;
        double dirX = dx / horizontalDistance;
        double dirZ = dz / horizontalDistance;
        double yVelocity = climbing
                ? Math.max(current.y, climbObstacleAhead ? AGGRESSIVE_ELYTRA_MAX_Y_SPEED : AGGRESSIVE_ELYTRA_CLIMB_SPEED)
                : current.y * 0.92;
        yVelocity = Math.max(-0.42, Math.min(AGGRESSIVE_ELYTRA_MAX_Y_SPEED, yVelocity));
        player.setDeltaMovement(dirX * speed, yVelocity, dirZ * speed);
    }

    private MovementResult flyElytraToward(
            Minecraft client,
            LocalPlayer player,
            BlockPos navigationTarget,
            AutomationStyle style
    ) {
        releaseMovementKeys(client);
        double dx = navigationTarget.getX() + 0.5 - player.getX();
        double dz = navigationTarget.getZ() + 0.5 - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double cruiseAltitude = elytraCruiseAltitude(client);
        boolean climbing = player.getY() < cruiseAltitude - ELYTRA_CLIMB_MARGIN;
        boolean climbObstacleAhead = climbing && climbPathBlocked(client, player, dx, dz, ELYTRA_CLIMB_OBSTACLE_SCAN, false);
        if (style == AutomationStyle.AGGRESSIVE) {
            return flyAggressiveElytraToward(
                    client,
                    player,
                    navigationTarget,
                    dx,
                    dz,
                    horizontalDistance,
                    climbing,
                    climbObstacleAhead
            );
        }

        if (climbing) {
            faceElytraClimb(player, dx, dz, style, climbObstacleAhead);
        } else {
            faceElytra(player, dx, dz, horizontalDistance, style);
        }

        Vec3 velocity = player.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean needsBoost = horizontalDistance > ELYTRA_FIREWORK_DISTANCE
                || horizontalSpeed < ELYTRA_LOW_SPEED
                || climbing;
        if (needsBoost && fireworkCooldown <= 0) {
            if (client.gui.screen() != null) {
                return MovementResult.active(List.of(navigationTarget));
            }
            int slot = findFirework(player);
            if (slot < 0) {
                release(client);
                resetProgress();
                return MovementResult.pause(Component.translatable("message.mappywall.auto_elytra_no_firework"));
            }
            if (!selectOrMoveToHotbar(client, player, slot)) {
                fireworkCooldown = 4;
                return MovementResult.active(List.of(navigationTarget));
            }
            if (client.gameMode != null) {
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                player.swing(InteractionHand.MAIN_HAND);
                fireworkCooldown = style == AutomationStyle.AGGRESSIVE
                        ? ELYTRA_FIREWORK_AGGRESSIVE_COOLDOWN_TICKS
                        : ELYTRA_FIREWORK_NORMAL_COOLDOWN_TICKS;
            }
        }
        return MovementResult.active(List.of(navigationTarget));
    }

    private MovementResult swimOrBoat(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        AutomationStyle style = currentAutomationStyle();
        boolean surfaceRoute = isSurfaceWaterRoute(client, waypoint.pos());
        if (player.isPassenger()) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof AbstractBoat boat) {
                if (!surfaceRoute) {
                    if (!tryDismountVehicle(client, player)) {
                        stopMovement(client);
                    }
                    return MovementResult.active(pathSnapshot());
                }
                return driveBoatToward(client, player, boat, waypoint, style);
            }
        } else {
            if (surfaceRoute && client.gui.screen() == null) {
                if (tryBoardNearbyBoat(client, player, waypoint.pos(), style)) {
                    return MovementResult.active(pathSnapshot());
                }
                if (boatCooldown <= 0 && tryPlaceBoat(client, player, waypoint, style)) {
                    return MovementResult.active(pathSnapshot());
                }
            }
        }
        return swimToward(client, player, waypoint);
    }

    private boolean isSurfaceWaterRoute(Minecraft client, BlockPos pos) {
        return client.level != null
                && client.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
                && !client.level.getFluidState(pos.above()).is(net.minecraft.tags.FluidTags.WATER);
    }

    private MovementResult swimToward(
            Minecraft client,
        LocalPlayer player,
        LocalPathPlanner.PathStep waypoint
    ) {
        if (!player.isInWater()) {
            boolean enteringAbove = waypoint.pos().getY() > player.blockPosition().getY();
            return moveToward(client, player, waypoint, enteringAbove, false, false);
        }
        double dy = waypoint.pos().getY() + 0.5 - player.getY();
        boolean rise = dy > 0.35;
        boolean descend = dy < -0.35;
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            applyAggressiveGroundVelocity(
                    client,
                    player,
                    waypoint.pos().getX() + 0.5 - player.getX(),
                    waypoint.pos().getZ() + 0.5 - player.getZ(),
                    rise,
                    descend,
                    true
            );
            Vec3 velocity = player.getDeltaMovement();
            double yVelocity = rise
                    ? Math.max(velocity.y, 0.08)
                    : descend ? Math.min(velocity.y, -0.08) : velocity.y * 0.65;
            player.setDeltaMovement(velocity.x, yVelocity, velocity.z);
            return MovementResult.active(pathSnapshot());
        }
        return moveToward(client, player, waypoint, rise, descend, true);
    }

    private MovementResult driveBoatToward(
            Minecraft client,
            LocalPlayer player,
            AbstractBoat boat,
            LocalPathPlanner.PathStep waypoint,
            AutomationStyle style
    ) {
        double dx = waypoint.pos().getX() + 0.5 - boat.getX();
        double dz = waypoint.pos().getZ() + 0.5 - boat.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.001) {
            return MovementResult.active(pathSnapshot());
        }

        double dirX = dx / distance;
        double dirZ = dz / distance;
        Vec3 currentVelocity = boat.getDeltaMovement();
        double requestedX = dirX * BOAT_DRIVE_SPEED;
        double requestedZ = dirZ * BOAT_DRIVE_SPEED;
        if (style == AutomationStyle.AGGRESSIVE) {
            // Blend toward the next heading instead of replacing the velocity at
            // every one-block path node. This preserves momentum through small
            // grid-direction changes and avoids visible speed pulses.
            requestedX = Mth.lerp(0.55, currentVelocity.x, requestedX);
            requestedZ = Mth.lerp(0.55, currentVelocity.z, requestedZ);
            double requestedSpeed = Math.sqrt(requestedX * requestedX + requestedZ * requestedZ);
            if (requestedSpeed > BOAT_DRIVE_SPEED) {
                double scale = BOAT_DRIVE_SPEED / requestedSpeed;
                requestedX *= scale;
                requestedZ *= scale;
            }
        }
        Vec3 safeVelocity = collisionAdjustedHorizontalVelocity(
                client,
                boat,
                requestedX,
                currentVelocity.y,
                requestedZ
        );
        if (Math.abs(safeVelocity.x) + Math.abs(safeVelocity.z) <= 0.0001) {
            boat.setInput(false, false, false, false);
            boat.setPaddleState(false, false);
            sendBoatPaddles(client, false, false, true);
            boat.setDeltaMovement(0.0, currentVelocity.y, 0.0);
            movementRecoveryFailures++;
            forceLocalReplan();
            return MovementResult.active(pathSnapshot());
        }
        float targetYaw = (float) (Math.toDegrees(Math.atan2(safeVelocity.z, safeVelocity.x)) - 90.0);
        float yawDelta = Mth.wrapDegrees(targetYaw - boat.getYRot());
        float yaw = boat.getYRot() + Mth.clamp(yawDelta, -18.0F, 18.0F);
        boat.setYRot(yaw);
        boat.setXRot(0.0F);
        boat.setInput(false, false, true, false);
        boat.setPaddleState(true, true);
        // AbstractBoat sends its own paddle state during the entity tick. This
        // controller runs at END_CLIENT_TICK, so reassert the automated state every
        // tick instead of relying on our local packet cache.
        sendBoatPaddles(client, true, true, true);
        sendPlayerInput(client, true, false, false, false, false, false, true);

        if (style == AutomationStyle.AGGRESSIVE) {
            boat.setDeltaMovement(safeVelocity);
            sendServerLook(player, yaw, 0.0F);
            if (player.connection != null) {
                player.connection.send(ServerboundMoveVehiclePacket.fromEntity(boat));
            }
        }
        return MovementResult.active(pathSnapshot());
    }

    private AutomationStyle currentAutomationStyle() {
        return automationStyle;
    }

    private MovementResult breakBlock(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        if (client.gameMode == null || waypoint.actionBlock() == null) {
            forceLocalReplan();
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }
        BlockPos block = waypoint.actionBlock();
        BlockState liveState = client.level.getBlockState(block);
        boolean cleared = liveState.getCollisionShape(client.level, block).isEmpty()
                && client.level.getFluidState(block).isEmpty();
        if (cleared) {
            breakingBlock = null;
            if (!actionAcknowledged) {
                breakActionsForTarget++;
                actionAcknowledged = true;
            }
            if (!isActionEntrySafe(client, player, waypoint)) {
                forceLocalReplan();
                stopMovement(client);
                return MovementResult.active(pathSnapshot());
            }
            if (isAtActionWaypoint(player, waypoint)) {
                advancePathStep();
                return MovementResult.active(pathSnapshot());
            }
            return moveToward(client, player, waypoint, false);
        }
        actionAcknowledged = false;
        if (!isBreakableObstacle(client, block)
                || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(block)) > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS) {
            forceLocalReplan();
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }

        stopMovement(client);
        if (placeCooldown > 0) {
            return MovementResult.active(pathSnapshot());
        }
        if (!selectBestToolForBlock(client, player, block)) {
            placeCooldown = 4;
            return MovementResult.active(pathSnapshot());
        }
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            sendServerLookAt(player, Vec3.atCenterOf(block));
        } else {
            faceBlock(player, block);
        }
        Direction side = Direction.getApproximateNearest(
                player.getX() - (block.getX() + 0.5),
                player.getEyeY() - (block.getY() + 0.5),
                player.getZ() - (block.getZ() + 0.5)
        );
        if (!block.equals(breakingBlock)) {
            breakingBlock = block;
            client.gameMode.startDestroyBlock(block, side);
        } else {
            client.gameMode.continueDestroyBlock(block, side);
        }
        player.swing(InteractionHand.MAIN_HAND);
        return MovementResult.active(pathSnapshot());
    }

    private MovementResult placeBlock(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        if (client.gameMode == null || waypoint.actionBlock() == null) {
            forceLocalReplan();
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }
        BlockPos placePos = waypoint.actionBlock();
        if (isSolid(client, placePos)) {
            pendingPlacementBlock = null;
            pendingPlacementTicks = 0;
            actionAcknowledged = true;
            if (!isActionEntrySafe(client, player, waypoint)) {
                forceLocalReplan();
                stopMovement(client);
                return MovementResult.active(pathSnapshot());
            }
            if (isAtActionWaypoint(player, waypoint)) {
                advancePathStep();
                return MovementResult.active(pathSnapshot());
            }
            return moveToward(client, player, waypoint, false, false, true);
        }
        actionAcknowledged = false;
        if (!isSafeReplaceablePlacement(client, placePos)) {
            forceLocalReplan();
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }

        if (placePos.equals(pendingPlacementBlock)) {
            pendingPlacementTicks++;
            if (pendingPlacementTicks > PLACE_CONFIRM_TIMEOUT_TICKS) {
                pendingPlacementBlock = null;
                pendingPlacementTicks = 0;
                actionFailures++;
                forceLocalReplan();
                stopMovement(client);
                if (actionFailures >= MAX_ACTION_FAILURES) {
                    release(client);
                    resetProgress();
                    return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_stuck"));
                }
                return MovementResult.active(pathSnapshot());
            }
            if (pendingPlacementTicks % 8 != 0) {
                return approachPlacementEdge(client, player, waypoint);
            }
        }
        if (placeCooldown > 0) {
            return approachPlacementEdge(client, player, waypoint);
        }
        int slot = selectedAllowedPlaceBlock(player)
                ? player.getInventory().getSelectedSlot()
                : findAllowedPlaceBlock(player);
        if (slot < 0 && canSwapPlayerInventory(player)) {
            release(client);
            resetProgress();
            return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_no_place_block"));
        }
        if (slot < 0 || (!selectedAllowedPlaceBlock(player) && !canSwapPlayerInventory(player))) {
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }
        if (!selectOrMoveToHotbar(client, player, slot)) {
            placeCooldown = 4;
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }

        BlockHitResult hit = placementHit(client, placePos);
        if (hit == null
                || player.getEyePosition().distanceToSqr(hit.getLocation())
                > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS) {
            forceLocalReplan();
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }

        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            sendServerLookAt(player, hit.getLocation());
        } else {
            float error = face(
                    player,
                    hit.getLocation().x - player.getX(),
                    hit.getLocation().y - player.getEyeY(),
                    hit.getLocation().z - player.getZ(),
                    true
            );
            if (error > NORMAL_INTERACT_ALIGNMENT_DEGREES) {
                return MovementResult.active(pathSnapshot());
            }
        }
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        pendingPlacementBlock = placePos;
        pendingPlacementTicks = 0;
        placeCooldown = currentAutomationStyle() == AutomationStyle.AGGRESSIVE
                ? PLACE_COOLDOWN_AGGRESSIVE_TICKS
                : PLACE_COOLDOWN_NORMAL_TICKS;
        // Placement and walking share a tick, but until the server confirms the
        // support block we only sneak toward the safe edge. Full-speed entry starts
        // from the acknowledged branch above.
        return approachPlacementEdge(client, player, waypoint);
    }

    private MovementResult approachPlacementEdge(
            Minecraft client,
            LocalPlayer player,
            LocalPathPlanner.PathStep waypoint
    ) {
        double dx = waypoint.pos().getX() + 0.5 - player.getX();
        double dz = waypoint.pos().getZ() + 0.5 - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.68) {
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }
        return moveToward(client, player, waypoint, false, true, false);
    }

    private boolean isActionEntrySafe(
            Minecraft client,
            LocalPlayer player,
            LocalPathPlanner.PathStep waypoint
    ) {
        return isMovementStepSafe(client, player, new LocalPathPlanner.PathStep(
                waypoint.pos(),
                LocalPathPlanner.StepAction.WALK,
                null
        ));
    }

    private boolean isAtActionWaypoint(LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        BlockPos pos = waypoint.pos();
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double yError = player.getY() - pos.getY();
        return dx * dx + dz * dz <= WALK_WAYPOINT_DISTANCE_BLOCKS * WALK_WAYPOINT_DISTANCE_BLOCKS
                && Math.abs(yError) <= 0.60
                && (player.onGround() || player.isInWater());
    }

    private boolean tryEat(Minecraft client, LocalPlayer player) {
        AutoNavigationConfig config = navigationConfig();
        if (eatingSession) {
            if (player.isUsingItem()) {
                pressUse(client, true);
                stopMovement(client);
                return true;
            }
            releaseUseKey(client);
            eatingSession = false;
            eatCooldown = EAT_COOLDOWN_TICKS;
            return true;
        }
        if (!config.eatingEnabled() || eatCooldown > 0 || !player.canEat(false)) {
            return false;
        }
        if (player.getFoodData().getFoodLevel() > config.eatAtFoodLevel()) {
            return false;
        }
        if (client.gui.screen() != null) {
            return false;
        }

        int slot = selectedAllowedFood(player)
                ? player.getInventory().getSelectedSlot()
                : findAllowedFood(player);
        if (slot < 0) {
            return false;
        }
        if (!selectedAllowedFood(player) && !canSwapPlayerInventory(player)) {
            return false;
        }
        if (!selectOrMoveToHotbar(client, player, slot)) {
            eatCooldown = 4;
            stopMovement(client);
            return true;
        }
        stopMovement(client);
        if (client.gameMode != null) {
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        pressUse(client, true);
        eatingSession = true;
        return true;
    }

    private boolean tryPlaceBoat(
            Minecraft client,
            LocalPlayer player,
            LocalPathPlanner.PathStep waypoint,
            AutomationStyle style
    ) {
        if (client.gameMode == null || client.level == null) {
            return false;
        }
        BlockPos waterPos = bestBoatWaterPos(client, player, waypoint.pos());
        if (waterPos == null) {
            return false;
        }
        int slot = findBoat(player);
        if (slot < 0) {
            return false;
        }
        if (!selectOrMoveToHotbar(client, player, slot)) {
            return true;
        }

        boolean canInteractNow = style == AutomationStyle.AGGRESSIVE;
        if (!canInteractNow) {
            float yawError = faceMovement(
                    player,
                    waterPos.getX() + 0.5 - player.getX(),
                    waterPos.getZ() + 0.5 - player.getZ()
            );
            face(
                    player,
                    waterPos.getX() + 0.5 - player.getX(),
                    waterPos.getY() + 0.75 - player.getEyeY(),
                    waterPos.getZ() + 0.5 - player.getZ(),
                    true
            );
            canInteractNow = yawError <= SPRINT_ALIGNMENT_DEGREES;
        }
        if (!canInteractNow) {
            return true;
        }
        useBoatItemAtWater(client, player, waterPos, style);
        boatCooldown = BOAT_COOLDOWN_TICKS;
        return true;
    }

    private BlockPos bestBoatWaterPos(Minecraft client, LocalPlayer player, BlockPos waypoint) {
        if (client.level == null) {
            return null;
        }
        BlockPos playerPos = player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    BlockPos candidate = mutable.immutable();
                    if (!isBoatWater(client, candidate)) {
                        continue;
                    }
                    double eyeDistance = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate));
                    if (eyeDistance > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS) {
                        continue;
                    }
                    double waypointDistance = candidate.distSqr(waypoint);
                    double score = waypointDistance + eyeDistance * 0.25;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private boolean isBoatWater(Minecraft client, BlockPos pos) {
        return client.level != null
                && client.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
                && client.level.getBlockState(pos.above()).getCollisionShape(client.level, pos.above()).isEmpty();
    }

    private void useBoatItemAtWater(
            Minecraft client,
            LocalPlayer player,
            BlockPos waterPos,
            AutomationStyle style
    ) {
        Vec3 hit = Vec3.atCenterOf(waterPos).add(0.0, 0.25, 0.0);
        if (style == AutomationStyle.AGGRESSIVE) {
            float oldYaw = player.getYRot();
            float oldPitch = player.getXRot();
            float[] look = lookAngles(player, hit);
            sendServerLook(player, look[0], look[1]);
            player.setYRot(look[0]);
            player.setXRot(look[1]);
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            return;
        }
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private float[] lookAngles(LocalPlayer player, Vec3 target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return new float[] { yaw, pitch };
    }

    private void sendServerLook(LocalPlayer player, float yaw, float pitch) {
        player.connection.send(new ServerboundMovePlayerPacket.Rot(
                yaw,
                pitch,
                player.onGround(),
                player.horizontalCollision
        ));
    }

    private void sendServerLookAt(LocalPlayer player, Vec3 target) {
        float[] look = lookAngles(player, target);
        sendServerLook(player, look[0], look[1]);
    }

    private boolean tryBoardNearbyBoat(
            Minecraft client,
            LocalPlayer player,
            BlockPos waterPos,
            AutomationStyle style
    ) {
        if (client.level == null || client.gameMode == null) {
            return false;
        }
        AABB searchBox = new AABB(waterPos).inflate(style == AutomationStyle.AGGRESSIVE ? 6.0 : 3.0);
        List<AbstractBoat> boats = client.level.getEntitiesOfClass(
                AbstractBoat.class,
                searchBox,
                boat -> boat.isAlive()
                        && boat.getPassengers().isEmpty()
                        && player.getEyePosition().distanceToSqr(boat.position())
                                <= BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS
                        && player.hasLineOfSight(boat)
        );
        if (boats.isEmpty()) {
            return false;
        }
        AbstractBoat boat = boats.stream()
                .min((left, right) -> Double.compare(left.distanceToSqr(player), right.distanceToSqr(player)))
                .orElse(null);
        if (boat == null) {
            return false;
        }
        boolean canInteractNow = style == AutomationStyle.AGGRESSIVE;
        if (!canInteractNow) {
            float yawError = faceMovement(player, boat.getX() - player.getX(), boat.getZ() - player.getZ());
            canInteractNow = yawError <= SPRINT_ALIGNMENT_DEGREES;
        }
        if (!canInteractNow) {
            return true;
        }
        client.gameMode.interact(player, boat, new EntityHitResult(boat), InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private float[] elytraControlLook(
            LocalPlayer player,
            double dx,
            double dz,
            double horizontalDistance,
            boolean climbing,
            boolean climbObstacleAhead
    ) {
        float yaw = player.getYRot();
        if (dx * dx + dz * dz > 0.0001) {
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        }
        float pitch;
        if (climbObstacleAhead) {
            pitch = (float) -ELYTRA_STEEP_CLIMB_ANGLE;
        } else if (climbing) {
            pitch = (float) -ELYTRA_NORMAL_CLIMB_ANGLE;
        } else if (horizontalDistance < 32.0) {
            pitch = 12.0F;
        } else {
            pitch = 2.0F;
        }
        return new float[] { yaw, pitch };
    }

    private void useItemWithTemporaryLook(Minecraft client, LocalPlayer player, float yaw, float pitch) {
        if (client.gameMode == null) {
            return;
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        sendServerLook(player, yaw, pitch);
        player.setYRot(yaw);
        player.setXRot(pitch);
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        player.setYRot(oldYaw);
        player.setXRot(oldPitch);
    }

    private void faceElytra(LocalPlayer player, double dx, double dz, double horizontalDistance, AutomationStyle style) {
        if (dx * dx + dz * dz > 0.0001) {
            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float maxTurn = style == AutomationStyle.AGGRESSIVE ? 30.0F : 16.0F;
            float yawDelta = Mth.wrapDegrees(targetYaw - player.getYRot());
            player.setYRot(player.getYRot() + Mth.clamp(yawDelta, -maxTurn, maxTurn));
        }

        float targetPitch;
        if (horizontalDistance < 32.0) {
            targetPitch = 12.0F;
        } else {
            targetPitch = 2.0F;
        }
        float pitchDelta = Mth.wrapDegrees(targetPitch - player.getXRot());
        player.setXRot(player.getXRot() + Mth.clamp(pitchDelta, -8.0F, 8.0F));
    }

    private void faceElytraClimb(LocalPlayer player, double dx, double dz, AutomationStyle style, boolean climbObstacleAhead) {
        if (dx * dx + dz * dz > 0.0001) {
            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float maxTurn = style == AutomationStyle.AGGRESSIVE ? 22.0F : 12.0F;
            float yawDelta = Mth.wrapDegrees(targetYaw - player.getYRot());
            player.setYRot(player.getYRot() + Mth.clamp(yawDelta, -maxTurn, maxTurn));
        }

        float targetPitch = (float) (climbObstacleAhead ? -ELYTRA_STEEP_CLIMB_ANGLE : -ELYTRA_NORMAL_CLIMB_ANGLE);
        float pitchDelta = Mth.wrapDegrees(targetPitch - player.getXRot());
        player.setXRot(player.getXRot() + Mth.clamp(pitchDelta, -10.0F, 10.0F));
    }

    private double elytraCruiseAltitude(Minecraft client) {
        if (client.level == null) {
            return ELYTRA_CRUISE_ALTITUDE;
        }
        return Math.min(ELYTRA_CRUISE_ALTITUDE, client.level.getMaxY() - 25.0);
    }

    private boolean hasElytraLaunchSpace(Minecraft client, LocalPlayer player, BlockPos navigationTarget) {
        if (client.level == null) {
            return false;
        }
        if (hasVerticalFlightClearance(client, player)) {
            return true;
        }
        double dx = navigationTarget.getX() + 0.5 - player.getX();
        double dz = navigationTarget.getZ() + 0.5 - player.getZ();
        return !climbPathBlocked(client, player, dx, dz, ELYTRA_LAUNCH_CORRIDOR_DISTANCE, false)
                || !climbPathBlocked(client, player, dx, dz, ELYTRA_LAUNCH_CORRIDOR_DISTANCE, true);
    }

    private boolean hasVerticalFlightClearance(Minecraft client, LocalPlayer player) {
        BlockPos base = player.blockPosition();
        for (int yOffset = 1; yOffset <= ELYTRA_LAUNCH_VERTICAL_CLEARANCE; yOffset++) {
            BlockPos center = base.above(yOffset);
            if (!isFlightSpaceClear(client, center)) {
                return false;
            }
        }
        return true;
    }

    private boolean climbPathBlocked(
            Minecraft client,
            LocalPlayer player,
            double dx,
            double dz,
            int scanDistance,
            boolean steep
    ) {
        if (client.level == null) {
            return true;
        }
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= 0.0001) {
            return false;
        }
        double dirX = dx / horizontalDistance;
        double dirZ = dz / horizontalDistance;
        double angle = Math.toRadians(steep ? ELYTRA_STEEP_CLIMB_ANGLE : ELYTRA_NORMAL_CLIMB_ANGLE);
        double horizontalStep = Math.cos(angle);
        double verticalStep = Math.sin(angle);
        Vec3 origin = player.getEyePosition();
        for (int step = 3; step <= scanDistance; step += 2) {
            BlockPos sample = BlockPos.containing(
                    origin.x + dirX * horizontalStep * step,
                    origin.y + verticalStep * step,
                    origin.z + dirZ * horizontalStep * step
            );
            if (!isFlightSpaceClear(client, sample)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFlightSpaceClear(Minecraft client, BlockPos center) {
        if (client.level == null) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = center.offset(dx, 0, dz);
                if (!client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasEquippedElytra(LocalPlayer player) {
        ItemStack elytra = player.getItemBySlot(EquipmentSlot.CHEST);
        return elytra.is(Items.ELYTRA)
                && (!elytra.isDamageableItem() || elytra.getDamageValue() < elytra.getMaxDamage() - 1);
    }

    private int findFirework(LocalPlayer player) {
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
            if (stack.is(Items.FIREWORK_ROCKET)) {
                return slot;
            }
        }
        return -1;
    }

    private void handleNavigationTargetChange(RouteStep target) {
        String signature = navigationSignature(target);
        if (signature.equals(targetSignature)) {
            return;
        }

        cancelPendingPlan();
        pathSegments.resetTarget(signature);
        targetSignature = signature;
        currentStepOrdinal = 0;
        replanCooldown = 0;
        failedReplans = 0;
        breakingBlock = null;
        pendingPlacementBlock = null;
        pendingPlacementTicks = 0;
        activeStepSignature = null;
        activeStepTicks = 0;
        actionAcknowledged = false;
        dropCommitted = false;
    }

    private void advancePendingCapture() {
        if (pendingCapture == null || pendingPlanningRequest == null) {
            return;
        }
        if (!pendingCapture.advance(SNAPSHOT_COLUMNS_PER_TICK)) {
            return;
        }

        LocalPathPlanner.NavigationSnapshot snapshot = pendingCapture.finish();
        PlanningRequest request = pendingPlanningRequest;
        pendingCapture = null;
        pendingPlan = PATH_EXECUTOR.submit(
                () -> pathPlanner.plan(snapshot, request.target(), request.config())
        );
    }

    private void startEligibleLookahead(Minecraft client, RouteStep target) {
        int remaining = pathSegments.remainingSteps();
        if (client.level == null
                || pendingPlanningRequest != null
                || pendingCapture != null
                || pendingPlan != null
                || pathSegments.hasBuffered()
                || remaining < 1
                || remaining > LOOKAHEAD_REMAINING_STEPS) {
            return;
        }

        boolean suffixStable = pathSegments.remainingStepSnapshot().stream()
                .noneMatch(this::isModifyingStep);
        pathSegments.beginLookahead(suffixStable).ifPresent(request -> {
            BlockPos seam = blockPos(request.seam());
            beginCapture(
                    client,
                    target,
                    seam,
                    PlanRequestKind.LOOKAHEAD,
                    request
            );
        });
    }

    private void startInitialCaptureIfNeeded(Minecraft client, RouteStep target) {
        if (replanCooldown > 0) {
            replanCooldown--;
        }
        if (client.player == null
                || client.level == null
                || pendingPlanningRequest != null
                || pendingCapture != null
                || pendingPlan != null
                || pathSegments.remainingSteps() > 0
                || pathSegments.hasBuffered()
                || replanCooldown > 0) {
            return;
        }

        beginCapture(
                client,
                target,
                client.player.blockPosition(),
                PlanRequestKind.INITIAL,
                null
        );
        replanCooldown = REPLAN_INTERVAL_TICKS;
    }

    private void beginCapture(
            Minecraft client,
            RouteStep target,
            BlockPos plannedStart,
            PlanRequestKind kind,
            PathSegmentCoordinator.LookaheadRequest lookaheadRequest
    ) {
        if (client.level == null) {
            if (lookaheadRequest != null) {
                pathSegments.failLookahead(lookaheadRequest);
            }
            return;
        }

        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(client.level, plannedStart);
        long generation = ++planGeneration;
        pendingPlanningRequest = new PlanningRequest(
                kind,
                targetSignature,
                generation,
                target,
                navigationConfig(),
                plannedStart,
                lookaheadRequest
        );
        pendingCapture = capture;
    }

    private void acceptCompletedPlan(Minecraft client, LocalPlayer player, RouteStep target) {
        if (pendingPlan == null || !pendingPlan.isDone() || pendingPlanningRequest == null) {
            return;
        }

        PlanningRequest request = pendingPlanningRequest;
        try {
            LocalPathPlanner.PathPlan plan = pendingPlan.get();
            boolean currentRequest = request.generation() == planGeneration;
            boolean sameTarget = request.targetSignature().equals(targetSignature)
                    && request.targetSignature().equals(navigationSignature(target));
            boolean sameConfig = request.config().equals(navigationConfig());
            if (!currentRequest || !sameTarget || !sameConfig) {
                rejectPlanningRequest(request, false);
            } else if (request.kind() == PlanRequestKind.INITIAL) {
                acceptInitialPlan(player, request, plan);
            } else {
                acceptLookaheadPlan(client, request, plan);
            }
        } catch (CancellationException exception) {
            rejectPlanningRequest(request, request.kind() == PlanRequestKind.INITIAL);
        } catch (ExecutionException exception) {
            rejectPlanningRequest(request, request.kind() == PlanRequestKind.INITIAL);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rejectPlanningRequest(request, request.kind() == PlanRequestKind.INITIAL);
        } finally {
            pendingPlan = null;
            pendingPlanningRequest = null;
        }
    }

    private void acceptInitialPlan(
            LocalPlayer player,
            PlanningRequest request,
            LocalPathPlanner.PathPlan plan
    ) {
        boolean startStillCurrent = request.plannedStart()
                .distSqr(player.blockPosition()) <= MAX_PLAN_START_DRIFT_SQR;
        if (!startStillCurrent || !plan.isExecutable()) {
            rejectPlanningRequest(request, true);
            return;
        }

        pathSegments.installInitial(toSegment(plan));
        currentStepOrdinal = 0;
        replanCooldown = REPLAN_INTERVAL_TICKS;
        breakingBlock = null;
        activeStepSignature = null;
        activeStepTicks = 0;
        failedReplans = 0;
    }

    private void acceptLookaheadPlan(
            Minecraft client,
            PlanningRequest request,
            LocalPathPlanner.PathPlan plan
    ) {
        PathSegmentCoordinator.LookaheadRequest lookaheadRequest = request.lookaheadRequest();
        boolean exactSeam = lookaheadRequest != null
                && plan.plannedStart().equals(blockPos(lookaheadRequest.seam()));
        if (!exactSeam
                || !plan.isExecutable()
                || !isLookaheadContinuationSafe(client, lookaheadRequest, plan)
                || !pathSegments.acceptLookahead(lookaheadRequest, toSegment(plan))) {
            rejectPlanningRequest(request, false);
        }
    }

    private void rejectPlanningRequest(PlanningRequest request, boolean countInitialFailure) {
        if (request.lookaheadRequest() != null) {
            pathSegments.failLookahead(request.lookaheadRequest());
        }
        if (countInitialFailure) {
            failedReplans++;
            replanCooldown = Math.max(replanCooldown, 20);
        }
    }

    private PathSegmentCoordinator.Segment<LocalPathPlanner.PathStep> toSegment(
            LocalPathPlanner.PathPlan plan
    ) {
        boolean modifying = plan.steps().stream().anyMatch(this::isModifyingStep);
        return new PathSegmentCoordinator.Segment<>(
                anchor(plan.plannedStart()),
                anchor(plan.plannedEnd()),
                plan.steps(),
                plan.outcome() == LocalPathPlanner.PathOutcome.REACHED_TARGET,
                plan.outcome() == LocalPathPlanner.PathOutcome.SAFE_FRONTIER && !modifying
        );
    }

    private boolean isModifyingStep(LocalPathPlanner.PathStep step) {
        return step.action() == LocalPathPlanner.StepAction.BREAK
                || step.action() == LocalPathPlanner.StepAction.PLACE;
    }

    private PathSegmentCoordinator.Anchor anchor(BlockPos pos) {
        return new PathSegmentCoordinator.Anchor(pos.getX(), pos.getY(), pos.getZ());
    }

    private BlockPos blockPos(PathSegmentCoordinator.Anchor anchor) {
        return new BlockPos(anchor.x(), anchor.y(), anchor.z());
    }

    private LocalPathPlanner.PathStep nextWaypoint(LocalPlayer player) {
        while (true) {
            LocalPathPlanner.PathStep step = pathSegments.currentStepOrPromote().orElse(null);
            if (step == null) {
                return null;
            }
            if (step.action() == LocalPathPlanner.StepAction.BREAK
                    || step.action() == LocalPathPlanner.StepAction.PLACE) {
                return step;
            }
            if (isAtWaypoint(player, step)) {
                advancePathStep();
                continue;
            }
            return step;
        }
    }

    private boolean isAtWaypoint(LocalPlayer player, LocalPathPlanner.PathStep step) {
        BlockPos pos = step.pos();
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double yError = player.getY() - pos.getY();
        return switch (step.action()) {
            case WALK -> horizontalDistance <= WALK_WAYPOINT_DISTANCE_BLOCKS
                    && Math.abs(yError) <= 0.60
                    && (player.onGround() || player.isInWater());
            case JUMP -> horizontalDistance <= JUMP_WAYPOINT_DISTANCE_BLOCKS
                    && yError >= -0.15
                    && yError <= 0.70
                    && player.onGround();
            case DROP -> horizontalDistance <= DROP_WAYPOINT_DISTANCE_BLOCKS
                    && Math.abs(yError) <= 0.65
                    && (player.onGround() || player.isInWater());
            case SWIM -> horizontalDistance <= SWIM_WAYPOINT_DISTANCE_BLOCKS
                    && Math.abs(yError) <= 1.25
                    && (player.isInWater() || player.getVehicle() instanceof AbstractBoat);
            case BREAK, PLACE -> false;
        };
    }

    private void advancePathStep() {
        if (pathSegments.advance()) {
            currentStepOrdinal++;
        }
        activeStepSignature = null;
        activeStepTicks = 0;
        actionFailures = 0;
        movementRecoveryFailures = 0;
        pendingPlacementBlock = null;
        pendingPlacementTicks = 0;
        actionAcknowledged = false;
        dropCommitted = false;
    }

    private void updateProgress(LocalPlayer player, RouteStep target, LocalPathPlanner.PathStep waypoint) {
        BlockPos navigationTarget = navigationTarget(player, target);
        double distance = Math.sqrt(squaredHorizontalDistance(player, navigationTarget));
        double waypointDistance = Math.sqrt(squaredHorizontalDistance(player, waypoint.pos()));
        Vec3 playerPos = new Vec3(player.getX(), player.getY(), player.getZ());
        double movedX = playerPos.x - lastPlayerPos.x;
        double movedZ = playerPos.z - lastPlayerPos.z;
        double playerMoved = Math.sqrt(movedX * movedX + movedZ * movedZ);
        boolean madeProgress = distance < lastDistance - STUCK_EPSILON
                || waypointDistance < lastWaypointDistance - STUCK_EPSILON;
        if (madeProgress) {
            stuckTicks = 0;
            if (isMovementAction(waypoint.action())) {
                movementRecoveryFailures = 0;
            }
        } else if (isProgressingMovement(waypoint.action())) {
            stuckTicks++;
        }
        lastDistance = distance;
        lastWaypointDistance = waypointDistance;
        lastPlayerPos = playerPos;

        boolean movementAction = isProgressingMovement(waypoint.action());
        // A horizontal-collision flag is also raised while vanilla collision
        // resolution is successfully sliding the entity along a wall. Replan only
        // when the collision is accompanied by no useful motion; otherwise a
        // harmless brush repeatedly discards a valid detour path.
        if (movementAction && player.horizontalCollision && playerMoved <= PLAYER_MOVE_EPSILON) {
            horizontalCollisionTicks++;
        } else {
            horizontalCollisionTicks = 0;
        }
        if (movementAction) {
            recordMovementSample(playerPos);
        } else {
            movementSamples.clear();
        }

        if (horizontalCollisionTicks >= COLLISION_REPLAN_TICKS
                || stuckTicks >= STUCK_TICKS_LIMIT
                || (movementAction && isTrappedInRecentArea(LOCAL_STALL_TICKS, LOCAL_STALL_AREA_BLOCKS))
                || (movementAction && isTrappedInRecentArea(LOOP_STALL_TICKS, LOOP_STALL_AREA_BLOCKS))) {
            if (movementAction) {
                movementRecoveryFailures++;
            }
            forceLocalReplan();
        }
    }

    private boolean isMovementAction(LocalPathPlanner.StepAction action) {
        return action == LocalPathPlanner.StepAction.WALK
                || action == LocalPathPlanner.StepAction.JUMP
                || action == LocalPathPlanner.StepAction.DROP
                || action == LocalPathPlanner.StepAction.SWIM;
    }

    private boolean isProgressingMovement(LocalPathPlanner.StepAction action) {
        return isMovementAction(action)
                || (actionAcknowledged
                        && (action == LocalPathPlanner.StepAction.BREAK
                                || action == LocalPathPlanner.StepAction.PLACE));
    }

    private void recordMovementSample(Vec3 playerPos) {
        movementSamples.addLast(new MovementSample(++movementSampleTick, playerPos.x, playerPos.z));
        while (!movementSamples.isEmpty()
                && movementSampleTick - movementSamples.getFirst().tick() > LOOP_STALL_TICKS) {
            movementSamples.removeFirst();
        }
    }

    private boolean isTrappedInRecentArea(int ticks, double maxSpan) {
        if (movementSamples.size() < ticks) {
            return false;
        }

        MovementSample newest = movementSamples.getLast();
        double minX = newest.x();
        double maxX = newest.x();
        double minZ = newest.z();
        double maxZ = newest.z();
        int count = 0;
        var iterator = movementSamples.descendingIterator();
        while (iterator.hasNext() && count < ticks) {
            MovementSample sample = iterator.next();
            minX = Math.min(minX, sample.x());
            maxX = Math.max(maxX, sample.x());
            minZ = Math.min(minZ, sample.z());
            maxZ = Math.max(maxZ, sample.z());
            count++;
        }
        return count >= ticks && Math.max(maxX - minX, maxZ - minZ) <= maxSpan;
    }

    private void forceLocalReplan() {
        cancelPendingPlan();
        pathSegments.clear();
        currentStepOrdinal = 0;
        replanCooldown = 0;
        stuckTicks = 0;
        horizontalCollisionTicks = 0;
        breakingBlock = null;
        activeStepSignature = null;
        activeStepTicks = 0;
        pendingPlacementBlock = null;
        pendingPlacementTicks = 0;
        actionAcknowledged = false;
        dropCommitted = false;
        movementSamples.clear();
    }

    private double squaredHorizontalDistance(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dz * dz;
    }

    private int findAllowedFood(LocalPlayer player) {
        AutoNavigationConfig config = navigationConfig();
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (config.allowsFood(id)) {
                return slot;
            }
        }
        return -1;
    }

    private int findAllowedPlaceBlock(LocalPlayer player) {
        AutoNavigationConfig config = navigationConfig();
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
            if (!(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (config.allowsPlace(id)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean selectedAllowedFood(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.get(DataComponents.FOOD) == null) {
            return false;
        }
        return navigationConfig().allowsFood(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private boolean selectedAllowedPlaceBlock(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        return navigationConfig().allowsPlace(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private boolean canSwapPlayerInventory(LocalPlayer player) {
        return player.containerMenu == player.inventoryMenu;
    }

    private boolean selectBestToolForBlock(Minecraft client, LocalPlayer player, BlockPos block) {
        if (client.level == null) {
            return true;
        }
        BlockState state = client.level.getBlockState(block);
        int slot = findBestTool(player, state);
        if (slot < 0 || slot == player.getInventory().getSelectedSlot()) {
            return true;
        }
        if (!canSwapPlayerInventory(player)) {
            return true;
        }
        return selectOrMoveToHotbar(client, player, slot);
    }

    private int findBestTool(LocalPlayer player, BlockState state) {
        int selected = player.getInventory().getSelectedSlot();
        double bestScore = miningScore(player.getInventory().getNonEquipmentItems().get(selected), state);
        int bestSlot = selected;
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            double score = miningScore(stack, state);
            if (score > bestScore + 0.05) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot == selected ? -1 : bestSlot;
    }

    private double miningScore(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        double score = stack.getDestroySpeed(state);
        if (stack.isCorrectToolForDrops(state)) {
            score += 100.0;
        }
        return score;
    }

    private int findBoat(LocalPlayer player) {
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
            if (stack.getItem() instanceof BoatItem) {
                return slot;
            }
        }
        return -1;
    }

    private boolean selectOrMoveToHotbar(Minecraft client, LocalPlayer player, int inventorySlot) {
        if (inventorySlot < 0 || client.gui.screen() != null) {
            return false;
        }
        if (inventorySlot < 9) {
            player.getInventory().setSelectedSlot(inventorySlot);
            return true;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (client.gameMode == null || !canSwapPlayerInventory(player)) {
            return false;
        }
        client.gameMode.handleContainerInput(
                player.containerMenu.containerId,
                inventorySlot,
                selected,
                ContainerInput.SWAP,
                player
        );
        player.getInventory().setSelectedSlot(selected);
        return false;
    }

    private BlockHitResult placementHit(Minecraft client, BlockPos placePos) {
        BlockPos below = placePos.below();
        if (isSolid(client, below)) {
            return new BlockHitResult(Vec3.atCenterOf(below).add(0.0, 0.5, 0.0), Direction.UP, below, false);
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = placePos.relative(direction);
            if (isSolid(client, neighbor)) {
                Direction face = direction.getOpposite();
                Vec3 hit = Vec3.atCenterOf(neighbor).add(
                        face.getStepX() * 0.5,
                        face.getStepY() * 0.5,
                        face.getStepZ() * 0.5
                );
                return new BlockHitResult(hit, face, neighbor, false);
            }
        }
        return null;
    }

    private boolean isSolid(Minecraft client, BlockPos pos) {
        if (client.level == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        return !state.getCollisionShape(client.level, pos).isEmpty();
    }

    private boolean isSafeReplaceablePlacement(Minecraft client, BlockPos pos) {
        if (client.level == null || !client.level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        return state.isAir()
                && state.canBeReplaced()
                && state.getCollisionShape(client.level, pos).isEmpty()
                && client.level.getFluidState(pos).isEmpty()
                && client.level.getBlockEntity(pos) == null;
    }

    private boolean isBreakableObstacle(Minecraft client, BlockPos pos) {
        if (client.level == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.getCollisionShape(client.level, pos).isEmpty()
                || !client.level.getFluidState(pos).isEmpty()
                || client.level.getBlockEntity(pos) != null
                || state.getDestroySpeed(client.level, pos) < 0.0F) {
            return false;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return navigationConfig().allowsBreak(blockId);
    }

    private void setMovementKeys(
            Minecraft client,
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        if (client.options == null) {
            return;
        }
        sendNeutralInput(client, false);
        client.options.keyUp.setDown(forward);
        client.options.keyDown.setDown(back);
        client.options.keyLeft.setDown(left);
        client.options.keyRight.setDown(right);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(sneak);
        client.options.keySprint.setDown(sprint);
        movementKeysHeld = forward || back || left || right || jump || sneak || sprint;
    }

    private void releaseMovementKeys(Minecraft client) {
        clearVanillaMovementKeys(client);
        sendNeutralInput(client, false);
    }

    private void clearVanillaMovementKeys(Minecraft client) {
        if (client.options != null && movementKeysHeld) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keyShift.setDown(false);
            client.options.keySprint.setDown(false);
        }
        movementKeysHeld = false;
    }

    private void stopMovement(Minecraft client) {
        releaseMovementKeys(client);
        releaseDirectMovementState(client);
        releaseVehicleControls(client);
    }

    private void applyAggressiveGroundVelocity(
            Minecraft client,
            LocalPlayer player,
            double dx,
            double dz,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        clearVanillaMovementKeys(client);

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= 0.0001) {
            setDirectMovementState(player, false);
            sendPlayerInput(client, false, false, false, false, false, sneak, false);
            return;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        sendServerLook(player, yaw, 0.0F);
        double dirX = dx / horizontalDistance;
        double dirZ = dz / horizontalDistance;
        double speed;
        if (sneak) {
            speed = AGGRESSIVE_SNEAK_SPEED;
        } else if (player.isInWater()) {
            speed = AGGRESSIVE_SWIM_SPEED;
        } else {
            speed = sprint ? AGGRESSIVE_GROUND_SPEED : AGGRESSIVE_GROUND_SPEED * 0.72;
        }
        if (jump && !player.isInWater()) {
            speed = Math.min(speed, 0.20);
        }

        Vec3 current = player.getDeltaMovement();
        double velocityY = current.y;
        boolean shouldJump = jump && (player.onGround() || player.isInWater());
        if (shouldJump) {
            velocityY = player.isInWater() ? Math.max(current.y, 0.08) : Math.max(current.y, AGGRESSIVE_JUMP_VELOCITY);
        }

        double velocityX = dirX * speed;
        double velocityZ = dirZ * speed;
        if (!jump
                && !shouldJump
                && !player.isInWater()
                && client.level != null) {
            Vec3 safeVelocity = collisionAdjustedHorizontalVelocity(
                    client,
                    player,
                    velocityX,
                    velocityY,
                    velocityZ
            );
            velocityX = safeVelocity.x;
            velocityZ = safeVelocity.z;
        }

        setDirectMovementState(player, sprint && !sneak);
        player.setDeltaMovement(velocityX, velocityY, velocityZ);
        sendPlayerInput(client, true, false, false, false, shouldJump, sneak, sprint && !sneak);
    }

    /**
     * Projects a requested horizontal motion onto any collision-free axis. Vanilla
     * movement resolves blocked axes independently; aggressive control must do the
     * same instead of cancelling the whole vector when it merely brushes a wall.
     */
    private Vec3 collisionAdjustedHorizontalVelocity(
            Minecraft client,
            Entity entity,
            double velocityX,
            double velocityY,
            double velocityZ
    ) {
        if (client.level == null) {
            return new Vec3(velocityX, velocityY, velocityZ);
        }
        AABB bounds = entity.getBoundingBox().deflate(0.01);
        if (client.level.noCollision(entity, bounds.move(velocityX, 0.0, velocityZ))) {
            return new Vec3(velocityX, velocityY, velocityZ);
        }

        boolean xClear = Math.abs(velocityX) > 0.0001
                && client.level.noCollision(entity, bounds.move(velocityX, 0.0, 0.0))
                && hasSafeProjectedSupport(client, entity, velocityX, 0.0);
        boolean zClear = Math.abs(velocityZ) > 0.0001
                && client.level.noCollision(entity, bounds.move(0.0, 0.0, velocityZ))
                && hasSafeProjectedSupport(client, entity, 0.0, velocityZ);
        if (xClear && zClear) {
            // The combined diagonal clips a corner. Preserve the component that
            // contributes most to the requested heading, then let the next tick
            // continue around the corner.
            return Math.abs(velocityX) >= Math.abs(velocityZ)
                    ? new Vec3(velocityX, velocityY, 0.0)
                    : new Vec3(0.0, velocityY, velocityZ);
        }
        if (xClear) {
            return new Vec3(velocityX, velocityY, 0.0);
        }
        if (zClear) {
            return new Vec3(0.0, velocityY, velocityZ);
        }
        return new Vec3(0.0, velocityY, 0.0);
    }

    private boolean hasSafeProjectedSupport(
            Minecraft client,
            Entity entity,
            double velocityX,
            double velocityZ
    ) {
        if (!(entity instanceof LocalPlayer player) || player.isInWater() || !player.onGround()) {
            return true;
        }
        BlockPos projectedFeet = BlockPos.containing(
                entity.getX() + velocityX,
                entity.getY(),
                entity.getZ() + velocityZ
        );
        return isLiveBodyClear(client, projectedFeet)
                && isSafeSolidSupport(client, projectedFeet.below());
    }

    private void setDirectMovementState(LocalPlayer player, boolean sprint) {
        if (sprint) {
            player.setSprinting(true);
            directSprintHeld = true;
        } else if (directSprintHeld) {
            player.setSprinting(false);
            directSprintHeld = false;
        }
    }

    private void releaseDirectMovementState(Minecraft client) {
        if (client.player != null) {
            if (directSprintHeld) {
                client.player.setSprinting(false);
            }
        }
        directSprintHeld = false;
    }

    private boolean tryDismountVehicle(Minecraft client, LocalPlayer player) {
        if (!player.isPassenger() || dismountCooldown > 0) {
            return false;
        }
        releaseVehicleControls(client);
        releaseMovementKeys(client);
        releaseUseKey(client);
        sendPlayerInput(client, false, false, false, false, false, true, false);
        if (client.options != null) {
            client.options.keyShift.setDown(true);
            movementKeysHeld = true;
        }
        player.stopRiding();
        dismountCooldown = 10;
        return true;
    }

    private void pressUse(Minecraft client, boolean pressed) {
        if (client.options == null) {
            return;
        }
        client.options.keyUse.setDown(pressed);
        useKeyHeld = pressed;
    }

    private void releaseUseKey(Minecraft client) {
        if (client.options == null || !useKeyHeld) {
            return;
        }
        client.options.keyUse.setDown(false);
        useKeyHeld = false;
    }

    private void sendPlayerInput(
            Minecraft client,
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        if (client.player == null) {
            return;
        }
        DirectInput input = new DirectInput(forward, back, left, right, jump, sneak, sprint);
        Input playerInput = new Input(forward, back, left, right, jump, sneak, sprint);
        // LocalPlayer may have sent a user-derived input packet earlier in this same
        // tick. Aggressive control runs at END_CLIENT_TICK and must reassert the
        // server-facing input even when our requested state itself did not change.
        client.player.input.keyPresses = playerInput;
        client.player.connection.send(new ServerboundPlayerInputPacket(
                playerInput
        ));
        lastDirectInput = input;
    }

    private void sendNeutralInput(Minecraft client, boolean force) {
        if (client.player == null) {
            lastDirectInput = DirectInput.NEUTRAL;
            return;
        }
        if (!force && lastDirectInput.equals(DirectInput.NEUTRAL)) {
            return;
        }
        client.player.input.keyPresses = Input.EMPTY;
        client.player.connection.send(new ServerboundPlayerInputPacket(
                Input.EMPTY
        ));
        lastDirectInput = DirectInput.NEUTRAL;
    }

    private void sendBoatPaddles(Minecraft client, boolean left, boolean right) {
        sendBoatPaddles(client, left, right, false);
    }

    private void sendBoatPaddles(Minecraft client, boolean left, boolean right, boolean force) {
        if (client.player == null) {
            return;
        }
        BoatInput input = new BoatInput(left, right);
        if (!force && input.equals(lastBoatInput)) {
            return;
        }
        client.player.connection.send(new ServerboundPaddleBoatPacket(left, right));
        lastBoatInput = input;
    }

    private void releaseVehicleControls(Minecraft client) {
        if (client.player == null || !client.player.isPassenger()) {
            lastBoatInput = BoatInput.NEUTRAL;
            return;
        }
        Entity vehicle = client.player.getVehicle();
        if (vehicle instanceof AbstractBoat boat) {
            boat.setInput(false, false, false, false);
            boat.setPaddleState(false, false);
            sendBoatPaddles(client, false, false);
        } else {
            lastBoatInput = BoatInput.NEUTRAL;
        }
    }

    private float faceMovement(LocalPlayer player, double dx, double dz) {
        if (dx * dx + dz * dz <= 0.0001) {
            return 0.0F;
        }
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = Mth.wrapDegrees(targetYaw - player.getYRot());
        player.setYRot(player.getYRot() + Mth.clamp(yawDelta, -MOVEMENT_TURN_DEGREES, MOVEMENT_TURN_DEGREES));
        return Math.abs(yawDelta);
    }

    private void faceBlock(LocalPlayer player, BlockPos block) {
        face(
                player,
                block.getX() + 0.5 - player.getX(),
                block.getY() + 0.5 - player.getEyeY(),
                block.getZ() + 0.5 - player.getZ(),
                true
        );
    }

    private float face(LocalPlayer player, double dx, double dy, double dz, boolean includePitch) {
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = Mth.wrapDegrees(targetYaw - player.getYRot());
        float nextYaw = player.getYRot() + Mth.clamp(yawDelta, -35.0F, 35.0F);
        player.setYRot(nextYaw);
        float error = Math.abs(yawDelta);

        if (includePitch) {
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
            float pitchDelta = Mth.wrapDegrees(targetPitch - player.getXRot());
            player.setXRot(player.getXRot() + Mth.clamp(pitchDelta, -25.0F, 25.0F));
            error = Math.max(error, Math.abs(pitchDelta));
        }
        return error;
    }

    private void tickCooldowns() {
        if (placeCooldown > 0) {
            placeCooldown--;
        }
        if (boatCooldown > 0) {
            boatCooldown--;
        }
        if (eatCooldown > 0) {
            eatCooldown--;
        }
        if (elytraStartCooldown > 0) {
            elytraStartCooldown--;
        }
        if (fireworkCooldown > 0) {
            fireworkCooldown--;
        }
        if (dismountCooldown > 0) {
            dismountCooldown--;
        }
    }

    private void resetProgress() {
        cancelPendingPlan();
        pathSegments.clear();
        currentStepOrdinal = 0;
        replanCooldown = 0;
        stuckTicks = 0;
        horizontalCollisionTicks = 0;
        failedReplans = 0;
        lastDistance = Double.MAX_VALUE;
        lastWaypointDistance = Double.MAX_VALUE;
        lastPlayerPos = Vec3.ZERO;
        targetSignature = null;
        breakingBlock = null;
        movementSamples.clear();
        movementSampleTick = 0;
        waitingForChunk = false;
        activeStepSignature = null;
        activeStepTicks = 0;
        actionFailures = 0;
        movementRecoveryFailures = 0;
        pendingPlacementBlock = null;
        pendingPlacementTicks = 0;
        actionAcknowledged = false;
        dropCommitted = false;
        eatingSession = false;
        placeCooldown = 0;
        boatCooldown = 0;
        eatCooldown = 0;
        elytraStartCooldown = 0;
        fireworkCooldown = 0;
        dismountCooldown = 0;
    }

    private void resetBreakBudgetIfTargetChanged(RouteStep target) {
        String signature = target.region().signature();
        if (!signature.equals(breakBudgetTargetSignature)) {
            breakBudgetTargetSignature = signature;
            breakActionsForTarget = 0;
            movementSamples.clear();
            movementSampleTick = 0;
        }
    }

    private boolean arrivedAtNavigationTarget(LocalPlayer player, RouteStep target) {
        if (target.state() == RouteStepState.OPENED) {
            return target.targetBlock().distanceSquaredTo(player.getX(), player.getZ())
                    <= ARRIVAL_DISTANCE_BLOCKS * ARRIVAL_DISTANCE_BLOCKS;
        }
        return target.region().bounds().contains(player.getX(), player.getZ());
    }

    private void cancelPendingPlan() {
        planGeneration++;
        if (pendingPlanningRequest != null && pendingPlanningRequest.lookaheadRequest() != null) {
            pathSegments.failLookahead(pendingPlanningRequest.lookaheadRequest());
        }
        pendingCapture = null;
        if (pendingPlan != null && !pendingPlan.isDone()) {
            pendingPlan.cancel(true);
        }
        pendingPlan = null;
        pendingPlanningRequest = null;
    }

    private String navigationSignature(RouteStep target) {
        return target.region().signature()
                + ":" + target.state()
                + ":" + target.targetBlock().x()
                + ":" + target.targetBlock().z();
    }

    private BlockPos navigationTarget(LocalPlayer player, RouteStep target) {
        if (target.state() == RouteStepState.OPENED) {
            return new BlockPos(target.targetBlock().x(), player.blockPosition().getY(), target.targetBlock().z());
        }
        int targetX = Mth.clamp(
                player.blockPosition().getX(),
                interiorMin(target.region().bounds().minX(), target.region().bounds().maxX()),
                interiorMax(target.region().bounds().minX(), target.region().bounds().maxX())
        );
        int targetZ = Mth.clamp(
                player.blockPosition().getZ(),
                interiorMin(target.region().bounds().minZ(), target.region().bounds().maxZ()),
                interiorMax(target.region().bounds().minZ(), target.region().bounds().maxZ())
        );
        return new BlockPos(targetX, player.blockPosition().getY(), targetZ);
    }

    private int interiorMin(int min, int max) {
        return max - min + 1 <= REGION_ENTRY_INSET_BLOCKS * 2 ? min : min + REGION_ENTRY_INSET_BLOCKS;
    }

    private int interiorMax(int min, int max) {
        return max - min + 1 <= REGION_ENTRY_INSET_BLOCKS * 2 ? max : max - REGION_ENTRY_INSET_BLOCKS;
    }

    private enum PlanRequestKind {
        INITIAL,
        LOOKAHEAD
    }

    private record PlanningRequest(
            PlanRequestKind kind,
            String targetSignature,
            long generation,
            RouteStep target,
            AutoNavigationConfig config,
            BlockPos plannedStart,
            PathSegmentCoordinator.LookaheadRequest lookaheadRequest
    ) {
        private PlanningRequest {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetSignature, "targetSignature");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(plannedStart, "plannedStart");
            if (kind == PlanRequestKind.LOOKAHEAD) {
                Objects.requireNonNull(lookaheadRequest, "lookaheadRequest");
            } else if (lookaheadRequest != null) {
                throw new IllegalArgumentException("Initial planning cannot carry a lookahead request");
            }
        }
    }

    private record DirectInput(
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        private static final DirectInput NEUTRAL = new DirectInput(false, false, false, false, false, false, false);
    }

    private record BoatInput(boolean left, boolean right) {
        private static final BoatInput NEUTRAL = new BoatInput(false, false);
    }

    private record MovementSample(int tick, double x, double z) {
    }

    public record MovementResult(boolean moving, boolean waitingForChunk, Component pauseMessage, List<BlockPos> path) {
        static MovementResult none() {
            return new MovementResult(false, false, null, List.of());
        }

        static MovementResult active(List<BlockPos> path) {
            return new MovementResult(true, false, null, path);
        }

        static MovementResult waiting(List<BlockPos> path) {
            return new MovementResult(false, true, null, path);
        }

        static MovementResult pause(Component message) {
            return new MovementResult(false, false, message, List.of());
        }

        public boolean shouldPause() {
            return pauseMessage != null;
        }
    }
}

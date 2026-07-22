package dev.mappywall.client;

import dev.mappywall.client.LocalPathPlanner.ContinuationContext;
import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.BoatAcquisitionPolicy;
import dev.mappywall.core.BoatDismountRecovery;
import dev.mappywall.core.BoatDismountRecovery.Action;
import dev.mappywall.core.BoatDismountRecovery.Observation;
import dev.mappywall.core.BoatDismountRecovery.RequestPosition;
import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.NavigationPlanningCadence;
import dev.mappywall.core.NavigationPlanningRetryState;
import dev.mappywall.core.NavigationTerrainProbe;
import dev.mappywall.core.PathSegmentCoordinator;
import dev.mappywall.core.PathSegmentCoordinator.PreviewPolicy;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.RunMode;
import dev.mappywall.core.WaterTransitPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiPredicate;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
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
    private static final double DISMOUNT_POSITION_EPSILON_SQR = 1.0E-4;
    private static final double VEHICLE_CONTACT_INFLATION = 1.0E-3;
    private static final double EGRESS_PROGRESS_EPSILON = 0.01;
    private static final int STUCK_TICKS_LIMIT = 90;
    private static final int COLLISION_REPLAN_TICKS = 8;
    private static final int MAX_MOVEMENT_RECOVERY_FAILURES = 12;
    private static final int LOCAL_STALL_TICKS = 40;
    private static final int LOOP_STALL_TICKS = 100;
    private static final double LOCAL_STALL_AREA_BLOCKS = 1.0;
    private static final double LOOP_STALL_AREA_BLOCKS = 4.0;
    private static final int PLACE_COOLDOWN_NORMAL_TICKS = 4;
    private static final int PLACE_COOLDOWN_AGGRESSIVE_TICKS = 1;
    private static final int PLACE_CONFIRM_TIMEOUT_TICKS = 30;
    private static final int BREAK_TIMEOUT_TICKS = 140;
    private static final int MAX_ACTION_FAILURES = 3;
    private static final double MAX_PLAN_START_DRIFT_SQR = 2.0;
    private static final int LOOKAHEAD_VALIDATION_STEPS = 3;
    private static final int MAX_BREAK_ACTIONS_PER_TARGET = 9;
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
    private static final NavigationPlanningCadence PLANNING_CADENCE =
            NavigationPlanningCadence.defaults();
    private static final Observation INACTIVE_DISMOUNT_OBSERVATION = new Observation(
            false, false, false, false, false, false, false, false, false
    );
    private static final ExecutorService PATH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MappyWall Path Planner");
        thread.setDaemon(true);
        return thread;
    });

    private final AutoNavigationConfig normalConfig = AutoNavigationConfig.defaults();
    private volatile AutoNavigationConfig aggressiveConfig;
    private final LocalPathPlanner pathPlanner = new LocalPathPlanner();
    private final NavigationFeetResolver navigationFeetResolver = new NavigationFeetResolver();
    private final BoatDismountRecovery dismountRecovery = new BoatDismountRecovery();
    private final BoatEgressSelector boatEgressSelector = new BoatEgressSelector();
    private final InitialPathPlanPreparer initialPathPlanPreparer = new InitialPathPlanPreparer();
    private final PathSegmentCoordinator<LocalPathPlanner.PathStep> pathSegments =
            new PathSegmentCoordinator<>();
    private final WaterTransitPolicy waterTransitPolicy = new WaterTransitPolicy();
    private final WaterRouteEvidenceAdapter waterRouteEvidenceAdapter = new WaterRouteEvidenceAdapter();
    private final BoatAcquisitionPolicy boatAcquisitionPolicy = new BoatAcquisitionPolicy();
    private final NavigationPlanningRetryState planningRetry =
            new NavigationPlanningRetryState(PLANNING_CADENCE);
    private final ArrayDeque<MovementSample> movementSamples = new ArrayDeque<>();

    private long currentStepOrdinal;
    private int stuckTicks;
    private int horizontalCollisionTicks;
    private int consecutiveNoPathFailures;
    private int placeCooldown;
    private int eatCooldown;
    private int elytraStartCooldown;
    private int fireworkCooldown;
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
    private BlockPos dismountEgress;
    private double lastDismountEgressDistance = Double.POSITIVE_INFINITY;
    private WaterTransitPolicy.TravelDecision currentWaterTravelDecision =
            WaterTransitPolicy.TravelDecision.SWIM;
    private BlockPos waterRunAnchor;
    private OptionalInt waterRunAnchorSurfaceY = OptionalInt.empty();
    private BlockPos waterReplanAnchor;
    private boolean waterReplanContinuityPending;
    private WaterRouteEvidenceAdapter.Evidence currentWaterEvidence =
            WaterRouteEvidenceAdapter.Evidence.none();
    private Set<Integer> boatPlacementBaseline = Set.of();
    private BlockPos pendingBoatPlacementSurface;
    private BlockPos boatSurfaceApproachTarget;
    private BlockPos lastBoatSurfaceApproachTarget;
    private double lastBoatSurfaceVerticalDistance = Double.MAX_VALUE;
    private int boatSurfaceApproachCandidateId;
    private int lastBoatSurfaceApproachCandidateId;
    private int routeBoatApproachStallTicks;
    private int routeBoatBackoffEntityId;
    private int routeBoatBackoffTicks;

    public MovementController() {
        this(AutoNavigationConfig.aggressiveDefaults());
    }

    MovementController(AutoNavigationConfig aggressiveConfig) {
        this.aggressiveConfig = Objects.requireNonNull(aggressiveConfig, "aggressiveConfig");
    }

    public void setAggressiveConfig(AutoNavigationConfig aggressiveConfig) {
        AutoNavigationConfig requestedConfig = Objects.requireNonNull(aggressiveConfig, "aggressiveConfig");
        boolean minimumBoatDistanceChanged = this.aggressiveConfig.minimumBoatDistanceBlocks()
                != requestedConfig.minimumBoatDistanceBlocks();
        this.aggressiveConfig = requestedConfig;
        consecutiveNoPathFailures = 0;
        if (minimumBoatDistanceChanged && automationStyle == AutomationStyle.AGGRESSIVE) {
            resetWaterTransit();
        }
        forceLocalReplan();
    }

    public MovementResult tick(Minecraft client, MapWallSave save, RouteStep target) {
        if (client.level == null || client.player == null || target == null) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        updateAutomationStyle(save.project().automationStyle());
        waitingForChunk = false;
        resetBreakBudgetIfTargetChanged(target);
        if (save.project().mode() == RunMode.AUTO_ELYTRA) {
            cancelDismountRecoveryForModeChange();
            return tickElytra(client, save, target);
        }

        if (save.project().mode() != RunMode.AUTO_WALK) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        LocalPlayer player = client.player;
        handleNavigationTargetChange(target);
        boolean recoveryWasActive = dismountRecovery.active();
        // Boarding suppression advances only on actual AUTO_WALK controller ticks.
        // Runtime target handling can freeze this clock safely because it cannot
        // attempt automatic boat boarding while the controller is not serviced.
        Action recoveryAction = dismountRecovery.tick(recoveryWasActive
                ? observeVehicleDismountRecovery(client, player, target)
                : INACTIVE_DISMOUNT_OBSERVATION);
        if (recoveryWasActive) {
            return serviceDismountRecovery(client, player, target, recoveryAction);
        }
        planningRetry.beginTick();
        if (arrivedAtNavigationTarget(player, target)) {
            if (shouldBeginVehicleDismountAtArrival(
                            player.isPassenger(), player.getVehicle() != null)
                    && beginVehicleDismountRecovery(client, player)) {
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
        pathSegments.promoteBuffered();
        startEligibleLookahead(client, target);
        startInitialCaptureIfNeeded(client, target);

        // Retire only already-reached path cells before deciding whether this is
        // the boat-to-land seam. This lets consecutive SWIM cells expose the first
        // non-SWIM step, but no step execution, progress, or stall work runs before
        // recovery begins and clears the path.
        LocalPathPlanner.PathStep waypoint = nextWaypoint(client, player);
        startEligibleLookahead(client, target);
        startInitialCaptureIfNeeded(client, target);
        if (waypoint == null) {
            stopForPlanningGap(client, player);
            if (isUnloadedAhead(client, player, target)) {
                waitingForChunk = true;
                consecutiveNoPathFailures = 0;
                return MovementResult.waiting(pathSnapshot());
            }
            if (consecutiveNoPathFailures >= MAX_ACTION_FAILURES) {
                release(client);
                resetProgress();
                return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_no_path"));
            }
            return MovementResult.active(pathSnapshot());
        }

        boolean swimWaypoint = waypoint.action() == LocalPathPlanner.StepAction.SWIM;
        Entity vehicle = player.getVehicle();
        boolean vehiclePresent = vehicle != null;
        boolean vehicleIsBoat = vehicle instanceof AbstractBoat;
        WaterRouteEvidenceAdapter.ResolvedSurface waypointSurface = swimWaypoint
                ? waterRouteEvidenceAdapter.resolveBoatableSurface(client, waypoint.pos()).orElse(null)
                : null;
        OptionalInt waypointSurfaceY = waypointSurface == null
                ? OptionalInt.empty()
                : OptionalInt.of(waypointSurface.waterPos().getY());
        OptionalInt boatSurfaceY = vehicle instanceof AbstractBoat boat
                ? waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat)
                : OptionalInt.empty();
        boolean boatableSwimRoute = swimWaypoint
                && vehicleIsBoat
                && isCompatibleResolvedBoatSurface(
                        waterTransitPolicy.surfaceY(), waypointSurfaceY, boatSurfaceY);
        if (shouldBeginVehicleDismountForWaypoint(
                        player.isPassenger(),
                        vehiclePresent,
                        swimWaypoint,
                        vehicleIsBoat,
                        boatableSwimRoute
                )
                && beginVehicleDismountRecovery(client, player)) {
            return MovementResult.active(pathSnapshot());
        }

        if (tryEat(client, player)) {
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

        if (client.gui.screen() != null
                && (waypoint.action() == LocalPathPlanner.StepAction.BREAK
                        || waypoint.action() == LocalPathPlanner.StepAction.PLACE)) {
            // Aggressive mode may keep travelling with a screen open, but world and
            // inventory transactions wait until the player closes it.
            stopMovement(client);
            return MovementResult.active(pathSnapshot());
        }

        consecutiveNoPathFailures = 0;
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
        planningRetry.forceFreshSnapshot();
        consecutiveNoPathFailures = 0;
        dismountRecovery.cancel();
        resetDismountEgressProgress(null, null);
        resetWaterTransit();
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
        dismountRecovery.reset();
        resetDismountEgressProgress(null, null);
    }

    public boolean isWaitingForChunk() {
        return waitingForChunk;
    }

    public boolean isPlanningPath() {
        return pendingCapture != null || pendingPlan != null;
    }

    public boolean isDismountRecovering() {
        return dismountRecovery.active();
    }

    private void updateAutomationStyle(AutomationStyle requestedStyle) {
        Objects.requireNonNull(requestedStyle, "requestedStyle");
        if (automationStyle != requestedStyle) {
            automationStyle = requestedStyle;
            resetWaterTransit();
            forceLocalReplan();
        }
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

    private boolean isContinuationTerrainReady(
            Minecraft client,
            RouteStep target,
            BlockPos seam
    ) {
        if (client.level == null || client.player == null) {
            return false;
        }

        BlockPos navigationTarget = navigationTarget(seam, target);
        for (PathSegmentCoordinator.Anchor column : NavigationTerrainProbe.targetDirectedColumns(
                anchor(seam),
                anchor(navigationTarget),
                4
        )) {
            BlockPos feet = blockPos(column);
            if (!client.level.hasChunkAt(feet)
                    || !client.level.hasChunkAt(feet.above())
                    || !client.level.hasChunkAt(feet.below())) {
                return false;
            }
        }
        return true;
    }

    private boolean isAdjacentActionStep(LocalPlayer player, LocalPathPlanner.PathStep step) {
        return isAdjacentActionStep(navigationFeetResolver.resolve(player), step);
    }

    private boolean isAdjacentActionStep(BlockPos current, LocalPathPlanner.PathStep step) {
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
        BlockPos currentFeet = navigationFeetResolver.resolve(player);
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
        int verticalDelta = feet.getY() - currentFeet.getY();
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

    private boolean isInitialPathPrefixLiveSafe(
            Minecraft client,
            BlockPos actualFeet,
            LocalPathPlanner.PathPlan plan
    ) {
        if (client.level == null || plan.steps().isEmpty()) {
            return false;
        }

        BlockPos cursor = actualFeet;
        int validationCount = Math.min(LOOKAHEAD_VALIDATION_STEPS, plan.steps().size());
        for (int index = 0; index < validationCount; index++) {
            LocalPathPlanner.PathStep step = plan.steps().get(index);
            if (isModifyingStep(step)) {
                return isAdjacentActionStep(cursor, step)
                        && client.level.hasChunkAt(step.pos())
                        && step.actionBlock() != null
                        && client.level.hasChunkAt(step.actionBlock());
            }
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

    private boolean trackStep(
            LocalPathPlanner.PathStep step,
            boolean suspendWaypointTimeout
    ) {
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
        if (!suspendWaypointTimeout) {
            activeStepTicks++;
        }
        if (step.action() == LocalPathPlanner.StepAction.BREAK && breakingBlock != null) {
            return activeStepTicks <= BREAK_TIMEOUT_TICKS;
        }
        if (step.action() == LocalPathPlanner.StepAction.PLACE && !actionAcknowledged) {
            return true;
        }
        return suspendWaypointTimeout || activeStepTicks <= STUCK_TICKS_LIMIT * 2;
    }

    public List<BlockPos> pathSnapshot() {
        return pathSegments.previewStepSnapshot().stream()
                .map(LocalPathPlanner.PathStep::pos)
                .toList();
    }

    private MovementResult executeStep(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        boolean continuingBoatSurfaceApproach = waypoint.action() == LocalPathPlanner.StepAction.SWIM
                && boatSurfaceApproachTarget != null;
        boatSurfaceApproachTarget = null;
        boatSurfaceApproachCandidateId = 0;
        if (!trackStep(waypoint, continuingBoatSurfaceApproach)) {
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
            boolean delegateVanillaGroundCollision = shouldDelegateAggressiveWalkCollisionToVanilla(
                    waypoint.action(),
                    player.onGround(),
                    player.isInWater(),
                    jump,
                    sneak,
                    dismountRecovery.active()
            );
            applyAggressiveGroundVelocity(
                    client,
                    player,
                    targetX - player.getX(),
                    targetZ - player.getZ(),
                    jump,
                    sneak,
                    sprint,
                    delegateVanillaGroundCollision
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

    private record RouteBoatCandidate(int entityId, BlockPos approachSurface) {}

    static boolean isAcceptedBoatCorridorColumn(
            BlockPos boatColumn,
            List<BlockPos> acceptedSurfaces
    ) {
        Objects.requireNonNull(boatColumn, "boatColumn");
        Objects.requireNonNull(acceptedSurfaces, "acceptedSurfaces");
        return acceptedSurfaces.contains(boatColumn);
    }

    static boolean isPreferredRouteBoatCandidate(
            boolean candidateReachable,
            double candidateDistanceSquared,
            boolean currentReachable,
            double currentDistanceSquared
    ) {
        if (candidateReachable != currentReachable) {
            return candidateReachable;
        }
        return candidateDistanceSquared < currentDistanceSquared;
    }

    static boolean isRouteBoatCandidateAvailable(
            int candidateId,
            int backedOffEntityId,
            int backoffTicks
    ) {
        return candidateId > 0
                && (backoffTicks <= 0 || candidateId != backedOffEntityId);
    }

    private List<BlockPos> acceptedWaterCorridorSurfaces() {
        ArrayList<BlockPos> accepted = new ArrayList<>();
        if (waterRunAnchor != null && waterRunAnchorSurfaceY.isPresent()) {
            accepted.add(new BlockPos(
                    waterRunAnchor.getX(),
                    waterRunAnchorSurfaceY.getAsInt(),
                    waterRunAnchor.getZ()
            ));
        }
        for (WaterRouteEvidenceAdapter.ResolvedSurface surface : currentWaterEvidence.surfaces()) {
            if (!accepted.contains(surface.waterPos())) {
                accepted.add(surface.waterPos());
            }
        }
        return List.copyOf(accepted);
    }

    private Optional<RouteBoatCandidate> findRouteAdjacentEligibleBoat(
            Minecraft client,
            LocalPlayer player
    ) {
        List<BlockPos> surfaces = acceptedWaterCorridorSurfaces();
        if (client.level == null || surfaces.isEmpty()) {
            return Optional.empty();
        }
        AABB searchBox = new AABB(surfaces.getFirst());
        for (int index = 1; index < surfaces.size(); index++) {
            searchBox = searchBox.minmax(new AABB(surfaces.get(index)));
        }
        List<AbstractBoat> candidates = client.level.getEntitiesOfClass(
                AbstractBoat.class,
                searchBox.inflate(1.0, 2.0, 1.0),
                boat -> isRouteBoatCandidateAvailable(
                                boat.getId(), routeBoatBackoffEntityId, routeBoatBackoffTicks)
                        && acceptedApproachSurfaceForBoat(client, boat).isPresent()
        );
        AbstractBoat selected = null;
        boolean selectedReachable = false;
        double selectedDistanceSquared = Double.MAX_VALUE;
        for (AbstractBoat candidate : candidates) {
            double candidateDistanceSquared = candidate.distanceToSqr(player);
            boolean candidateReachable = player.getEyePosition().distanceToSqr(candidate.position())
                    <= BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS
                    && player.hasLineOfSight(candidate);
            if (selected == null || isPreferredRouteBoatCandidate(
                    candidateReachable,
                    candidateDistanceSquared,
                    selectedReachable,
                    selectedDistanceSquared
            )) {
                selected = candidate;
                selectedReachable = candidateReachable;
                selectedDistanceSquared = candidateDistanceSquared;
            }
        }
        if (selected == null) {
            return Optional.empty();
        }
        return Optional.of(new RouteBoatCandidate(
                selected.getId(), acceptedApproachSurfaceForBoat(client, selected).orElseThrow()));
    }

    private Optional<BlockPos> acceptedApproachSurfaceForBoat(
            Minecraft client,
            int boatEntityId
    ) {
        if (client.level == null) {
            return Optional.empty();
        }
        Entity entity = client.level.getEntity(boatEntityId);
        return entity instanceof AbstractBoat boat
                ? acceptedApproachSurfaceForBoat(client, boat)
                : Optional.empty();
    }

    private Optional<BlockPos> acceptedApproachSurfaceForBoat(
            Minecraft client,
            AbstractBoat boat
    ) {
        if (!boat.isAlive()
                || !boat.getPassengers().isEmpty()
                || !shouldBoardBoat(boat.getId(), dismountRecovery::suppressBoarding)) {
            return Optional.empty();
        }
        OptionalInt runSurfaceY = waterTransitPolicy.surfaceY();
        OptionalInt boatSurfaceY = waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat);
        if (runSurfaceY.isEmpty()
                || boatSurfaceY.isEmpty()
                || runSurfaceY.getAsInt() != boatSurfaceY.getAsInt()) {
            return Optional.empty();
        }
        BlockPos boatColumn = BlockPos.containing(
                boat.getX(), runSurfaceY.getAsInt(), boat.getZ());
        List<BlockPos> acceptedSurfaces = acceptedWaterCorridorSurfaces();
        return isAcceptedBoatCorridorColumn(boatColumn, acceptedSurfaces)
                ? Optional.of(boatColumn)
                : Optional.empty();
    }

    private OptionalInt reachableBoatFromCandidate(
            Minecraft client,
            LocalPlayer player,
            Optional<RouteBoatCandidate> candidate
    ) {
        if (client.level == null || candidate.isEmpty()) {
            return OptionalInt.empty();
        }
        Entity entity = client.level.getEntity(candidate.orElseThrow().entityId());
        if (!(entity instanceof AbstractBoat boat)
                || !boat.isAlive()
                || !boat.getPassengers().isEmpty()
                || !shouldBoardBoat(boat.getId(), dismountRecovery::suppressBoarding)
                || player.getEyePosition().distanceToSqr(boat.position())
                        > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS
                || !player.hasLineOfSight(boat)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(boat.getId());
    }

    private boolean interactWithBoat(Minecraft client, LocalPlayer player, int selectedBoatId) {
        if (client.level == null || client.gameMode == null) {
            return false;
        }
        Entity entity = client.level.getEntity(selectedBoatId);
        if (!(entity instanceof AbstractBoat boat)
                || !selectedBoatRemainsEligible(client, boat)
                || player.getEyePosition().distanceToSqr(boat.position())
                        > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS
                || !player.hasLineOfSight(boat)) {
            return false;
        }
        InteractionResult result = client.gameMode.interact(
                player, boat, new EntityHitResult(boat), InteractionHand.MAIN_HAND);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private Optional<BlockPos> reachableBoatPlacementSurface(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        return currentWaterEvidence.surfaces().stream()
                .map(WaterRouteEvidenceAdapter.ResolvedSurface::waterPos)
                .filter(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos).add(0.0, 0.25, 0.0))
                        <= BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS)
                .findFirst();
    }

    private Set<Integer> captureLoadedBoatIds(Minecraft client) {
        if (client.level == null) {
            return Set.of();
        }
        HashSet<Integer> ids = new HashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof AbstractBoat boat && boat.isAlive()) {
                ids.add(boat.getId());
            }
        }
        return Set.copyOf(ids);
    }

    static boolean isNewPlacementBoat(int candidateId, Set<Integer> baseline, double distanceSquared) {
        Objects.requireNonNull(baseline, "baseline");
        return candidateId > 0
                && !baseline.contains(candidateId)
                && distanceSquared <= 9.0;
    }

    static boolean isConfirmedPlacementBoatEligible(
            int candidateId,
            Set<Integer> baseline,
            double distanceSquared,
            OptionalInt waterRunSurfaceY,
            OptionalInt placementSurfaceY,
            OptionalInt boatSurfaceY
    ) {
        return isNewPlacementBoat(candidateId, baseline, distanceSquared)
                && isCompatibleResolvedBoatSurface(
                        waterRunSurfaceY, placementSurfaceY, boatSurfaceY);
    }

    private OptionalInt findNewPlacementBoat(Minecraft client) {
        if (client.level == null || pendingBoatPlacementSurface == null) {
            return OptionalInt.empty();
        }
        Vec3 center = Vec3.atCenterOf(pendingBoatPlacementSurface);
        AbstractBoat nearest = client.level.getEntitiesOfClass(
                        AbstractBoat.class,
                        new AABB(pendingBoatPlacementSurface).inflate(3.0),
                        boat -> boat.isAlive()
                                && boat.getPassengers().isEmpty()
                                && shouldBoardBoat(
                                        boat.getId(), dismountRecovery::suppressBoarding)
                                && isConfirmedPlacementBoatEligible(
                                        boat.getId(),
                                        boatPlacementBaseline,
                                        boat.position().distanceToSqr(center),
                                        waterTransitPolicy.surfaceY(),
                                        OptionalInt.of(pendingBoatPlacementSurface.getY()),
                                        waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat)
                                )
                ).stream()
                .min((left, right) -> Double.compare(
                        left.position().distanceToSqr(center),
                        right.position().distanceToSqr(center)))
                .orElse(null);
        return nearest == null ? OptionalInt.empty() : OptionalInt.of(nearest.getId());
    }

    private boolean selectedBoatIsPresent(Minecraft client) {
        if (client.level == null || boatAcquisitionPolicy.selectedBoatId().isEmpty()) {
            return false;
        }
        int selectedBoatId = boatAcquisitionPolicy.selectedBoatId().getAsInt();
        Entity entity = client.level.getEntity(selectedBoatId);
        return entity instanceof AbstractBoat boat
                && selectedBoatRemainsEligible(client, boat);
    }

    private boolean selectedBoatRemainsEligible(Minecraft client, AbstractBoat boat) {
        if (!boat.isAlive()
                || !boat.getPassengers().isEmpty()
                || !shouldBoardBoat(boat.getId(), dismountRecovery::suppressBoarding)) {
            return false;
        }
        if (pendingBoatPlacementSurface == null) {
            return acceptedApproachSurfaceForBoat(client, boat).isPresent();
        }
        OptionalInt placementSurfaceY = OptionalInt.of(pendingBoatPlacementSurface.getY());
        OptionalInt boatSurfaceY = waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat);
        return isConfirmedPlacementBoatEligible(
                boat.getId(),
                boatPlacementBaseline,
                boat.position().distanceToSqr(Vec3.atCenterOf(pendingBoatPlacementSurface)),
                waterTransitPolicy.surfaceY(),
                placementSurfaceY,
                boatSurfaceY
        );
    }

    private MovementResult swimOrBoat(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        AutomationStyle style = currentAutomationStyle();
        return switch (currentWaterTravelDecision) {
            case SWIM -> {
                resetBoatAcquisition();
                yield swimToward(client, player, waypoint);
            }
            case ACQUIRE_BOAT -> acquireBoatOrSwim(client, player, waypoint, style);
            case CONTINUE_RIDING -> {
                resetBoatAcquisition();
                AbstractBoat boat = player.getVehicle() instanceof AbstractBoat currentBoat
                        ? currentBoat
                        : null;
                yield boat == null
                        ? swimToward(client, player, waypoint)
                        : driveBoatToward(client, player, boat, waypoint, style);
            }
        };
    }

    private MovementResult acquireBoatOrSwim(
            Minecraft client,
            LocalPlayer player,
            LocalPathPlanner.PathStep waypoint,
            AutomationStyle style
    ) {
        Optional<BlockPos> resolvedSurface = currentWaterEvidence.surfaces().stream()
                .map(WaterRouteEvidenceAdapter.ResolvedSurface::waterPos)
                .findFirst();
        Optional<BlockPos> placementSurface = reachableBoatPlacementSurface(player);
        boolean transactionAvailable = client.gameMode != null
                && client.gui.screen() == null
                && canSwapPlayerInventory(player);
        boolean heldBoat = player.getMainHandItem().getItem() instanceof BoatItem;
        boolean carriedBoat = findBoat(player) >= 0;
        Optional<RouteBoatCandidate> routeBoat = findRouteAdjacentEligibleBoat(client, player);
        OptionalInt nearbyBoat = transactionAvailable
                ? reachableBoatFromCandidate(client, player, routeBoat)
                : OptionalInt.empty();
        Optional<BlockPos> routeBoatSurface = routeBoat.map(RouteBoatCandidate::approachSurface);
        OptionalInt newBoat = findNewPlacementBoat(client);

        BoatAcquisitionPolicy.Decision decision = boatAcquisitionPolicy.tick(
                new BoatAcquisitionPolicy.Observation(
                        true,
                        transactionAvailable,
                        player.getVehicle() instanceof AbstractBoat,
                        heldBoat,
                        carriedBoat,
                        placementSurface.isPresent(),
                        routeBoat.isPresent(),
                        nearbyBoat,
                        newBoat,
                        selectedBoatIsPresent(client)
                )
        );

        if (boatAcquisitionPolicy.phase() == BoatAcquisitionPolicy.Phase.FALLBACK) {
            boatPlacementBaseline = Set.of();
            pendingBoatPlacementSurface = null;
        }

        switch (decision.action()) {
            case SELECT_CARRIED_BOAT -> {
                int slot = findBoat(player);
                if (slot >= 0) {
                    selectOrMoveToHotbar(client, player, slot);
                    boatAcquisitionPolicy.selectionRequested();
                }
            }
            case PLACE_HELD_BOAT -> {
                if (placementSurface.isPresent()) {
                    Set<Integer> baseline = captureLoadedBoatIds(client);
                    Optional<InteractionResult> attempt = useBoatItemAtWater(
                            client, player, placementSurface.orElseThrow(), style);
                    if (attempt.isPresent()) {
                        InteractionResult placementResult = attempt.orElseThrow();
                        boolean accepted = placementResult.consumesAction();
                        boatAcquisitionPolicy.placementResult(accepted);
                        if (accepted) {
                            boatPlacementBaseline = baseline;
                            pendingBoatPlacementSurface = placementSurface.orElseThrow();
                        } else {
                            boatPlacementBaseline = Set.of();
                            pendingBoatPlacementSurface = null;
                        }
                    }
                }
            }
            case BOARD_SELECTED_BOAT -> {
                int selectedBoatId = decision.boatEntityId().orElseThrow();
                boolean accepted = interactWithBoat(client, player, selectedBoatId);
                boatAcquisitionPolicy.boardingResult(selectedBoatId, accepted);
            }
            case NONE -> {
            }
        }

        if (player.getVehicle() instanceof AbstractBoat boat) {
            return driveBoatToward(client, player, boat, waypoint, style);
        }
        Optional<BlockPos> selectedBoatSurface = boatAcquisitionPolicy.selectedBoatId().isPresent()
                ? acceptedApproachSurfaceForBoat(
                        client, boatAcquisitionPolicy.selectedBoatId().getAsInt())
                : Optional.empty();
        Optional<BlockPos> holdSurface = selectedBoatSurface.isPresent()
                ? selectedBoatSurface
                : pendingBoatPlacementSurface != null
                        ? Optional.of(pendingBoatPlacementSurface)
                        : routeBoatSurface.isPresent() ? routeBoatSurface : resolvedSurface;
        boolean acquisitionResourceAvailable = heldBoat
                || carriedBoat
                || routeBoat.isPresent()
                || nearbyBoat.isPresent()
                || newBoat.isPresent();
        if (shouldHoldBoatAcquisitionSurface(
                boatAcquisitionPolicy.phase(),
                acquisitionResourceAvailable,
                holdSurface.isPresent()
        )) {
            OptionalInt routeBoatId = routeBoat.isPresent()
                    ? OptionalInt.of(routeBoat.orElseThrow().entityId())
                    : OptionalInt.empty();
            boatSurfaceApproachCandidateId = surfaceApproachCandidateId(
                    pendingBoatPlacementSurface != null,
                    boatAcquisitionPolicy.selectedBoatId(),
                    selectedBoatSurface.isPresent(),
                    routeBoatId,
                    routeBoatSurface.isPresent()
            );
            return swimTowardBoatSurface(client, player, holdSurface.orElseThrow());
        }
        return swimToward(client, player, waypoint);
    }

    static int surfaceApproachCandidateId(
            boolean pendingPlacement,
            OptionalInt selectedBoatId,
            boolean selectedSurfaceAvailable,
            OptionalInt routeBoatId,
            boolean routeSurfaceAvailable
    ) {
        Objects.requireNonNull(selectedBoatId, "selectedBoatId");
        Objects.requireNonNull(routeBoatId, "routeBoatId");
        if (selectedSurfaceAvailable && selectedBoatId.isPresent()) {
            return pendingPlacement ? 0 : selectedBoatId.getAsInt();
        }
        if (pendingPlacement) {
            return 0;
        }
        if (routeSurfaceAvailable && routeBoatId.isPresent()) {
            return routeBoatId.getAsInt();
        }
        return 0;
    }

    static boolean shouldHoldBoatAcquisitionSurface(
            BoatAcquisitionPolicy.Phase phase,
            boolean acquisitionResourceAvailable,
            boolean resolvedSurfaceAvailable
    ) {
        Objects.requireNonNull(phase, "phase");
        return resolvedSurfaceAvailable
                && phase != BoatAcquisitionPolicy.Phase.FALLBACK
                && (phase != BoatAcquisitionPolicy.Phase.IDLE || acquisitionResourceAvailable);
    }

    private MovementResult swimTowardBoatSurface(
            Minecraft client,
            LocalPlayer player,
            BlockPos surface
    ) {
        boatSurfaceApproachTarget = surface.immutable();
        return swimToward(
                client,
                player,
                new LocalPathPlanner.PathStep(
                        surface, LocalPathPlanner.StepAction.SWIM, null)
        );
    }

    static boolean madeBoatSurfaceVerticalProgress(
            BlockPos currentTarget,
            BlockPos previousTarget,
            double currentDistance,
            double previousDistance
    ) {
        return currentTarget != null
                && Objects.equals(currentTarget, previousTarget)
                && currentDistance < previousDistance - 0.01;
    }

    static double nextBoatSurfaceBestDistance(
            BlockPos currentTarget,
            BlockPos previousTarget,
            double currentDistance,
            double previousBestDistance
    ) {
        return Objects.equals(currentTarget, previousTarget)
                ? Math.min(currentDistance, previousBestDistance)
                : currentDistance;
    }

    static boolean isStableBoatSurfaceHold(
            BlockPos surfaceTarget,
            int candidateId,
            double horizontalDistance,
            double verticalDistance
    ) {
        return surfaceTarget != null
                && candidateId <= 0
                && horizontalDistance <= SWIM_WAYPOINT_DISTANCE_BLOCKS
                && verticalDistance <= 0.35;
    }

    static boolean shouldBackoffRouteBoat(
            int candidateId,
            boolean surfaceApproachActive,
            boolean madeProgress,
            int stalledTicks,
            int collisionTicks
    ) {
        return candidateId > 0
                && surfaceApproachActive
                && !madeProgress
                && (stalledTicks >= STUCK_TICKS_LIMIT
                        || collisionTicks >= COLLISION_REPLAN_TICKS);
    }

    static int nextRouteBoatApproachStallTicks(
            int candidateId,
            int previousCandidateId,
            boolean surfaceApproachActive,
            boolean madeProgress,
            int previousStallTicks
    ) {
        if (candidateId <= 0 || !surfaceApproachActive || madeProgress) {
            return 0;
        }
        return candidateId == previousCandidateId ? previousStallTicks + 1 : 1;
    }

    private void startRouteBoatBackoff(int candidateId) {
        routeBoatBackoffEntityId = candidateId;
        routeBoatBackoffTicks = BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS;
        lastBoatSurfaceApproachCandidateId = 0;
        routeBoatApproachStallTicks = 0;
    }

    private MovementResult swimToward(
            Minecraft client,
        LocalPlayer player,
        LocalPathPlanner.PathStep waypoint
    ) {
        if (!player.isInWater()) {
            boolean enteringAbove = waypoint.pos().getY() > navigationFeetResolver.resolve(player).getY();
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

    private Optional<InteractionResult> useBoatItemAtWater(
            Minecraft client,
            LocalPlayer player,
            BlockPos waterPos,
            AutomationStyle style
    ) {
        if (client.gameMode == null) {
            return Optional.of(InteractionResult.FAIL);
        }
        Vec3 hit = Vec3.atCenterOf(waterPos).add(0.0, 0.25, 0.0);
        if (style != AutomationStyle.AGGRESSIVE) {
            float yawError = faceMovement(
                    player, hit.x - player.getX(), hit.z - player.getZ());
            float lookError = face(
                    player,
                    hit.x - player.getX(),
                    hit.y - player.getEyeY(),
                    hit.z - player.getZ(),
                    true
            );
            if (Math.max(yawError, lookError) > SPRINT_ALIGNMENT_DEGREES) {
                return Optional.empty();
            }
            InteractionResult result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            if (result.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
            return Optional.of(result);
        }

        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        float[] look = lookAngles(player, hit);
        InteractionResult result;
        try {
            sendServerLook(player, look[0], look[1]);
            player.setYRot(look[0]);
            player.setXRot(look[1]);
            result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            if (result.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
        return Optional.of(result);
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
        planningRetry.forceFreshSnapshot();
        consecutiveNoPathFailures = 0;
        breakingBlock = null;
        pendingPlacementBlock = null;
        pendingPlacementTicks = 0;
        activeStepSignature = null;
        activeStepTicks = 0;
        actionAcknowledged = false;
        dropCommitted = false;
        resetWaterTransit();
    }

    private void advancePendingCapture() {
        if (pendingCapture == null || pendingPlanningRequest == null) {
            return;
        }
        if (!pendingCapture.advance(PLANNING_CADENCE.snapshotColumnsPerTick())) {
            return;
        }

        LocalPathPlanner.NavigationSnapshot snapshot = pendingCapture.finish();
        PlanningRequest request = pendingPlanningRequest;
        pendingCapture = null;
        pendingPlan = PATH_EXECUTOR.submit(
                () -> pathPlanner.plan(
                        snapshot,
                        request.target(),
                        request.config(),
                        request.continuationContext()
                )
        );
    }

    private void startEligibleLookahead(Minecraft client, RouteStep target) {
        int remaining = pathSegments.remainingSteps();
        if (client.level == null
                || pendingPlanningRequest != null
                || pendingCapture != null
                || pendingPlan != null
                || pathSegments.hasBuffered()
                || !planningRetry.canSubmit()
                || remaining < 1
                || remaining > PLANNING_CADENCE.lookaheadRemainingSteps()) {
            return;
        }

        List<LocalPathPlanner.PathStep> suffix = pathSegments.remainingStepSnapshot();
        boolean suffixStable = suffix.stream()
                .noneMatch(this::isModifyingStep);
        PathSegmentCoordinator.ContinuationCandidate candidate =
                pathSegments.continuationCandidate().orElse(null);
        if (candidate == null) {
            return;
        }
        BlockPos seam = blockPos(candidate.seam());
        ContinuationContext continuationContext = ContinuationContext.fromSuffix(suffix, seam);
        boolean terrainReady = candidate.policy()
                == PathSegmentCoordinator.ContinuationPolicy.WHEN_TERRAIN_READY
                && isContinuationTerrainReady(client, target, seam);
        pathSegments.beginLookahead(suffixStable, terrainReady).ifPresent(request -> {
            beginCapture(
                    client,
                    target,
                    seam,
                    PlanRequestKind.LOOKAHEAD,
                    request,
                    continuationContext
            );
        });
    }

    private void startInitialCaptureIfNeeded(Minecraft client, RouteStep target) {
        if (client.player == null
                || client.level == null
                || pendingPlanningRequest != null
                || pendingCapture != null
                || pendingPlan != null
                || pathSegments.remainingSteps() > 0
                || pathSegments.hasBuffered()
                || !planningRetry.canSubmit()) {
            return;
        }

        beginCapture(
                client,
                target,
                navigationFeetResolver.resolve(client.player),
                PlanRequestKind.INITIAL,
                null,
                ContinuationContext.none()
        );
    }

    private void beginCapture(
            Minecraft client,
            RouteStep target,
            BlockPos plannedStart,
            PlanRequestKind kind,
            PathSegmentCoordinator.LookaheadRequest lookaheadRequest,
            ContinuationContext continuationContext
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
                lookaheadRequest,
                continuationContext
        );
        pendingCapture = capture;
    }

    private void acceptCompletedPlan(Minecraft client, LocalPlayer player, RouteStep target) {
        if (pendingPlan == null || !pendingPlan.isDone() || pendingPlanningRequest == null) {
            return;
        }

        PlanningRequest request = pendingPlanningRequest;
        boolean requestCurrent = isCurrentPlanningRequest(request, target);
        try {
            LocalPathPlanner.PathPlan plan = pendingPlan.get();
            if (!requestCurrent) {
                rejectPlanningRequest(request, PlanningFailure.STALE);
            } else if (plan.outcome() == LocalPathPlanner.PathOutcome.NO_PATH) {
                rejectPlanningRequest(request, PlanningFailure.NO_PATH);
            } else if (plan.outcome() == LocalPathPlanner.PathOutcome.NODE_LIMIT && plan.isEmpty()) {
                rejectPlanningRequest(request, PlanningFailure.NODE_LIMIT);
            } else if (request.kind() == PlanRequestKind.INITIAL) {
                acceptInitialPlan(client, player, request, plan);
            } else {
                acceptLookaheadPlan(client, request, plan);
            }
        } catch (CancellationException exception) {
            rejectPlanningCompletionException(request, requestCurrent);
        } catch (ExecutionException exception) {
            rejectPlanningCompletionException(request, requestCurrent);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rejectPlanningCompletionException(request, requestCurrent);
        } finally {
            pendingPlan = null;
            pendingPlanningRequest = null;
        }
    }

    private boolean isCurrentPlanningRequest(PlanningRequest request, RouteStep target) {
        return request.generation() == planGeneration
                && request.targetSignature().equals(targetSignature)
                && request.targetSignature().equals(navigationSignature(target))
                && request.config().equals(navigationConfig());
    }

    InitialPlanAcceptance evaluateInitialPlanAcceptance(
            Supplier<BlockPos> actualFeetSupplier,
            BlockPos requestPlannedStart,
            LocalPathPlanner.PathPlan plan,
            BiPredicate<BlockPos, LocalPathPlanner.PathPlan> livePrefixValidator
    ) {
        Objects.requireNonNull(actualFeetSupplier, "actualFeetSupplier");
        Objects.requireNonNull(requestPlannedStart, "requestPlannedStart");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(livePrefixValidator, "livePrefixValidator");

        BlockPos actualFeet = Objects.requireNonNull(actualFeetSupplier.get(), "actualFeet");
        boolean startStillCurrent = requestPlannedStart
                .distSqr(actualFeet) <= MAX_PLAN_START_DRIFT_SQR;
        if (!startStillCurrent) {
            return InitialPlanAcceptance.rejected(PlanningFailure.LIVE_INVALIDATED);
        }
        if (!plan.isExecutable()) {
            return InitialPlanAcceptance.rejected(PlanningFailure.INVALID_PLAN);
        }

        InitialPathPlanPreparer.PreparedInitialPath prepared = initialPathPlanPreparer
                .prepare(actualFeet, plan)
                .orElse(null);
        if (prepared == null
                || !livePrefixValidator.test(actualFeet, prepared.plan())) {
            return InitialPlanAcceptance.rejected(PlanningFailure.INITIAL_PREFIX);
        }
        return InitialPlanAcceptance.accepted(prepared.plan());
    }

    private void acceptInitialPlan(
            Minecraft client,
            LocalPlayer player,
            PlanningRequest request,
            LocalPathPlanner.PathPlan plan
    ) {
        InitialPlanAcceptance acceptance = evaluateInitialPlanAcceptance(
                () -> navigationFeetResolver.resolve(player),
                request.plannedStart(),
                plan,
                (actualFeet, preparedPlan) -> isInitialPathPrefixLiveSafe(
                        client,
                        actualFeet,
                        preparedPlan
                )
        );
        if (acceptance.failure() != null) {
            rejectPlanningRequest(request, acceptance.failure());
            return;
        }

        pathSegments.installInitial(toSegment(acceptance.plan()));
        currentStepOrdinal = 0;
        planningRetry.onSuccess();
        breakingBlock = null;
        activeStepSignature = null;
        activeStepTicks = 0;
        consecutiveNoPathFailures = 0;
    }

    private void acceptLookaheadPlan(
            Minecraft client,
            PlanningRequest request,
            LocalPathPlanner.PathPlan plan
    ) {
        PathSegmentCoordinator.LookaheadRequest lookaheadRequest = request.lookaheadRequest();
        boolean exactSeam = lookaheadRequest != null
                && plan.plannedStart().equals(blockPos(lookaheadRequest.seam()));
        if (!exactSeam || !plan.isExecutable()) {
            rejectPlanningRequest(request, PlanningFailure.INVALID_PLAN);
            return;
        }
        if (!isLookaheadContinuationSafe(client, lookaheadRequest, plan)) {
            rejectPlanningRequest(request, PlanningFailure.LIVE_INVALIDATED);
            return;
        }
        if (!pathSegments.acceptLookahead(lookaheadRequest, toSegment(plan))) {
            rejectPlanningRequest(request, PlanningFailure.STALE);
            return;
        }
        planningRetry.onSuccess();
        consecutiveNoPathFailures = 0;
    }

    private void rejectPlanningRequest(PlanningRequest request, PlanningFailure failure) {
        failPendingLookahead(request);
        switch (failure) {
            case STALE, LIVE_INVALIDATED -> planningRetry.forceFreshSnapshot();
            case NO_PATH -> {
                consecutiveNoPathFailures++;
                planningRetry.onNoPath();
            }
            case NODE_LIMIT -> planningRetry.onNodeLimit();
            case INVALID_PLAN -> planningRetry.onInvalidPlan();
            case INITIAL_PREFIX -> planningRetry.onInvalidInitialPrefix();
        }
    }

    private void rejectPlanningCompletionException(PlanningRequest request, boolean requestCurrent) {
        failPendingLookahead(request);
        planningRetry.onCompletionException(requestCurrent);
    }

    private void failPendingLookahead(PlanningRequest request) {
        if (request.lookaheadRequest() != null) {
            pathSegments.failLookahead(request.lookaheadRequest());
        }
    }

    private PathSegmentCoordinator.Segment<LocalPathPlanner.PathStep> toSegment(
            LocalPathPlanner.PathPlan plan
    ) {
        boolean modifying = plan.steps().stream().anyMatch(this::isModifyingStep);
        PathSegmentCoordinator.ContinuationPolicy continuationPolicy;
        if (modifying || plan.outcome() == LocalPathPlanner.PathOutcome.REACHED_TARGET) {
            continuationPolicy = PathSegmentCoordinator.ContinuationPolicy.NONE;
        } else if (plan.outcome() == LocalPathPlanner.PathOutcome.SAFE_FRONTIER) {
            continuationPolicy = PathSegmentCoordinator.ContinuationPolicy.IMMEDIATE;
        } else if (plan.outcome() == LocalPathPlanner.PathOutcome.UNLOADED_FRONTIER) {
            continuationPolicy = PathSegmentCoordinator.ContinuationPolicy.WHEN_TERRAIN_READY;
        } else {
            continuationPolicy = PathSegmentCoordinator.ContinuationPolicy.NONE;
        }
        PreviewPolicy previewPolicy = plan.usedRetreatFallback()
                ? PreviewPolicy.AFTER_PROMOTION
                : PreviewPolicy.CONTINUOUS;
        return new PathSegmentCoordinator.Segment<>(
                anchor(plan.plannedStart()),
                anchor(plan.plannedEnd()),
                plan.steps(),
                plan.outcome() == LocalPathPlanner.PathOutcome.REACHED_TARGET,
                continuationPolicy,
                previewPolicy
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

    private LocalPathPlanner.PathStep nextWaypoint(Minecraft client, LocalPlayer player) {
        while (true) {
            LocalPathPlanner.PathStep step = pathSegments.currentStepOrPromote().orElse(null);
            if (step == null) {
                return null;
            }
            if (step.action() != LocalPathPlanner.StepAction.SWIM) {
                leaveWaterRun();
            } else {
                currentWaterTravelDecision = refreshWaterEvidence(client, player);
            }
            if (step.action() == LocalPathPlanner.StepAction.BREAK
                    || step.action() == LocalPathPlanner.StepAction.PLACE) {
                return step;
            }
            if (isAtWaypoint(client, player, step)) {
                boolean advanced = advancePathStep();
                if (advanced && step.action() == LocalPathPlanner.StepAction.SWIM) {
                    recordCompletedSwimStep(client, player, step);
                }
                continue;
            }
            return step;
        }
    }

    private void recordCompletedSwimStep(
            Minecraft client,
            LocalPlayer player,
            LocalPathPlanner.PathStep step
    ) {
        WaterRouteEvidenceAdapter.ResolvedSurface resolved = waterRouteEvidenceAdapter
                .resolveBoatableSurface(client, step.pos())
                .orElse(null);
        if (resolved == null) {
            leaveWaterRun();
            return;
        }
        BlockPos anchor = waterRunAnchor == null
                ? navigationFeetResolver.resolve(player)
                : waterRunAnchor;
        int dx = Math.abs(step.pos().getX() - anchor.getX());
        int dz = Math.abs(step.pos().getZ() - anchor.getZ());
        boolean knownLoadedNonWaterAnchor = client.level != null
                && client.level.hasChunkAt(anchor)
                && !client.level.getFluidState(anchor).is(FluidTags.WATER);
        if (dx <= 1
                && dz <= 1
                && dx + dz > 0
                && shouldCountCompletedWaterEdge(
                        waterRunAnchorSurfaceY,
                        resolved.waterPos().getY(),
                        knownLoadedNonWaterAnchor
                )) {
            waterTransitPolicy.recordCompletedEdge(
                    resolved.waterPos().getY(), Math.hypot(dx, dz));
        }
        waterRunAnchor = step.pos();
        waterRunAnchorSurfaceY = OptionalInt.of(resolved.waterPos().getY());
    }

    static boolean shouldCountCompletedWaterEdge(
            OptionalInt anchorSurfaceY,
            int completedSurfaceY,
            boolean knownLoadedNonWaterAnchor
    ) {
        Objects.requireNonNull(anchorSurfaceY, "anchorSurfaceY");
        return anchorSurfaceY.isPresent()
                ? anchorSurfaceY.getAsInt() == completedSurfaceY
                : knownLoadedNonWaterAnchor;
    }

    private WaterTransitPolicy.TravelDecision refreshWaterEvidence(
            Minecraft client,
            LocalPlayer player
    ) {
        if (waterRunAnchor == null) {
            BlockPos candidate = navigationFeetResolver.resolve(player);
            OptionalInt candidateSurfaceY = waterRouteEvidenceAdapter
                    .resolveBoatableSurface(client, candidate)
                    .map(surface -> OptionalInt.of(surface.waterPos().getY()))
                    .orElseGet(OptionalInt::empty);
            if (waterReplanContinuityPending
                    && !canPreserveWaterRunAcrossReplan(
                            waterReplanAnchor,
                            candidate,
                            waterTransitPolicy.surfaceY(),
                            candidateSurfaceY
                    )) {
                leaveWaterRun();
            }
            waterRunAnchor = candidate;
            waterRunAnchorSurfaceY = candidateSurfaceY;
            waterReplanAnchor = null;
            waterReplanContinuityPending = false;
        }

        double remainingProof = Math.max(
                1.0e-9,
                navigationConfig().minimumBoatDistanceBlocks()
                        - waterTransitPolicy.completedDistanceBlocks()
        );
        currentWaterEvidence = waterRouteEvidenceAdapter.collect(
                client,
                waterRunAnchor,
                pathSegments.previewStepSnapshot(),
                remainingProof
        );
        return waterTransitPolicy.observe(new WaterTransitPolicy.RunObservation(
                currentWaterEvidence.surfaceY(),
                currentWaterEvidence.futureConfirmedDistanceBlocks(),
                navigationConfig().minimumBoatDistanceBlocks(),
                player.getVehicle() instanceof AbstractBoat
        ));
    }

    static boolean canPreserveWaterRunAcrossReplan(
            BlockPos previousAnchor,
            BlockPos currentAnchor,
            OptionalInt previousSurfaceY,
            OptionalInt currentSurfaceY
    ) {
        if (previousAnchor == null || currentAnchor == null) {
            return false;
        }
        Objects.requireNonNull(previousSurfaceY, "previousSurfaceY");
        Objects.requireNonNull(currentSurfaceY, "currentSurfaceY");
        int dx = Math.abs(previousAnchor.getX() - currentAnchor.getX());
        int dz = Math.abs(previousAnchor.getZ() - currentAnchor.getZ());
        return dx <= 1
                && dz <= 1
                && previousSurfaceY.isPresent()
                && currentSurfaceY.isPresent()
                && previousSurfaceY.getAsInt() == currentSurfaceY.getAsInt();
    }

    private boolean isAtWaypoint(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep step) {
        BlockPos pos = step.pos();
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        AbstractBoat boat = player.getVehicle() instanceof AbstractBoat currentBoat
                ? currentBoat
                : null;
        OptionalInt waypointSurfaceY = step.action() == LocalPathPlanner.StepAction.SWIM
                ? waterRouteEvidenceAdapter.resolveBoatableSurface(client, pos)
                        .map(surface -> OptionalInt.of(surface.waterPos().getY()))
                        .orElseGet(OptionalInt::empty)
                : OptionalInt.empty();
        OptionalInt boatSurfaceY = boat == null
                ? OptionalInt.empty()
                : waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat);
        boolean compatibleBoatableSurface = isCompatibleResolvedBoatSurface(
                waterTransitPolicy.surfaceY(), waypointSurfaceY, boatSurfaceY);
        if (step.action() == LocalPathPlanner.StepAction.SWIM && boat != null) {
            return isResolvedBoatSwimWaypointComplete(
                    horizontalDistance, true, compatibleBoatableSurface);
        }
        return evaluateWaypointCompletion(
                step.action(),
                horizontalDistance,
                player.getY(),
                pos.getY(),
                player.onGround(),
                player.isInWater(),
                player.getVehicle() instanceof AbstractBoat,
                () -> navigationFeetResolver.resolveWaypointY(player)
        );
    }

    static boolean isCompatibleResolvedBoatSurface(
            OptionalInt waterRunSurfaceY,
            OptionalInt waypointSurfaceY,
            OptionalInt boatSurfaceY
    ) {
        Objects.requireNonNull(waterRunSurfaceY, "waterRunSurfaceY");
        Objects.requireNonNull(waypointSurfaceY, "waypointSurfaceY");
        Objects.requireNonNull(boatSurfaceY, "boatSurfaceY");
        return waterRunSurfaceY.isPresent()
                && waypointSurfaceY.isPresent()
                && boatSurfaceY.isPresent()
                && waterRunSurfaceY.getAsInt() == waypointSurfaceY.getAsInt()
                && waypointSurfaceY.getAsInt() == boatSurfaceY.getAsInt();
    }

    static boolean isResolvedBoatSwimWaypointComplete(
            double horizontalDistance,
            boolean inBoat,
            boolean compatibleBoatableSurface
    ) {
        return inBoat
                && compatibleBoatableSurface
                && horizontalDistance <= SWIM_WAYPOINT_DISTANCE_BLOCKS;
    }

    static boolean evaluateWaypointCompletion(
            LocalPathPlanner.StepAction action,
            double horizontalDistance,
            double physicalFeetY,
            int targetY,
            boolean onGround,
            boolean inWater,
            boolean inBoat,
            DoubleSupplier selectedMovementYResolver
    ) {
        double selectedMovementY = selectedMovementYResolver.getAsDouble();
        return isWaypointComplete(
                action,
                horizontalDistance,
                physicalFeetY,
                selectedMovementY,
                targetY,
                onGround,
                inWater,
                inBoat
        );
    }

    static boolean isWaypointComplete(
            LocalPathPlanner.StepAction action,
            double horizontalDistance,
            double physicalFeetY,
            double selectedMovementY,
            int targetY,
            boolean onGround,
            boolean inWater,
            boolean inBoat
    ) {
        double movementYError = selectedMovementY - targetY;
        double physicalYError = physicalFeetY - targetY;
        return switch (action) {
            case WALK -> horizontalDistance <= WALK_WAYPOINT_DISTANCE_BLOCKS
                    && Math.abs(movementYError) <= 0.60
                    && (onGround || inWater);
            case JUMP -> horizontalDistance <= JUMP_WAYPOINT_DISTANCE_BLOCKS
                    && movementYError >= -0.15
                    && movementYError <= 0.70
                    && onGround;
            case DROP -> horizontalDistance <= DROP_WAYPOINT_DISTANCE_BLOCKS
                    && Math.abs(movementYError) <= 0.65
                    && (onGround || inWater);
            case SWIM -> horizontalDistance <= SWIM_WAYPOINT_DISTANCE_BLOCKS
                    && Math.abs(physicalYError) <= 1.25
                    && (inWater || inBoat);
            case BREAK, PLACE -> false;
        };
    }

    private boolean advancePathStep() {
        boolean advanced = pathSegments.advance();
        if (advanced) {
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
        return advanced;
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
        double boatSurfaceVerticalDistance = boatSurfaceApproachTarget == null
                ? Double.MAX_VALUE
                : Math.abs(boatSurfaceApproachTarget.getY() + 0.5 - player.getY());
        double boatSurfaceHorizontalDistance = boatSurfaceApproachTarget == null
                ? Double.MAX_VALUE
                : Math.sqrt(squaredHorizontalDistance(player, boatSurfaceApproachTarget));
        boolean boatSurfaceProgress = madeBoatSurfaceVerticalProgress(
                boatSurfaceApproachTarget,
                lastBoatSurfaceApproachTarget,
                boatSurfaceVerticalDistance,
                lastBoatSurfaceVerticalDistance
        );
        boolean surfaceApproachActive = boatSurfaceApproachTarget != null;
        boolean stableSurfaceHold = isStableBoatSurfaceHold(
                boatSurfaceApproachTarget,
                boatSurfaceApproachCandidateId,
                boatSurfaceHorizontalDistance,
                boatSurfaceVerticalDistance
        );
        madeProgress = madeProgress || boatSurfaceProgress;
        double nextBoatSurfaceBestDistance = nextBoatSurfaceBestDistance(
                boatSurfaceApproachTarget,
                lastBoatSurfaceApproachTarget,
                boatSurfaceVerticalDistance,
                lastBoatSurfaceVerticalDistance
        );
        lastBoatSurfaceApproachTarget = boatSurfaceApproachTarget;
        lastBoatSurfaceVerticalDistance = nextBoatSurfaceBestDistance;
        if (madeProgress || stableSurfaceHold) {
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
        if (!stableSurfaceHold
                && movementAction
                && player.horizontalCollision
                && playerMoved <= PLAYER_MOVE_EPSILON) {
            horizontalCollisionTicks++;
        } else {
            horizontalCollisionTicks = 0;
        }
        if (movementAction && !surfaceApproachActive) {
            recordMovementSample(playerPos);
        } else {
            movementSamples.clear();
        }

        routeBoatApproachStallTicks = nextRouteBoatApproachStallTicks(
                boatSurfaceApproachCandidateId,
                lastBoatSurfaceApproachCandidateId,
                surfaceApproachActive,
                madeProgress,
                routeBoatApproachStallTicks
        );
        lastBoatSurfaceApproachCandidateId = boatSurfaceApproachCandidateId;

        if (shouldBackoffRouteBoat(
                boatSurfaceApproachCandidateId,
                surfaceApproachActive,
                madeProgress,
                routeBoatApproachStallTicks,
                horizontalCollisionTicks
        )) {
            startRouteBoatBackoff(boatSurfaceApproachCandidateId);
            stuckTicks = 0;
            horizontalCollisionTicks = 0;
            boatSurfaceApproachTarget = null;
            boatSurfaceApproachCandidateId = 0;
            return;
        }

        if (horizontalCollisionTicks >= COLLISION_REPLAN_TICKS
                || stuckTicks >= STUCK_TICKS_LIMIT
                || (!surfaceApproachActive
                        && movementAction
                        && isTrappedInRecentArea(LOCAL_STALL_TICKS, LOCAL_STALL_AREA_BLOCKS))
                || (!surfaceApproachActive
                        && movementAction
                        && isTrappedInRecentArea(LOOP_STALL_TICKS, LOOP_STALL_AREA_BLOCKS))) {
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
        planningRetry.forceFreshSnapshot();
        consecutiveNoPathFailures = 0;
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
        if (!waterReplanContinuityPending && waterTransitPolicy.surfaceY().isPresent()) {
            waterReplanAnchor = waterRunAnchor;
            waterReplanContinuityPending = true;
        }
        waterRunAnchor = null;
        waterRunAnchorSurfaceY = OptionalInt.empty();
        currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
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
        if (client.player != null) {
            client.player.input.keyPresses = releasedPlayerInput(
                    lastDirectInput.equals(DirectInput.NEUTRAL),
                    client.player.input.keyPresses
            );
        }
        sendNeutralInput(client, false);
    }

    static Input releasedPlayerInput(boolean cachedNeutral, Input currentInput) {
        Objects.requireNonNull(currentInput, "currentInput");
        return Input.EMPTY;
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

    private void stopForPlanningGap(Minecraft client, LocalPlayer player) {
        stopMovement(client);
        Vec3 currentVelocity = player.getDeltaMovement();
        Vec3 safeVelocity = planningGapVelocity(currentAutomationStyle(), currentVelocity);
        if (safeVelocity != currentVelocity) {
            player.setDeltaMovement(safeVelocity);
        }
    }

    static Vec3 planningGapVelocity(AutomationStyle style, Vec3 currentVelocity) {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        if (style != AutomationStyle.AGGRESSIVE) {
            return currentVelocity;
        }
        return new Vec3(0.0, currentVelocity.y, 0.0);
    }

    static boolean shouldDelegateAggressiveWalkCollisionToVanilla(
            LocalPathPlanner.StepAction action,
            boolean onGround,
            boolean inWater,
            boolean jump,
            boolean sneak,
            boolean dismountRecovering
    ) {
        Objects.requireNonNull(action, "action");
        return action == LocalPathPlanner.StepAction.WALK
                && onGround
                && !inWater
                && !jump
                && !sneak
                && !dismountRecovering;
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
        applyAggressiveGroundVelocity(client, player, dx, dz, jump, sneak, sprint, false);
    }

    private void applyAggressiveGroundVelocity(
            Minecraft client,
            LocalPlayer player,
            double dx,
            double dz,
            boolean jump,
            boolean sneak,
            boolean sprint,
            boolean delegateVanillaGroundCollision
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
        if (!delegateVanillaGroundCollision
                && !jump
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
        return isProjectedSupportSafe(
                entity.getX() + velocityX,
                entity.getZ() + velocityZ,
                (x, z) -> navigationFeetResolver.resolveProjected(player, x, z),
                feet -> isLiveBodyClear(client, feet),
                support -> isSafeSolidSupport(client, support)
        );
    }

    static boolean isProjectedSupportSafe(
            double projectedX,
            double projectedZ,
            ProjectedFeetResolver feetResolver,
            Predicate<BlockPos> isBodyClear,
            Predicate<BlockPos> isSafeSupport
    ) {
        BlockPos projectedFeet = feetResolver.resolve(projectedX, projectedZ);
        return isBodyClear.test(projectedFeet)
                && isSafeSupport.test(projectedFeet.below());
    }

    @FunctionalInterface
    interface ProjectedFeetResolver {
        BlockPos resolve(double x, double z);
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

    private void cancelDismountRecoveryForModeChange() {
        dismountRecovery.cancel();
        resetDismountEgressProgress(null, null);
    }

    private boolean beginVehicleDismountRecovery(Minecraft client, LocalPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return false;
        }

        DismountLifecycleEffects effects = dismountLifecycleEffects(true, Action.NONE, true);
        dismountRecovery.begin(vehicle.getId(), player.getX(), player.getY(), player.getZ());
        if (effects.clearPlanning()) {
            clearPlanningForRecovery();
        }
        resetDismountEgressProgress(null, null);
        releaseVehicleControls(client);
        releaseDirectMovementState(client);
        releaseUseKey(client);
        clearVanillaMovementKeys(client);
        Vec3 velocity = player.getDeltaMovement();
        player.setDeltaMovement(0.0, velocity.y, 0.0);
        keepDismountShiftLocally(client, player);
        return effects.returnImmediately();
    }

    private MovementResult serviceDismountRecovery(
            Minecraft client,
            LocalPlayer player,
            RouteStep target,
            Action action
    ) {
        DismountLifecycleEffects effects = dismountLifecycleEffects(false, action, player.isPassenger());
        switch (effects.inputMode()) {
            case REQUEST_SHIFT -> requestServerDismount(client, player);
            case KEEP_SHIFT -> keepDismountShiftLocally(client, player);
            case NEUTRAL -> {
                if (effects.motion() == DismountMotion.NONE) {
                    stopMovement(client);
                    releaseUseKey(client);
                } else {
                    prepareDismountMotion(client);
                }
            }
        }

        if (effects.motion() == DismountMotion.STEER) {
            BlockPos egress = revalidateDismountEgress(client, player, target);
            if (egress != null) {
                moveToward(
                        client,
                        player,
                        new LocalPathPlanner.PathStep(egress, LocalPathPlanner.StepAction.WALK, null),
                        false
                );
            } else {
                sendNeutralInput(client, false);
            }
        } else if (effects.motion() == DismountMotion.JUMP) {
            BlockPos egress = revalidateDismountEgress(client, player, target);
            if (egress != null) {
                moveToward(
                        client,
                        player,
                        new LocalPathPlanner.PathStep(egress, LocalPathPlanner.StepAction.JUMP, null),
                        true
                );
            } else {
                sendNeutralInput(client, false);
            }
        }

        if (effects.freshReplan() || effects.pauseStuck()) {
            resetDismountEgressProgress(null, null);
        }
        DismountTerminalOutcome terminalOutcome =
                applyDismountTerminalEffects(effects, this::forceLocalReplan);
        if (terminalOutcome.pauseStuck()) {
            return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_stuck"));
        }
        return MovementResult.active(pathSnapshot());
    }

    private void prepareDismountMotion(Minecraft client) {
        releaseVehicleControls(client);
        releaseDirectMovementState(client);
        releaseUseKey(client);
        clearVanillaMovementKeys(client);
        if (client.player != null) {
            client.player.input.keyPresses = Input.EMPTY;
        }
    }

    private void requestServerDismount(Minecraft client, LocalPlayer player) {
        releaseVehicleControls(client);
        releaseDirectMovementState(client);
        releaseUseKey(client);
        clearVanillaMovementKeys(client);
        sendPlayerInput(client, false, false, false, false, false, true, false);
        if (client.options != null) {
            client.options.keyShift.setDown(true);
            movementKeysHeld = true;
        }
    }

    private void keepDismountShiftLocally(Minecraft client, LocalPlayer player) {
        releaseVehicleControls(client);
        releaseDirectMovementState(client);
        releaseUseKey(client);
        clearVanillaMovementKeys(client);
        player.input.keyPresses = new Input(false, false, false, false, false, true, false);
        if (client.options != null) {
            client.options.keyShift.setDown(true);
            movementKeysHeld = true;
        }
    }

    private Observation observeVehicleDismountRecovery(
            Minecraft client,
            LocalPlayer player,
            RouteStep target
    ) {
        Entity originalVehicle = client.level == null
                ? null
                : client.level.getEntity(dismountRecovery.originalBoatEntityId());
        boolean passenger = player.isPassenger();
        boolean ridingOriginalVehicle = passenger
                && player.getVehicle() != null
                && player.getVehicle().getId() == dismountRecovery.originalBoatEntityId();
        boolean originalVehiclePresent = originalVehicle != null && originalVehicle.isAlive();
        boolean touchingOriginalVehicle = originalVehicle != null
                && player.getBoundingBox().inflate(VEHICLE_CONTACT_INFLATION)
                        .intersects(originalVehicle.getBoundingBox());
        RequestPosition requestPosition = dismountRecovery.requestPosition().orElseThrow();
        boolean serverPositionChanged = !passenger
                && squaredDistance(player, requestPosition) > DISMOUNT_POSITION_EPSILON_SQR;

        BlockPos feet = navigationFeetResolver.resolve(player);
        boolean stableBlockSupport = isStableDismountSupport(client, feet);
        boolean safeWater = isSafeDismountWater(client, feet);
        BlockPos egress = !passenger && touchingOriginalVehicle
                ? revalidateDismountEgress(client, player, target)
                : null;
        if (egress == null && !touchingOriginalVehicle) {
            resetDismountEgressProgress(null, null);
        }
        boolean egressProgress = egress != null && recordDismountEgressProgress(player, egress);
        return new Observation(
                passenger,
                ridingOriginalVehicle,
                originalVehiclePresent,
                touchingOriginalVehicle,
                stableBlockSupport,
                safeWater,
                serverPositionChanged,
                egress != null,
                egressProgress
        );
    }

    private double squaredDistance(LocalPlayer player, RequestPosition requestPosition) {
        double dx = player.getX() - requestPosition.x();
        double dy = player.getY() - requestPosition.y();
        double dz = player.getZ() - requestPosition.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private BlockPos revalidateDismountEgress(
            Minecraft client,
            LocalPlayer player,
            RouteStep target
    ) {
        if (client.level == null) {
            resetDismountEgressProgress(null, null);
            return null;
        }
        if (dismountEgress != null && isValidDismountEgress(client, player, dismountEgress)) {
            return dismountEgress;
        }

        Entity originalVehicle = client.level.getEntity(dismountRecovery.originalBoatEntityId());
        if (originalVehicle == null) {
            resetDismountEgressProgress(null, null);
            return null;
        }
        BlockPos playerFeet = navigationFeetResolver.resolve(player);
        BlockPos selected = boatEgressSelector.select(
                new BoatEgressSelector.EgressProbe() {
                    @Override
                    public boolean loaded(BlockPos feet) {
                        return areLiveBlocksLoaded(client, feet, feet.above(), feet.below());
                    }

                    @Override
                    public boolean bodyClear(BlockPos feet) {
                        return isLiveBodyClear(client, feet)
                                && !isDangerousLiveBlock(client, feet)
                                && !isDangerousLiveBlock(client, feet.above());
                    }

                    @Override
                    public boolean stableBlockSupport(BlockPos feet) {
                        return isStableDismountSupport(client, feet);
                    }

                    @Override
                    public boolean safeWater(BlockPos feet) {
                        return isSafeDismountWater(client, feet);
                    }

                    @Override
                    public boolean destinationCollisionFree(BlockPos feet) {
                        return isDismountDestinationCollisionFree(client, player, feet);
                    }
                },
                playerFeet,
                originalVehicle.getBoundingBox().getCenter(),
                navigationTarget(player, target)
        ).filter(candidate -> isValidDismountEgress(client, player, candidate)).orElse(null);
        if (!Objects.equals(selected, dismountEgress)) {
            resetDismountEgressProgress(selected, player);
        }
        return selected;
    }

    private boolean isValidDismountEgress(Minecraft client, LocalPlayer player, BlockPos feet) {
        return areLiveBlocksLoaded(client, feet, feet.above(), feet.below())
                && isLiveBodyClear(client, feet)
                && !isDangerousLiveBlock(client, feet)
                && !isDangerousLiveBlock(client, feet.above())
                && !isDangerousLiveBlock(client, feet.below())
                && (isStableDismountSupport(client, feet) || isSafeDismountWater(client, feet))
                && isDismountDestinationCollisionFree(client, player, feet);
    }

    private boolean isStableDismountSupport(Minecraft client, BlockPos feet) {
        if (client.level == null
                || !areLiveBlocksLoaded(client, feet, feet.above(), feet.below())
                || !isLiveBodyClear(client, feet)
                || isDangerousLiveBlock(client, feet)
                || isDangerousLiveBlock(client, feet.above())
                || isDangerousLiveBlock(client, feet.below())
                || !isSafeSolidSupport(client, feet.below())) {
            return false;
        }
        return (client.level.getFluidState(feet).isEmpty()
                        || client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER))
                && (client.level.getFluidState(feet.above()).isEmpty()
                        || client.level.getFluidState(feet.above()).is(net.minecraft.tags.FluidTags.WATER));
    }

    private boolean isSafeDismountWater(Minecraft client, BlockPos feet) {
        return client.level != null
                && areLiveBlocksLoaded(client, feet, feet.above(), feet.below())
                && isLiveBodyClear(client, feet)
                && !isDangerousLiveBlock(client, feet)
                && !isDangerousLiveBlock(client, feet.above())
                && !isDangerousLiveBlock(client, feet.below())
                && client.level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER)
                && (client.level.getFluidState(feet.above()).isEmpty()
                        || client.level.getFluidState(feet.above()).is(net.minecraft.tags.FluidTags.WATER));
    }

    private boolean isDismountDestinationCollisionFree(
            Minecraft client,
            LocalPlayer player,
            BlockPos feet
    ) {
        if (client.level == null) {
            return false;
        }
        AABB destinationBox = player.getDimensions(Pose.STANDING)
                .makeBoundingBox(Vec3.atBottomCenterOf(feet));
        return client.level.getWorldBorder().isWithinBounds(destinationBox)
                && client.level.noCollision(player, destinationBox);
    }

    private boolean recordDismountEgressProgress(LocalPlayer player, BlockPos egress) {
        double distance = Math.sqrt(squaredHorizontalDistance(player, egress));
        if (!egress.equals(dismountEgress)) {
            resetDismountEgressProgress(egress, player);
            return false;
        }
        boolean progressed = distance <= lastDismountEgressDistance - EGRESS_PROGRESS_EPSILON;
        lastDismountEgressDistance = distance;
        return progressed;
    }

    private void resetDismountEgressProgress(BlockPos egress, LocalPlayer player) {
        dismountEgress = egress == null ? null : egress.immutable();
        lastDismountEgressDistance = egress == null || player == null
                ? Double.POSITIVE_INFINITY
                : Math.sqrt(squaredHorizontalDistance(player, egress));
    }

    private void clearPlanningForRecovery() {
        cancelPendingPlan();
        pathSegments.clear();
        currentStepOrdinal = 0;
        consecutiveNoPathFailures = 0;
        stuckTicks = 0;
        horizontalCollisionTicks = 0;
        lastDistance = Double.MAX_VALUE;
        lastWaypointDistance = Double.MAX_VALUE;
        lastPlayerPos = Vec3.ZERO;
        breakingBlock = null;
        activeStepSignature = null;
        activeStepTicks = 0;
        actionFailures = 0;
        movementRecoveryFailures = 0;
        pendingPlacementBlock = null;
        pendingPlacementTicks = 0;
        actionAcknowledged = false;
        dropCommitted = false;
        eatingSession = false;
        waitingForChunk = false;
        movementSamples.clear();
        movementSampleTick = 0;
        resetWaterTransit();
    }

    static boolean shouldBeginVehicleDismountAtArrival(boolean passenger, boolean vehiclePresent) {
        return passenger && vehiclePresent;
    }

    static boolean shouldBeginVehicleDismountForWaypoint(
            boolean passenger,
            boolean vehiclePresent,
            boolean swimWaypoint,
            boolean vehicleIsBoat,
            boolean boatableSwimRoute
    ) {
        return passenger
                && vehiclePresent
                && (!swimWaypoint || vehicleIsBoat && !boatableSwimRoute);
    }

    static DismountTerminalOutcome applyDismountTerminalEffects(
            DismountLifecycleEffects effects,
            Runnable freshReplan
    ) {
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(freshReplan, "freshReplan");
        if (effects.freshReplan()) {
            freshReplan.run();
        }
        return new DismountTerminalOutcome(effects.pauseStuck());
    }

    static boolean shouldBoardBoat(int candidateId, Predicate<Integer> suppressedPredicate) {
        Objects.requireNonNull(suppressedPredicate, "suppressedPredicate");
        return !suppressedPredicate.test(candidateId);
    }

    static DismountLifecycleEffects dismountLifecycleEffects(
            boolean beginning,
            Action action,
            boolean passenger
    ) {
        Objects.requireNonNull(action, "action");
        if (beginning) {
            return new DismountLifecycleEffects(
                    true, true, false, false, DismountInputMode.KEEP_SHIFT, DismountMotion.NONE
            );
        }
        return switch (action) {
            case NONE -> new DismountLifecycleEffects(
                    false, false, false, false, DismountInputMode.NEUTRAL, DismountMotion.NONE
            );
            case REQUEST_DISMOUNT -> new DismountLifecycleEffects(
                    true, false, false, false, DismountInputMode.REQUEST_SHIFT, DismountMotion.NONE
            );
            case HOLD -> new DismountLifecycleEffects(
                    true,
                    false,
                    false,
                    false,
                    passenger ? DismountInputMode.KEEP_SHIFT : DismountInputMode.NEUTRAL,
                    DismountMotion.NONE
            );
            case STEER_EGRESS -> new DismountLifecycleEffects(
                    true, false, false, false, DismountInputMode.NEUTRAL, DismountMotion.STEER
            );
            case PULSE_JUMP -> new DismountLifecycleEffects(
                    true, false, false, false, DismountInputMode.NEUTRAL, DismountMotion.JUMP
            );
            case COMPLETE_REPLAN -> new DismountLifecycleEffects(
                    true, false, true, false, DismountInputMode.NEUTRAL, DismountMotion.NONE
            );
            case FAILED -> new DismountLifecycleEffects(
                    true, false, false, true, DismountInputMode.NEUTRAL, DismountMotion.NONE
            );
        };
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
        if (routeBoatBackoffTicks > 0) {
            routeBoatBackoffTicks--;
            if (routeBoatBackoffTicks == 0) {
                routeBoatBackoffEntityId = 0;
            }
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
    }

    private void leaveWaterRun() {
        waterTransitPolicy.leaveWaterRun();
        currentWaterTravelDecision = WaterTransitPolicy.TravelDecision.SWIM;
        waterRunAnchor = null;
        waterRunAnchorSurfaceY = OptionalInt.empty();
        waterReplanAnchor = null;
        waterReplanContinuityPending = false;
        currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
        resetBoatAcquisition();
    }

    private void resetWaterTransit() {
        waterTransitPolicy.reset();
        currentWaterTravelDecision = WaterTransitPolicy.TravelDecision.SWIM;
        waterRunAnchor = null;
        waterRunAnchorSurfaceY = OptionalInt.empty();
        waterReplanAnchor = null;
        waterReplanContinuityPending = false;
        currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
        resetBoatAcquisition();
    }

    private void resetBoatAcquisition() {
        boatAcquisitionPolicy.resetForWaterRun();
        boatPlacementBaseline = Set.of();
        pendingBoatPlacementSurface = null;
        boatSurfaceApproachTarget = null;
        lastBoatSurfaceApproachTarget = null;
        lastBoatSurfaceVerticalDistance = Double.MAX_VALUE;
        boatSurfaceApproachCandidateId = 0;
        lastBoatSurfaceApproachCandidateId = 0;
        routeBoatApproachStallTicks = 0;
        routeBoatBackoffEntityId = 0;
        routeBoatBackoffTicks = 0;
    }

    private void resetProgress() {
        cancelPendingPlan();
        pathSegments.clear();
        currentStepOrdinal = 0;
        planningRetry.forceFreshSnapshot();
        consecutiveNoPathFailures = 0;
        stuckTicks = 0;
        horizontalCollisionTicks = 0;
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
        eatCooldown = 0;
        elytraStartCooldown = 0;
        fireworkCooldown = 0;
        resetWaterTransit();
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
        return navigationTarget(navigationFeetResolver.resolve(player), target);
    }

    private BlockPos navigationTarget(BlockPos origin, RouteStep target) {
        if (target.state() == RouteStepState.OPENED) {
            return new BlockPos(target.targetBlock().x(), origin.getY(), target.targetBlock().z());
        }
        int targetX = Mth.clamp(
                origin.getX(),
                interiorMin(target.region().bounds().minX(), target.region().bounds().maxX()),
                interiorMax(target.region().bounds().minX(), target.region().bounds().maxX())
        );
        int targetZ = Mth.clamp(
                origin.getZ(),
                interiorMin(target.region().bounds().minZ(), target.region().bounds().maxZ()),
                interiorMax(target.region().bounds().minZ(), target.region().bounds().maxZ())
        );
        return new BlockPos(targetX, origin.getY(), targetZ);
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

    enum PlanningFailure {
        STALE,
        LIVE_INVALIDATED,
        NO_PATH,
        NODE_LIMIT,
        INVALID_PLAN,
        INITIAL_PREFIX
    }

    record InitialPlanAcceptance(
            LocalPathPlanner.PathPlan plan,
            PlanningFailure failure
    ) {
        static InitialPlanAcceptance accepted(LocalPathPlanner.PathPlan plan) {
            return new InitialPlanAcceptance(Objects.requireNonNull(plan, "plan"), null);
        }

        static InitialPlanAcceptance rejected(PlanningFailure failure) {
            return new InitialPlanAcceptance(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    private record PlanningRequest(
            PlanRequestKind kind,
            String targetSignature,
            long generation,
            RouteStep target,
            AutoNavigationConfig config,
            BlockPos plannedStart,
            PathSegmentCoordinator.LookaheadRequest lookaheadRequest,
            ContinuationContext continuationContext
    ) {
        private PlanningRequest {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetSignature, "targetSignature");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(plannedStart, "plannedStart");
            Objects.requireNonNull(continuationContext, "continuationContext");
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

    enum DismountInputMode {
        REQUEST_SHIFT,
        KEEP_SHIFT,
        NEUTRAL
    }

    enum DismountMotion {
        NONE,
        STEER,
        JUMP
    }

    record DismountLifecycleEffects(
            boolean returnImmediately,
            boolean clearPlanning,
            boolean freshReplan,
            boolean pauseStuck,
            DismountInputMode inputMode,
            DismountMotion motion
    ) {
        DismountLifecycleEffects {
            Objects.requireNonNull(inputMode, "inputMode");
            Objects.requireNonNull(motion, "motion");
        }
    }

    record DismountTerminalOutcome(boolean pauseStuck) {
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

package dev.mappywall.client;

import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.RunMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
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
    private static final int HOTBAR_CONTAINER_OFFSET = 36;
    private static final double ARRIVAL_DISTANCE_BLOCKS = 4.0;
    private static final double WAYPOINT_DISTANCE_BLOCKS = 1.25;
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
    private static final double AGGRESSIVE_GROUND_SPEED = 0.31;
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
    private static final int LOCAL_STALL_TICKS = 40;
    private static final int LOOP_STALL_TICKS = 100;
    private static final double LOCAL_STALL_AREA_BLOCKS = 1.0;
    private static final double LOOP_STALL_AREA_BLOCKS = 4.0;
    private static final int REPLAN_INTERVAL_TICKS = 50;
    private static final int RECOVERY_TICKS = 35;
    private static final int PLACE_COOLDOWN_TICKS = 8;
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
    private static final ExecutorService PATH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MappyWall Path Planner");
        thread.setDaemon(true);
        return thread;
    });

    private final AutoNavigationConfig config = AutoNavigationConfig.defaults();
    private final LocalPathPlanner pathPlanner = new LocalPathPlanner();
    private final ArrayDeque<MovementSample> movementSamples = new ArrayDeque<>();

    private List<LocalPathPlanner.PathStep> path = List.of();
    private int pathIndex;
    private int replanCooldown;
    private int stuckTicks;
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
    private int recoveryTicks;
    private Future<LocalPathPlanner.PathPlan> pendingPlan;
    private String pendingPlanSignature;
    private AutomationStyle automationStyle = AutomationStyle.NORMAL;
    private boolean movementKeysHeld;
    private boolean useKeyHeld;
    private boolean attackKeyHeld;
    private int movementSampleTick;

    public MovementResult tick(Minecraft client, MapWallSave save, RouteStep target) {
        if (client.level == null || client.player == null || target == null) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        automationStyle = save.project().automationStyle();
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
        if (arrivedAtNavigationTarget(player, target)) {
            if (player.isPassenger() && tryDismountVehicle(client, player)) {
                return MovementResult.active(pathSnapshot());
            }
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        tickCooldowns();

        if (tryEat(client, player)) {
            return MovementResult.active(pathSnapshot());
        }

        acceptCompletedPlan(target);
        if (needsNewPath(target)) {
            requestReplan(client, target);
        }

        LocalPathPlanner.PathStep waypoint = nextWaypoint(player);
        if (waypoint == null) {
            failedReplans++;
            recoveryTicks = RECOVERY_TICKS;
            return recoverTowardTarget(client, player, target);
        }

        if (player.isPassenger() && waypoint.action() != LocalPathPlanner.StepAction.SWIM
                && tryDismountVehicle(client, player)) {
            return MovementResult.active(pathSnapshot());
        }

        failedReplans = 0;
        MovementResult actionResult = executeStep(client, player, waypoint);
        updateProgress(player, target, waypoint);
        return actionResult;
    }

    public void release(Minecraft client) {
        releaseMovementKeys(client);
        releaseUseKey(client);
        releaseAttackKey(client);
        releaseVehicleControls(client);
        breakingBlock = null;
        movementSamples.clear();
        cancelPendingPlan();
    }

    public boolean isWaitingForChunk() {
        return false;
    }

    public boolean isPlanningPath() {
        return pendingPlan != null && !pendingPlan.isDone();
    }

    public List<BlockPos> pathSnapshot() {
        if (path.isEmpty() || pathIndex >= path.size()) {
            return List.of();
        }
        ArrayList<BlockPos> snapshot = new ArrayList<>();
        for (int index = pathIndex; index < path.size(); index++) {
            snapshot.add(path.get(index).pos());
        }
        return snapshot;
    }

    private MovementResult executeStep(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
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
            case WALK -> moveToward(client, player, waypoint, player.horizontalCollision || recoveryTicks > 0);
        };
    }

    private MovementResult dropToward(Minecraft client, LocalPlayer player, LocalPathPlanner.PathStep waypoint) {
        double drop = player.getY() - waypoint.pos().getY();
        boolean sprint = drop < 3.75;
        return moveToward(client, player, waypoint, false, false, sprint);
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

    private MovementResult recoverTowardTarget(Minecraft client, LocalPlayer player, RouteStep target) {
        BlockPos navigationTarget = navigationTarget(player, target);
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
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

        float yawError = faceMovement(
                player,
                navigationTarget.getX() + 0.5 - player.getX(),
                navigationTarget.getZ() + 0.5 - player.getZ()
        );

        boolean aligned = yawError <= MOVE_ALIGNMENT_DEGREES;
        setMovementKeys(client, aligned, false, false, false, aligned, false, aligned);
        return MovementResult.active(List.of(navigationTarget));
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
        if (player.isPassenger()) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof AbstractBoat boat) {
                return driveBoatToward(client, player, boat, waypoint, style);
            }
        } else {
            if (tryBoardNearbyBoat(client, player, waypoint.pos(), style)) {
                return MovementResult.active(pathSnapshot());
            }
            if (boatCooldown <= 0 && tryPlaceBoat(client, player, waypoint, style)) {
                boatCooldown = BOAT_COOLDOWN_TICKS;
                return MovementResult.active(pathSnapshot());
            }
        }
        return moveToward(client, player, waypoint, true);
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
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        boat.setYRot(yaw);
        boat.setXRot(0.0F);
        boat.setInput(false, false, true, false);
        boat.setPaddleState(true, true);
        sendBoatPaddles(client, true, true);
        sendPlayerInput(client, true, false, false, false, false, false, true);

        if (style == AutomationStyle.AGGRESSIVE) {
            Vec3 velocity = new Vec3(dirX * BOAT_DRIVE_SPEED, boat.getDeltaMovement().y, dirZ * BOAT_DRIVE_SPEED);
            boat.setDeltaMovement(velocity);
            boat.setPos(boat.getX() + velocity.x, boat.getY(), boat.getZ() + velocity.z);
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
            return moveToward(client, player, waypoint, true);
        }
        BlockPos block = waypoint.actionBlock();
        if (client.level.getBlockState(block).getCollisionShape(client.level, block).isEmpty()) {
            breakingBlock = null;
            releaseAttackKey(client);
            breakActionsForTarget++;
            pathIndex++;
            replanCooldown = 0;
            return MovementResult.active(pathSnapshot());
        }

        releaseMovementKeys(client);
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
        setAttackKey(client, true);
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
        releaseAttackKey(client);
        if (client.gameMode == null || waypoint.actionBlock() == null) {
            return MovementResult.active(pathSnapshot());
        }
        if (isSolid(client, waypoint.actionBlock())) {
            pathIndex++;
            replanCooldown = 0;
            return MovementResult.active(pathSnapshot());
        }
        releaseMovementKeys(client);
        if (placeCooldown > 0) {
            return MovementResult.active(pathSnapshot());
        }
        int slot = findAllowedPlaceBlock(player);
        if (slot < 0) {
            release(client);
            resetProgress();
            return MovementResult.pause(Component.translatable("message.mappywall.auto_walk_no_place_block"));
        }
        if (!selectOrMoveToHotbar(client, player, slot)) {
            releaseMovementKeys(client);
            placeCooldown = 4;
            return MovementResult.active(pathSnapshot());
        }

        BlockHitResult hit = placementHit(client, waypoint.actionBlock());
        if (hit == null) {
            replanCooldown = 0;
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
        placeCooldown = PLACE_COOLDOWN_TICKS;
        replanCooldown = 0;
        return MovementResult.active(pathSnapshot());
    }

    private boolean tryEat(Minecraft client, LocalPlayer player) {
        if (!config.eatingEnabled() || eatCooldown > 0 || !player.canEat(false)) {
            if (useKeyHeld && client.options != null) {
                client.options.keyUse.setDown(false);
                useKeyHeld = false;
            }
            return false;
        }
        if (player.getFoodData().getFoodLevel() > config.eatAtFoodLevel()) {
            return false;
        }
        releaseAttackKey(client);

        if (player.isUsingItem()) {
            pressUse(client, true);
            return true;
        }

        int slot = findAllowedFood(player);
        if (slot < 0) {
            return false;
        }
        if (!selectOrMoveToHotbar(client, player, slot)) {
            eatCooldown = 4;
            releaseMovementKeys(client);
            return true;
        }
        releaseMovementKeys(client);
        if (client.gameMode != null) {
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        pressUse(client, true);
        eatCooldown = EAT_COOLDOWN_TICKS;
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
                boat -> boat.isAlive() && boat.getPassengers().isEmpty()
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
        return player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
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

    private void requestReplan(Minecraft client, RouteStep target) {
        if (client.player == null) {
            path = List.of();
            return;
        }

        String signature = target.region().signature();
        if (pendingPlan != null && !pendingPlan.isDone()) {
            if (signature.equals(pendingPlanSignature)) {
                return;
            }
            pendingPlan.cancel(true);
        }

        LocalPathPlanner.NavigationSnapshot snapshot = LocalPathPlanner.NavigationSnapshot.capture(client.player);
        pendingPlanSignature = signature;
        pendingPlan = PATH_EXECUTOR.submit(() -> pathPlanner.plan(snapshot, target, config));
        replanCooldown = REPLAN_INTERVAL_TICKS;
        targetSignature = signature;
    }

    private boolean needsNewPath(RouteStep target) {
        replanCooldown--;
        if (!target.region().signature().equals(targetSignature)) {
            return true;
        }
        if (pendingPlan != null && !pendingPlan.isDone()) {
            return false;
        }
        return path.isEmpty()
                ? replanCooldown <= 0
                : pathIndex >= path.size()
                || replanCooldown <= 0
                || !target.region().signature().equals(targetSignature);
    }

    private void acceptCompletedPlan(RouteStep target) {
        if (pendingPlan == null || !pendingPlan.isDone()) {
            return;
        }

        try {
            LocalPathPlanner.PathPlan plan = pendingPlan.get();
            if (target.region().signature().equals(pendingPlanSignature)) {
                path = plan.steps();
                pathIndex = 0;
                replanCooldown = REPLAN_INTERVAL_TICKS;
                targetSignature = pendingPlanSignature;
                breakingBlock = null;
            }
        } catch (CancellationException | ExecutionException exception) {
            path = List.of();
            pathIndex = 0;
            replanCooldown = Math.max(replanCooldown, 20);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            replanCooldown = Math.max(replanCooldown, 20);
        } finally {
            pendingPlan = null;
            pendingPlanSignature = null;
        }
    }

    private LocalPathPlanner.PathStep nextWaypoint(LocalPlayer player) {
        while (pathIndex < path.size()) {
            LocalPathPlanner.PathStep step = path.get(pathIndex);
            if (step.action() == LocalPathPlanner.StepAction.BREAK
                    || step.action() == LocalPathPlanner.StepAction.PLACE) {
                return step;
            }
            if (isAtWaypoint(player, step.pos())) {
                pathIndex++;
                continue;
            }
            return step;
        }
        return null;
    }

    private boolean isAtWaypoint(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= WAYPOINT_DISTANCE_BLOCKS && Math.abs(player.getY() - pos.getY()) <= 1.35;
    }

    private void updateProgress(LocalPlayer player, RouteStep target, LocalPathPlanner.PathStep waypoint) {
        BlockPos navigationTarget = navigationTarget(player, target);
        double distance = Math.sqrt(squaredHorizontalDistance(player, navigationTarget));
        double waypointDistance = Math.sqrt(squaredHorizontalDistance(player, waypoint.pos()));
        Vec3 playerPos = new Vec3(player.getX(), player.getY(), player.getZ());
        double playerMoved = playerPos.distanceTo(lastPlayerPos);
        if (distance < lastDistance - STUCK_EPSILON
                || waypointDistance < lastWaypointDistance - STUCK_EPSILON
                || playerMoved > PLAYER_MOVE_EPSILON) {
            stuckTicks = 0;
        } else if (waypoint.action() == LocalPathPlanner.StepAction.WALK
                || waypoint.action() == LocalPathPlanner.StepAction.JUMP
                || waypoint.action() == LocalPathPlanner.StepAction.DROP
                || waypoint.action() == LocalPathPlanner.StepAction.SWIM) {
            stuckTicks++;
        }
        lastDistance = distance;
        lastWaypointDistance = waypointDistance;
        lastPlayerPos = playerPos;

        boolean movementAction = isMovementAction(waypoint.action());
        if (movementAction) {
            recordMovementSample(playerPos);
        } else {
            movementSamples.clear();
        }

        if (player.horizontalCollision
                || stuckTicks >= STUCK_TICKS_LIMIT
                || (movementAction && isTrappedInRecentArea(LOCAL_STALL_TICKS, LOCAL_STALL_AREA_BLOCKS))
                || (movementAction && isTrappedInRecentArea(LOOP_STALL_TICKS, LOOP_STALL_AREA_BLOCKS))) {
            forceLocalReplan();
        }
    }

    private boolean isMovementAction(LocalPathPlanner.StepAction action) {
        return action == LocalPathPlanner.StepAction.WALK
                || action == LocalPathPlanner.StepAction.JUMP
                || action == LocalPathPlanner.StepAction.DROP
                || action == LocalPathPlanner.StepAction.SWIM;
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
        path = List.of();
        pathIndex = 0;
        replanCooldown = 0;
        stuckTicks = 0;
        recoveryTicks = RECOVERY_TICKS;
        breakingBlock = null;
        movementSamples.clear();
    }

    private double squaredHorizontalDistance(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dz * dz;
    }

    private int findAllowedFood(LocalPlayer player) {
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

    private boolean selectBestToolForBlock(Minecraft client, LocalPlayer player, BlockPos block) {
        if (client.level == null) {
            return true;
        }
        BlockState state = client.level.getBlockState(block);
        int slot = findBestTool(player, state);
        if (slot < 0 || slot == player.getInventory().getSelectedSlot()) {
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
        if (inventorySlot < 0) {
            return false;
        }
        if (inventorySlot < 9) {
            player.getInventory().setSelectedSlot(inventorySlot);
            return true;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (client.gameMode == null) {
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
                return new BlockHitResult(Vec3.atCenterOf(neighbor), direction.getOpposite(), neighbor, false);
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

    private BlockPos breakableObstacleAhead(Minecraft client, LocalPlayer player) {
        if (client.level == null || !config.blockBreakingEnabled()) {
            return null;
        }

        Direction direction = player.getDirection();
        BlockPos feet = player.blockPosition().relative(direction);
        BlockPos head = feet.above();
        if (isBreakableObstacle(client, feet)) {
            return feet;
        }
        if (isBreakableObstacle(client, head)) {
            return head;
        }
        return null;
    }

    private boolean isBreakableObstacle(Minecraft client, BlockPos pos) {
        if (client.level == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.getCollisionShape(client.level, pos).isEmpty()) {
            return false;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return config.allowsBreak(blockId);
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
        releaseAttackKey(client);
        client.options.keyUp.setDown(forward);
        client.options.keyDown.setDown(back);
        client.options.keyLeft.setDown(left);
        client.options.keyRight.setDown(right);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(sneak);
        client.options.keySprint.setDown(sprint);
        movementKeysHeld = forward || back || left || right || jump || sneak || sprint;
        sendPlayerInput(client, forward, back, left, right, jump, sneak, sprint);
    }

    private void releaseMovementKeys(Minecraft client) {
        if (client.options == null || !movementKeysHeld) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keySprint.setDown(false);
        movementKeysHeld = false;
        sendPlayerInput(client, false, false, false, false, false, false, false);
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
        releaseMovementKeys(client);
        releaseAttackKey(client);

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= 0.0001) {
            sendPlayerInput(client, false, false, false, false, jump, sneak, false);
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

        Vec3 current = player.getDeltaMovement();
        double velocityY = current.y;
        boolean shouldJump = jump && (player.onGround() || player.horizontalCollision || player.isInWater());
        if (shouldJump) {
            velocityY = player.isInWater() ? Math.max(current.y, 0.08) : Math.max(current.y, AGGRESSIVE_JUMP_VELOCITY);
        }

        player.setSprinting(sprint && !sneak);
        player.setDeltaMovement(dirX * speed, velocityY, dirZ * speed);
        sendPlayerInput(client, true, false, false, false, shouldJump, sneak, sprint && !sneak);
    }

    private boolean tryDismountVehicle(Minecraft client, LocalPlayer player) {
        if (!player.isPassenger() || dismountCooldown > 0) {
            return false;
        }
        releaseVehicleControls(client);
        releaseMovementKeys(client);
        releaseAttackKey(client);
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

    private void setAttackKey(Minecraft client, boolean pressed) {
        if (client.options == null) {
            return;
        }
        client.options.keyAttack.setDown(pressed);
        attackKeyHeld = pressed;
    }

    private void releaseAttackKey(Minecraft client) {
        if (client.options == null || !attackKeyHeld) {
            return;
        }
        client.options.keyAttack.setDown(false);
        attackKeyHeld = false;
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
        client.player.connection.send(new ServerboundPlayerInputPacket(
                new Input(forward, back, left, right, jump, sneak, sprint)
        ));
    }

    private void sendBoatPaddles(Minecraft client, boolean left, boolean right) {
        if (client.player == null) {
            return;
        }
        client.player.connection.send(new ServerboundPaddleBoatPacket(left, right));
    }

    private void releaseVehicleControls(Minecraft client) {
        if (client.player == null || !client.player.isPassenger()) {
            return;
        }
        Entity vehicle = client.player.getVehicle();
        if (vehicle instanceof AbstractBoat boat) {
            boat.setInput(false, false, false, false);
            boat.setPaddleState(false, false);
            sendBoatPaddles(client, false, false);
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
        if (recoveryTicks > 0) {
            recoveryTicks--;
        }
    }

    private void resetProgress() {
        path = List.of();
        pathIndex = 0;
        replanCooldown = 0;
        stuckTicks = 0;
        failedReplans = 0;
        lastDistance = Double.MAX_VALUE;
        lastWaypointDistance = Double.MAX_VALUE;
        lastPlayerPos = Vec3.ZERO;
        targetSignature = null;
        breakingBlock = null;
        movementSamples.clear();
        movementSampleTick = 0;
        recoveryTicks = 0;
        placeCooldown = 0;
        boatCooldown = 0;
        eatCooldown = 0;
        elytraStartCooldown = 0;
        fireworkCooldown = 0;
        dismountCooldown = 0;
        cancelPendingPlan();
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
        if (pendingPlan != null && !pendingPlan.isDone()) {
            pendingPlan.cancel(true);
        }
        pendingPlan = null;
        pendingPlanSignature = null;
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

    private record MovementSample(int tick, double x, double z) {
    }

    public record MovementResult(boolean moving, boolean waitingForChunk, Component pauseMessage, List<BlockPos> path) {
        static MovementResult none() {
            return new MovementResult(false, false, null, List.of());
        }

        static MovementResult active(List<BlockPos> path) {
            return new MovementResult(true, false, null, path);
        }

        static MovementResult pause(Component message) {
            return new MovementResult(false, false, message, List.of());
        }

        public boolean shouldPause() {
            return pauseMessage != null;
        }
    }
}

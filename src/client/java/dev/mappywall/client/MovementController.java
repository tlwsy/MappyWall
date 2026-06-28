package dev.mappywall.client;

import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.RunMode;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BoatItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class MovementController {
    private static final int HOTBAR_CONTAINER_OFFSET = 36;
    private static final double ARRIVAL_DISTANCE_BLOCKS = 4.0;
    private static final double WAYPOINT_DISTANCE_BLOCKS = 1.25;
    private static final double BOAT_PLACE_REACH_BLOCKS = 4.75;
    private static final float MOVE_ALIGNMENT_DEGREES = 75.0F;
    private static final float SPRINT_ALIGNMENT_DEGREES = 35.0F;
    private static final float MOVEMENT_TURN_DEGREES = 12.0F;
    private static final double AGGRESSIVE_INPUT_THRESHOLD = 0.28;
    private static final double STUCK_EPSILON = 0.06;
    private static final double PLAYER_MOVE_EPSILON = 0.015;
    private static final int STUCK_TICKS_LIMIT = 90;
    private static final int REPLAN_INTERVAL_TICKS = 50;
    private static final int RECOVERY_TICKS = 35;
    private static final int PLACE_COOLDOWN_TICKS = 8;
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
    private double lastDistance = Double.MAX_VALUE;
    private double lastWaypointDistance = Double.MAX_VALUE;
    private Vec3d lastPlayerPos = Vec3d.ZERO;
    private String targetSignature;
    private BlockPos breakingBlock;
    private int recoveryTicks;
    private Future<LocalPathPlanner.PathPlan> pendingPlan;
    private String pendingPlanSignature;
    private AutomationStyle automationStyle = AutomationStyle.NORMAL;
    private boolean movementKeysHeld;
    private boolean useKeyHeld;
    private boolean attackKeyHeld;

    public MovementResult tick(MinecraftClient client, MapWallSave save, RouteStep target) {
        if (client.world == null || client.player == null || target == null) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        automationStyle = save.project().automationStyle();
        if (save.project().mode() == RunMode.AUTO_ELYTRA) {
            return tickElytra(client, save, target);
        }

        if (save.project().mode() != RunMode.AUTO_WALK) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        ClientPlayerEntity player = client.player;
        if (arrivedAtNavigationTarget(player, target)) {
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

        failedReplans = 0;
        MovementResult actionResult = executeStep(client, player, waypoint);
        updateProgress(player, target, waypoint);
        return actionResult;
    }

    public void release(MinecraftClient client) {
        releaseMovementKeys(client);
        releaseUseKey(client);
        releaseAttackKey(client);
        breakingBlock = null;
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

    private MovementResult executeStep(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        return switch (waypoint.action()) {
            case BREAK -> breakBlock(client, player, waypoint);
            case PLACE -> placeBlock(client, player, waypoint);
            case SWIM -> swimOrBoat(client, player, waypoint);
            case JUMP -> moveToward(client, player, waypoint, true);
            case DROP -> dropToward(client, player, waypoint);
            case WALK -> moveToward(client, player, waypoint, player.horizontalCollision || recoveryTicks > 0);
        };
    }

    private MovementResult dropToward(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        double drop = player.getY() - waypoint.pos().getY();
        boolean sprint = drop < 3.75;
        return moveToward(client, player, waypoint, false, false, sprint);
    }

    private MovementResult moveToward(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint, boolean jump) {
        return moveToward(client, player, waypoint, jump, false, true);
    }

    private MovementResult moveToward(
            MinecraftClient client,
            ClientPlayerEntity player,
            LocalPathPlanner.PathStep waypoint,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        BlockPos pos = waypoint.pos();
        double targetX = pos.getX() + 0.5;
        double targetZ = pos.getZ() + 0.5;
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            setDirectionalMovementKeys(
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

    private MovementResult recoverTowardTarget(MinecraftClient client, ClientPlayerEntity player, RouteStep target) {
        BlockPos navigationTarget = navigationTarget(player, target);
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            setDirectionalMovementKeys(
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

    private MovementResult tickElytra(MinecraftClient client, MapWallSave save, RouteStep target) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
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
            return MovementResult.pause(Text.translatable("message.mappywall.auto_elytra_no_elytra"));
        }

        if (!player.isGliding()) {
            return startOrPrepareElytra(client, player, navigationTarget, save.project().automationStyle());
        }

        return flyElytraToward(client, player, navigationTarget, save.project().automationStyle());
    }

    private MovementResult startOrPrepareElytra(
            MinecraftClient client,
            ClientPlayerEntity player,
            BlockPos navigationTarget,
            AutomationStyle style
    ) {
        if (style == AutomationStyle.AGGRESSIVE) {
            if (player.isOnGround()) {
                setMovementKeys(client, true, false, false, false, true, false, true);
                return MovementResult.active(List.of(navigationTarget));
            }
            releaseMovementKeys(client);
            if (elytraStartCooldown <= 0) {
                player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                        player,
                        ClientCommandC2SPacket.Mode.START_FALL_FLYING
                ));
                player.startGliding();
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
        if (player.isOnGround()) {
            setMovementKeys(client, aligned, false, false, false, aligned, false, aligned);
            return MovementResult.active(List.of(navigationTarget));
        }

        releaseMovementKeys(client);
        if (elytraStartCooldown <= 0 && (style == AutomationStyle.AGGRESSIVE || aligned)) {
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                    player,
                    ClientCommandC2SPacket.Mode.START_FALL_FLYING
            ));
            player.startGliding();
            elytraStartCooldown = ELYTRA_START_COOLDOWN_TICKS;
        }
        return MovementResult.active(List.of(navigationTarget));
    }

    private MovementResult flyElytraToward(
            MinecraftClient client,
            ClientPlayerEntity player,
            BlockPos navigationTarget,
            AutomationStyle style
    ) {
        releaseMovementKeys(client);
        double dx = navigationTarget.getX() + 0.5 - player.getX();
        double dz = navigationTarget.getZ() + 0.5 - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double cruiseAltitude = elytraCruiseAltitude(client);
        boolean climbing = player.getY() < cruiseAltitude - ELYTRA_CLIMB_MARGIN;
        if (climbing) {
            faceElytraClimb(player, dx, dz, style);
        } else {
            faceElytra(player, dx, dz, horizontalDistance, style);
        }

        Vec3d velocity = player.getVelocity();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean needsBoost = horizontalDistance > ELYTRA_FIREWORK_DISTANCE
                || horizontalSpeed < ELYTRA_LOW_SPEED
                || climbing;
        if (needsBoost && fireworkCooldown <= 0) {
            int slot = findFirework(player);
            if (slot < 0) {
                release(client);
                resetProgress();
                return MovementResult.pause(Text.translatable("message.mappywall.auto_elytra_no_firework"));
            }
            if (!selectOrMoveToHotbar(client, player, slot)) {
                fireworkCooldown = 4;
                return MovementResult.active(List.of(navigationTarget));
            }
            if (client.interactionManager != null) {
                client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                player.swingHand(Hand.MAIN_HAND);
                fireworkCooldown = style == AutomationStyle.AGGRESSIVE
                        ? ELYTRA_FIREWORK_AGGRESSIVE_COOLDOWN_TICKS
                        : ELYTRA_FIREWORK_NORMAL_COOLDOWN_TICKS;
            }
        }
        return MovementResult.active(List.of(navigationTarget));
    }

    private MovementResult swimOrBoat(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        if (!player.hasVehicle()) {
            AutomationStyle style = currentAutomationStyle();
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

    private AutomationStyle currentAutomationStyle() {
        return automationStyle;
    }

    private MovementResult breakBlock(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        if (client.interactionManager == null || waypoint.actionBlock() == null) {
            return moveToward(client, player, waypoint, true);
        }
        BlockPos block = waypoint.actionBlock();
        if (client.world.getBlockState(block).getCollisionShape(client.world, block).isEmpty()) {
            breakingBlock = null;
            releaseAttackKey(client);
            pathIndex++;
            replanCooldown = 0;
            return MovementResult.active(pathSnapshot());
        }

        releaseMovementKeys(client);
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            sendServerLookAt(player, block.toCenterPos());
        } else {
            faceBlock(player, block);
        }
        setAttackKey(client, true);
        Direction side = Direction.getFacing(
                player.getX() - (block.getX() + 0.5),
                player.getEyeY() - (block.getY() + 0.5),
                player.getZ() - (block.getZ() + 0.5)
        );
        if (!block.equals(breakingBlock)) {
            breakingBlock = block;
            client.interactionManager.attackBlock(block, side);
        } else {
            client.interactionManager.updateBlockBreakingProgress(block, side);
        }
        player.swingHand(Hand.MAIN_HAND);
        return MovementResult.active(pathSnapshot());
    }

    private MovementResult placeBlock(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        releaseAttackKey(client);
        if (client.interactionManager == null || waypoint.actionBlock() == null || placeCooldown > 0) {
            return MovementResult.active(pathSnapshot());
        }
        if (isSolid(client, waypoint.actionBlock())) {
            pathIndex++;
            replanCooldown = 0;
            return MovementResult.active(pathSnapshot());
        }
        int slot = findAllowedPlaceBlock(player);
        if (slot < 0) {
            release(client);
            resetProgress();
            return MovementResult.pause(Text.translatable("message.mappywall.auto_walk_no_place_block"));
        }
        if (!selectOrMoveToHotbar(client, player, slot)) {
            placeCooldown = 4;
            return MovementResult.active(pathSnapshot());
        }

        BlockHitResult hit = placementHit(client, waypoint.actionBlock());
        if (hit == null) {
            replanCooldown = 0;
            return MovementResult.active(pathSnapshot());
        }

        releaseMovementKeys(client);
        if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
            sendServerLookAt(player, hit.getPos());
        } else {
            face(player, hit.getPos().x - player.getX(), hit.getPos().y - player.getEyeY(), hit.getPos().z - player.getZ(), true);
        }
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        placeCooldown = PLACE_COOLDOWN_TICKS;
        replanCooldown = 0;
        return MovementResult.active(pathSnapshot());
    }

    private boolean tryEat(MinecraftClient client, ClientPlayerEntity player) {
        if (!config.eatingEnabled() || eatCooldown > 0 || !player.canConsume(false)) {
            if (useKeyHeld && client.options != null) {
                client.options.useKey.setPressed(false);
                useKeyHeld = false;
            }
            return false;
        }
        if (player.getHungerManager().getFoodLevel() > config.eatAtFoodLevel()) {
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
        if (client.interactionManager != null) {
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        }
        pressUse(client, true);
        eatCooldown = EAT_COOLDOWN_TICKS;
        return true;
    }

    private boolean tryPlaceBoat(
            MinecraftClient client,
            ClientPlayerEntity player,
            LocalPathPlanner.PathStep waypoint,
            AutomationStyle style
    ) {
        if (client.interactionManager == null || client.world == null) {
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
                    waterPos.getY() + 0.2 - player.getEyeY(),
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

    private BlockPos bestBoatWaterPos(MinecraftClient client, ClientPlayerEntity player, BlockPos waypoint) {
        if (client.world == null) {
            return null;
        }
        BlockPos playerPos = player.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    BlockPos candidate = mutable.toImmutable();
                    if (!isBoatWater(client, candidate)) {
                        continue;
                    }
                    double eyeDistance = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(candidate));
                    if (eyeDistance > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS) {
                        continue;
                    }
                    double waypointDistance = candidate.getSquaredDistance(waypoint);
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

    private boolean isBoatWater(MinecraftClient client, BlockPos pos) {
        return client.world != null
                && client.world.getFluidState(pos).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                && client.world.getBlockState(pos.up()).getCollisionShape(client.world, pos.up()).isEmpty();
    }

    private void useBoatItemAtWater(
            MinecraftClient client,
            ClientPlayerEntity player,
            BlockPos waterPos,
            AutomationStyle style
    ) {
        Vec3d hit = Vec3d.ofCenter(waterPos).add(0.0, -0.15, 0.0);
        if (style == AutomationStyle.AGGRESSIVE) {
            float oldYaw = player.getYaw();
            float oldPitch = player.getPitch();
            float[] look = lookAngles(player, hit);
            sendServerLook(player, look[0], look[1]);
            player.setYaw(look[0]);
            player.setPitch(look[1]);
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            player.swingHand(Hand.MAIN_HAND);
            player.setYaw(oldYaw);
            player.setPitch(oldPitch);
            return;
        }
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
    }

    private float[] lookAngles(ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return new float[] { yaw, pitch };
    }

    private void sendServerLook(ClientPlayerEntity player, float yaw, float pitch) {
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                yaw,
                pitch,
                player.isOnGround(),
                player.horizontalCollision
        ));
    }

    private void sendServerLookAt(ClientPlayerEntity player, Vec3d target) {
        float[] look = lookAngles(player, target);
        sendServerLook(player, look[0], look[1]);
    }

    private boolean tryBoardNearbyBoat(
            MinecraftClient client,
            ClientPlayerEntity player,
            BlockPos waterPos,
            AutomationStyle style
    ) {
        if (client.world == null || client.interactionManager == null) {
            return false;
        }
        Box searchBox = new Box(waterPos).expand(style == AutomationStyle.AGGRESSIVE ? 6.0 : 3.0);
        List<AbstractBoatEntity> boats = client.world.getEntitiesByClass(
                AbstractBoatEntity.class,
                searchBox,
                boat -> boat.isAlive() && !boat.hasPassengers()
        );
        if (boats.isEmpty()) {
            return false;
        }
        AbstractBoatEntity boat = boats.stream()
                .min((left, right) -> Double.compare(left.squaredDistanceTo(player), right.squaredDistanceTo(player)))
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
        client.interactionManager.interactEntity(player, boat, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private void faceElytra(ClientPlayerEntity player, double dx, double dz, double horizontalDistance, AutomationStyle style) {
        if (dx * dx + dz * dz > 0.0001) {
            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float maxTurn = style == AutomationStyle.AGGRESSIVE ? 30.0F : 16.0F;
            float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
            player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -maxTurn, maxTurn));
        }

        float targetPitch;
        if (horizontalDistance < 32.0) {
            targetPitch = 12.0F;
        } else {
            targetPitch = 2.0F;
        }
        float pitchDelta = MathHelper.wrapDegrees(targetPitch - player.getPitch());
        player.setPitch(player.getPitch() + MathHelper.clamp(pitchDelta, -8.0F, 8.0F));
    }

    private void faceElytraClimb(ClientPlayerEntity player, double dx, double dz, AutomationStyle style) {
        if (dx * dx + dz * dz > 0.0001) {
            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float maxTurn = style == AutomationStyle.AGGRESSIVE ? 22.0F : 12.0F;
            float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
            player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -maxTurn, maxTurn));
        }

        float pitchDelta = MathHelper.wrapDegrees(-24.0F - player.getPitch());
        player.setPitch(player.getPitch() + MathHelper.clamp(pitchDelta, -10.0F, 10.0F));
    }

    private double elytraCruiseAltitude(MinecraftClient client) {
        if (client.world == null) {
            return ELYTRA_CRUISE_ALTITUDE;
        }
        return Math.min(ELYTRA_CRUISE_ALTITUDE, client.world.getTopYInclusive() - 24.0);
    }

    private boolean hasEquippedElytra(ClientPlayerEntity player) {
        return player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    private int findFirework(ClientPlayerEntity player) {
        for (int slot = 0; slot < player.getInventory().getMainStacks().size(); slot++) {
            ItemStack stack = player.getInventory().getMainStacks().get(slot);
            if (stack.isOf(Items.FIREWORK_ROCKET)) {
                return slot;
            }
        }
        return -1;
    }

    private void requestReplan(MinecraftClient client, RouteStep target) {
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

    private LocalPathPlanner.PathStep nextWaypoint(ClientPlayerEntity player) {
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

    private boolean isAtWaypoint(ClientPlayerEntity player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= WAYPOINT_DISTANCE_BLOCKS && Math.abs(player.getY() - pos.getY()) <= 1.35;
    }

    private void updateProgress(ClientPlayerEntity player, RouteStep target, LocalPathPlanner.PathStep waypoint) {
        BlockPos navigationTarget = navigationTarget(player, target);
        double distance = Math.sqrt(squaredHorizontalDistance(player, navigationTarget));
        double waypointDistance = Math.sqrt(squaredHorizontalDistance(player, waypoint.pos()));
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
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

        if (player.horizontalCollision || stuckTicks >= STUCK_TICKS_LIMIT) {
            replanCooldown = 0;
            stuckTicks = 0;
            recoveryTicks = RECOVERY_TICKS;
        }
    }

    private double squaredHorizontalDistance(ClientPlayerEntity player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dz * dz;
    }

    private int findAllowedFood(ClientPlayerEntity player) {
        for (int slot = 0; slot < player.getInventory().getMainStacks().size(); slot++) {
            ItemStack stack = player.getInventory().getMainStacks().get(slot);
            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            if (food == null) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (config.allowsFood(id)) {
                return slot;
            }
        }
        return -1;
    }

    private int findAllowedPlaceBlock(ClientPlayerEntity player) {
        for (int slot = 0; slot < player.getInventory().getMainStacks().size(); slot++) {
            ItemStack stack = player.getInventory().getMainStacks().get(slot);
            if (!(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (config.allowsPlace(id)) {
                return slot;
            }
        }
        return -1;
    }

    private int findBoat(ClientPlayerEntity player) {
        for (int slot = 0; slot < player.getInventory().getMainStacks().size(); slot++) {
            ItemStack stack = player.getInventory().getMainStacks().get(slot);
            if (stack.getItem() instanceof BoatItem) {
                return slot;
            }
        }
        return -1;
    }

    private boolean selectOrMoveToHotbar(MinecraftClient client, ClientPlayerEntity player, int inventorySlot) {
        if (inventorySlot < 0) {
            return false;
        }
        if (inventorySlot < 9) {
            player.getInventory().setSelectedSlot(inventorySlot);
            return true;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (client.interactionManager == null) {
            return false;
        }
        client.interactionManager.clickSlot(
                player.currentScreenHandler.syncId,
                inventorySlot,
                selected,
                SlotActionType.SWAP,
                player
        );
        player.getInventory().setSelectedSlot(selected);
        return false;
    }

    private BlockHitResult placementHit(MinecraftClient client, BlockPos placePos) {
        BlockPos below = placePos.down();
        if (isSolid(client, below)) {
            return new BlockHitResult(below.toCenterPos().add(0.0, 0.5, 0.0), Direction.UP, below, false);
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = placePos.offset(direction);
            if (isSolid(client, neighbor)) {
                return new BlockHitResult(neighbor.toCenterPos(), direction.getOpposite(), neighbor, false);
            }
        }
        return null;
    }

    private boolean isSolid(MinecraftClient client, BlockPos pos) {
        if (client.world == null) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        return !state.getCollisionShape(client.world, pos).isEmpty();
    }

    private BlockPos breakableObstacleAhead(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || !config.blockBreakingEnabled()) {
            return null;
        }

        Direction direction = player.getHorizontalFacing();
        BlockPos feet = player.getBlockPos().offset(direction);
        BlockPos head = feet.up();
        if (isBreakableObstacle(client, feet)) {
            return feet;
        }
        if (isBreakableObstacle(client, head)) {
            return head;
        }
        return null;
    }

    private boolean isBreakableObstacle(MinecraftClient client, BlockPos pos) {
        if (client.world == null) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        if (state.getCollisionShape(client.world, pos).isEmpty()) {
            return false;
        }
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        return config.allowsBreak(blockId);
    }

    private void setMovementKeys(
            MinecraftClient client,
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
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(right);
        client.options.jumpKey.setPressed(jump);
        client.options.sneakKey.setPressed(sneak);
        client.options.sprintKey.setPressed(sprint);
        movementKeysHeld = forward || back || left || right || jump || sneak || sprint;
        sendPlayerInput(client, forward, back, left, right, jump, sneak, sprint);
    }

    private void releaseMovementKeys(MinecraftClient client) {
        if (client.options == null || !movementKeysHeld) {
            return;
        }
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        movementKeysHeld = false;
        sendPlayerInput(client, false, false, false, false, false, false, false);
    }

    private void setDirectionalMovementKeys(
            MinecraftClient client,
            ClientPlayerEntity player,
            double dx,
            double dz,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 0.0001) {
            setMovementKeys(client, false, false, false, false, jump, sneak, false);
            return;
        }

        double yawRadians = Math.toRadians(player.getYaw());
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double leftX = Math.cos(yawRadians);
        double leftZ = Math.sin(yawRadians);
        double targetX = dx / length;
        double targetZ = dz / length;
        double forwardDot = targetX * forwardX + targetZ * forwardZ;
        double leftDot = targetX * leftX + targetZ * leftZ;

        boolean forward = forwardDot > AGGRESSIVE_INPUT_THRESHOLD;
        boolean back = forwardDot < -AGGRESSIVE_INPUT_THRESHOLD;
        boolean left = leftDot > AGGRESSIVE_INPUT_THRESHOLD;
        boolean right = leftDot < -AGGRESSIVE_INPUT_THRESHOLD;
        if (!forward && !back && !left && !right) {
            if (Math.abs(forwardDot) >= Math.abs(leftDot)) {
                forward = forwardDot >= 0.0;
                back = !forward;
            } else {
                left = leftDot >= 0.0;
                right = !left;
            }
        }

        setMovementKeys(client, forward, back, left, right, jump, sneak, sprint && forward && !back);
    }

    private void setAttackKey(MinecraftClient client, boolean pressed) {
        if (client.options == null) {
            return;
        }
        client.options.attackKey.setPressed(pressed);
        attackKeyHeld = pressed;
    }

    private void releaseAttackKey(MinecraftClient client) {
        if (client.options == null || !attackKeyHeld) {
            return;
        }
        client.options.attackKey.setPressed(false);
        attackKeyHeld = false;
    }

    private void pressUse(MinecraftClient client, boolean pressed) {
        if (client.options == null) {
            return;
        }
        client.options.useKey.setPressed(pressed);
        useKeyHeld = pressed;
    }

    private void releaseUseKey(MinecraftClient client) {
        if (client.options == null || !useKeyHeld) {
            return;
        }
        client.options.useKey.setPressed(false);
        useKeyHeld = false;
    }

    private void sendPlayerInput(
            MinecraftClient client,
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
        client.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
                new PlayerInput(forward, back, left, right, jump, sneak, sprint)
        ));
    }

    private float faceMovement(ClientPlayerEntity player, double dx, double dz) {
        if (dx * dx + dz * dz <= 0.0001) {
            return 0.0F;
        }
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -MOVEMENT_TURN_DEGREES, MOVEMENT_TURN_DEGREES));
        return Math.abs(yawDelta);
    }

    private void faceBlock(ClientPlayerEntity player, BlockPos block) {
        face(
                player,
                block.getX() + 0.5 - player.getX(),
                block.getY() + 0.5 - player.getEyeY(),
                block.getZ() + 0.5 - player.getZ(),
                true
        );
    }

    private void face(ClientPlayerEntity player, double dx, double dy, double dz, boolean includePitch) {
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float nextYaw = player.getYaw() + MathHelper.clamp(yawDelta, -35.0F, 35.0F);
        player.setYaw(nextYaw);

        if (includePitch) {
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
            float pitchDelta = MathHelper.wrapDegrees(targetPitch - player.getPitch());
            player.setPitch(player.getPitch() + MathHelper.clamp(pitchDelta, -25.0F, 25.0F));
        }
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
        lastPlayerPos = Vec3d.ZERO;
        targetSignature = null;
        breakingBlock = null;
        recoveryTicks = 0;
        elytraStartCooldown = 0;
        fireworkCooldown = 0;
        cancelPendingPlan();
    }

    private boolean arrivedAtNavigationTarget(ClientPlayerEntity player, RouteStep target) {
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

    private BlockPos navigationTarget(ClientPlayerEntity player, RouteStep target) {
        if (target.state() == RouteStepState.OPENED) {
            return new BlockPos(target.targetBlock().x(), player.getBlockPos().getY(), target.targetBlock().z());
        }
        int targetX = MathHelper.clamp(
                player.getBlockPos().getX(),
                target.region().bounds().minX(),
                target.region().bounds().maxX()
        );
        int targetZ = MathHelper.clamp(
                player.getBlockPos().getZ(),
                target.region().bounds().minZ(),
                target.region().bounds().maxZ()
        );
        return new BlockPos(targetX, player.getBlockPos().getY(), targetZ);
    }

    public record MovementResult(boolean moving, boolean waitingForChunk, Text pauseMessage, List<BlockPos> path) {
        static MovementResult none() {
            return new MovementResult(false, false, null, List.of());
        }

        static MovementResult active(List<BlockPos> path) {
            return new MovementResult(true, false, null, path);
        }

        static MovementResult pause(Text message) {
            return new MovementResult(false, false, message, List.of());
        }

        public boolean shouldPause() {
            return pauseMessage != null;
        }
    }
}

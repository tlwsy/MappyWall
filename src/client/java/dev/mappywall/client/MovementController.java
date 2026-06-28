package dev.mappywall.client;

import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RunMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BoatItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class MovementController {
    private static final int HOTBAR_CONTAINER_OFFSET = 36;
    private static final double ARRIVAL_DISTANCE_BLOCKS = 4.0;
    private static final double WAYPOINT_DISTANCE_BLOCKS = 1.75;
    private static final double STUCK_EPSILON = 0.06;
    private static final int STUCK_TICKS_LIMIT = 70;
    private static final int REPLAN_INTERVAL_TICKS = 40;
    private static final int PLACE_COOLDOWN_TICKS = 8;
    private static final int BOAT_COOLDOWN_TICKS = 40;
    private static final int EAT_COOLDOWN_TICKS = 20;

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
    private double lastDistance = Double.MAX_VALUE;
    private String targetSignature;
    private BlockPos breakingBlock;
    private boolean movementKeysHeld;
    private boolean useKeyHeld;

    public MovementResult tick(MinecraftClient client, MapWallSave save, RouteStep target) {
        if (client.world == null || client.player == null || target == null) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        if (save.project().mode() != RunMode.AUTO_WALK) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        ClientPlayerEntity player = client.player;
        if (target.region().bounds().contains(player.getX(), player.getZ())) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        tickCooldowns();

        if (tryEat(client, player)) {
            return MovementResult.active(pathSnapshot());
        }

        if (needsNewPath(target)) {
            replan(client, target);
        }

        LocalPathPlanner.PathStep waypoint = nextWaypoint(player);
        if (waypoint == null) {
            replan(client, target);
            waypoint = nextWaypoint(player);
        }
        if (waypoint == null) {
            failedReplans++;
            release(client);
            if (failedReplans >= 3) {
                resetProgress();
                return MovementResult.pause(Text.translatable("message.mappywall.auto_walk_no_path"));
            }
            return MovementResult.active(pathSnapshot());
        }

        failedReplans = 0;
        MovementResult actionResult = executeStep(client, player, waypoint);
        updateProgress(player, target, waypoint);
        return actionResult;
    }

    public void release(MinecraftClient client) {
        if (client.options == null || (!movementKeysHeld && !useKeyHeld)) {
            return;
        }
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.useKey.setPressed(false);
        movementKeysHeld = false;
        useKeyHeld = false;
        breakingBlock = null;
    }

    public boolean isWaitingForChunk() {
        return false;
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
            case WALK -> moveToward(client, player, waypoint, player.horizontalCollision);
        };
    }

    private MovementResult dropToward(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        double drop = player.getY() - waypoint.pos().getY();
        boolean careful = drop >= 3.75;
        return moveToward(client, player, waypoint, false, careful, !careful);
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
        updateLook(player, targetX - player.getX(), pos.getY() + 0.2 - player.getEyeY(), targetZ - player.getZ(), false);
        setMovementKeys(client, true, false, false, false, jump, sneak, sprint);
        return MovementResult.active(pathSnapshot());
    }

    private MovementResult swimOrBoat(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        if (!player.hasVehicle() && boatCooldown <= 0 && tryUseBoat(client, player, waypoint)) {
            boatCooldown = BOAT_COOLDOWN_TICKS;
            return MovementResult.active(pathSnapshot());
        }
        return moveToward(client, player, waypoint, true);
    }

    private MovementResult breakBlock(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        if (client.interactionManager == null || waypoint.actionBlock() == null) {
            return moveToward(client, player, waypoint, true);
        }
        BlockPos block = waypoint.actionBlock();
        if (client.world.getBlockState(block).getCollisionShape(client.world, block).isEmpty()) {
            breakingBlock = null;
            pathIndex++;
            replanCooldown = 0;
            return MovementResult.active(pathSnapshot());
        }

        releaseMovementKeys(client);
        faceBlock(player, block);
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
        face(player, hit.getPos().x - player.getX(), hit.getPos().y - player.getEyeY(), hit.getPos().z - player.getZ(), true);
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

    private boolean tryUseBoat(MinecraftClient client, ClientPlayerEntity player, LocalPathPlanner.PathStep waypoint) {
        if (client.interactionManager == null || client.world == null) {
            return false;
        }
        BlockPos waterPos = waypoint.pos();
        if (!player.isTouchingWater() && !client.world.getFluidState(waterPos).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
            return false;
        }
        int slot = findBoat(player);
        if (slot < 0) {
            return false;
        }
        if (!selectOrMoveToHotbar(client, player, slot)) {
            return true;
        }
        face(
                player,
                waterPos.getX() + 0.5 - player.getX(),
                waterPos.getY() + 0.2 - player.getEyeY(),
                waterPos.getZ() + 0.5 - player.getZ(),
                true
        );
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private void replan(MinecraftClient client, RouteStep target) {
        if (client.player == null) {
            path = List.of();
            return;
        }
        LocalPathPlanner.PathPlan plan = pathPlanner.plan(client.player, target, config);
        path = plan.steps();
        pathIndex = 0;
        replanCooldown = REPLAN_INTERVAL_TICKS;
        targetSignature = target.region().signature();
        breakingBlock = null;
    }

    private boolean needsNewPath(RouteStep target) {
        replanCooldown--;
        return path.isEmpty()
                || pathIndex >= path.size()
                || replanCooldown <= 0
                || !target.region().signature().equals(targetSignature);
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
        double distance = Math.sqrt(target.targetBlock().distanceSquaredTo(player.getX(), player.getZ()));
        if (distance < lastDistance - STUCK_EPSILON) {
            stuckTicks = 0;
        } else if (waypoint.action() == LocalPathPlanner.StepAction.WALK
                || waypoint.action() == LocalPathPlanner.StepAction.JUMP
                || waypoint.action() == LocalPathPlanner.StepAction.DROP
                || waypoint.action() == LocalPathPlanner.StepAction.SWIM) {
            stuckTicks++;
        }
        lastDistance = distance;

        if (player.horizontalCollision || stuckTicks >= STUCK_TICKS_LIMIT) {
            replanCooldown = 0;
            stuckTicks = 0;
        }
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
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(right);
        client.options.jumpKey.setPressed(jump);
        client.options.sneakKey.setPressed(sneak);
        client.options.sprintKey.setPressed(sprint);
        movementKeysHeld = forward || back || left || right || jump || sneak || sprint;
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
    }

    private void pressUse(MinecraftClient client, boolean pressed) {
        if (client.options == null) {
            return;
        }
        client.options.useKey.setPressed(pressed);
        useKeyHeld = pressed;
    }

    private void updateLook(ClientPlayerEntity player, double dx, double dy, double dz, boolean includePitch) {
        face(player, dx, dy, dz, includePitch);
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
    }

    private void resetProgress() {
        path = List.of();
        pathIndex = 0;
        replanCooldown = 0;
        stuckTicks = 0;
        failedReplans = 0;
        lastDistance = Double.MAX_VALUE;
        targetSignature = null;
        breakingBlock = null;
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

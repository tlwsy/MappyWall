package dev.mappywall.client;

import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RunMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

public final class MovementController {
    private static final double ARRIVAL_DISTANCE_BLOCKS = 6.0;
    private static final double STUCK_EPSILON = 0.08;
    private static final int STUCK_TICKS_LIMIT = 80;
    private static final int WALK_ON_TICKS = 4;
    private static final int WALK_CYCLE_TICKS = 7;
    private static final int LOOKAHEAD_BLOCKS = 32;

    private int tickCounter;
    private int stuckTicks;
    private double lastDistance = Double.MAX_VALUE;
    private boolean waitingForChunk;

    public MovementResult tick(MinecraftClient client, MapWallSave save, RouteStep target) {
        if (client.world == null || client.player == null || target == null) {
            release(client);
            return MovementResult.none();
        }

        if (save.project().mode() != RunMode.AUTO_WALK) {
            release(client);
            return MovementResult.none();
        }

        ClientPlayerEntity player = client.player;
        if (target.region().bounds().contains(player.getX(), player.getZ())) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        double targetX = target.targetBlock().x() + 0.5;
        double targetZ = target.targetBlock().z() + 0.5;
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance <= ARRIVAL_DISTANCE_BLOCKS) {
            release(client);
            resetProgress();
            return MovementResult.none();
        }

        if (!isLookaheadChunkLoaded(client, player, dx, dz, distance)) {
            release(client);
            waitingForChunk = true;
            return MovementResult.waiting();
        }
        waitingForChunk = false;

        updateYaw(player, dx, dz);
        tickCounter++;
        boolean walkThisTick = tickCounter % WALK_CYCLE_TICKS < WALK_ON_TICKS;
        client.options.forwardKey.setPressed(walkThisTick);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);

        if (distance < lastDistance - STUCK_EPSILON) {
            stuckTicks = 0;
        } else if (walkThisTick) {
            stuckTicks++;
        }
        lastDistance = distance;

        if (player.horizontalCollision || stuckTicks >= STUCK_TICKS_LIMIT) {
            release(client);
            resetProgress();
            return MovementResult.pause(Text.translatable("message.mappywall.auto_walk_stuck"));
        }

        return MovementResult.active();
    }

    public void release(MinecraftClient client) {
        waitingForChunk = false;
        if (client.options == null) {
            return;
        }
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    public boolean isWaitingForChunk() {
        return waitingForChunk;
    }

    private void updateYaw(ClientPlayerEntity player, double dx, double dz) {
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float delta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float maxTurn = 12.0F;
        float nextYaw = player.getYaw() + MathHelper.clamp(delta, -maxTurn, maxTurn);
        player.setYaw(nextYaw);
    }

    private boolean isLookaheadChunkLoaded(MinecraftClient client, ClientPlayerEntity player, double dx, double dz, double distance) {
        double factor = Math.min(LOOKAHEAD_BLOCKS, distance) / distance;
        double checkX = player.getX() + dx * factor;
        double checkZ = player.getZ() + dz * factor;
        BlockPos checkPos = BlockPos.ofFloored(checkX, player.getY(), checkZ);
        return client.world != null && client.world.isChunkLoaded(checkPos);
    }

    private void resetProgress() {
        stuckTicks = 0;
        lastDistance = Double.MAX_VALUE;
    }

    public record MovementResult(boolean moving, boolean waitingForChunk, Text pauseMessage) {
        static MovementResult none() {
            return new MovementResult(false, false, null);
        }

        static MovementResult active() {
            return new MovementResult(true, false, null);
        }

        static MovementResult waiting() {
            return new MovementResult(false, true, null);
        }

        static MovementResult pause(Text message) {
            return new MovementResult(false, false, message);
        }

        public boolean shouldPause() {
            return pauseMessage != null;
        }
    }
}

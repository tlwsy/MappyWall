package dev.mappywall.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class WorldTargetRenderer {
    private static final double FAR_MARKER_DISTANCE = 160.0;
    private static final double BEAM_BOTTOM_OFFSET = -48.0;
    private static final double BEAM_TOP_OFFSET = 192.0;

    private WorldTargetRenderer() {
    }

    public static void register(MappyWallRuntime runtime) {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> render(context, runtime));
    }

    private static void render(LevelRenderContext context, MappyWallRuntime runtime) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        runtime.renderTarget(client).ifPresent(target -> renderTarget(context, client, target));
    }

    private static void renderTarget(
            LevelRenderContext context,
            Minecraft client,
            MappyWallRuntime.RenderTarget target
    ) {
        PoseStack matrices = context.poseStack();
        VertexConsumer vertices = context.bufferSource().getBuffer(RenderTypes.linesTranslucent());
        Vec3 camera = context.gameRenderer().getMainCamera().position();

        double playerY = client.player.getY();
        double centerX = target.targetX() + 0.5;
        double centerZ = target.targetZ() + 0.5;

        renderWaypointBeam(matrices, vertices, camera, playerY, centerX, centerZ);
        if (target.showPath()) {
            renderPath(matrices, vertices, camera, client, target);
        }
    }

    private static void renderWaypointBeam(
            PoseStack matrices,
            VertexConsumer vertices,
            Vec3 camera,
            double playerY,
            double targetX,
            double targetZ
    ) {
        double dx = targetX - camera.x;
        double dz = targetZ - camera.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double markerX = targetX;
        double markerZ = targetZ;
        if (distance > FAR_MARKER_DISTANCE) {
            markerX = camera.x + dx / distance * FAR_MARKER_DISTANCE;
            markerZ = camera.z + dz / distance * FAR_MARKER_DISTANCE;
        }

        double bottomY = playerY + BEAM_BOTTOM_OFFSET;
        double topY = playerY + BEAM_TOP_OFFSET;
        double centerY = playerY + 8.0;
        line(matrices, vertices, camera, markerX, bottomY, markerZ, markerX, topY, markerZ, 64, 224, 255, 255);
        line(matrices, vertices, camera, markerX - 3.0, centerY, markerZ, markerX + 3.0, centerY, markerZ, 64, 224, 255, 255);
        line(matrices, vertices, camera, markerX, centerY, markerZ - 3.0, markerX, centerY, markerZ + 3.0, 64, 224, 255, 255);
    }

    private static void renderPath(
            PoseStack matrices,
            VertexConsumer vertices,
            Vec3 camera,
            Minecraft client,
            MappyWallRuntime.RenderTarget target
    ) {
        double previousX = client.player.getX();
        double previousY = client.player.getY() + 0.25;
        double previousZ = client.player.getZ();
        for (BlockPos pos : target.path()) {
            double nextX = pos.getX() + 0.5;
            double nextY = pos.getY() + 0.25;
            double nextZ = pos.getZ() + 0.5;
            line(matrices, vertices, camera, previousX, previousY, previousZ, nextX, nextY, nextZ, 255, 216, 72, 255);
            previousX = nextX;
            previousY = nextY;
            previousZ = nextZ;
        }
    }

    private static void line(
            PoseStack matrices,
            VertexConsumer vertices,
            Vec3 camera,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        double normalX = endX - startX;
        double normalY = endY - startY;
        double normalZ = endZ - startZ;
        double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        if (length <= 0.0001) {
            return;
        }

        float nx = (float) (normalX / length);
        float ny = (float) (normalY / length);
        float nz = (float) (normalZ / length);
        PoseStack.Pose pose = matrices.last();
        vertices.addVertex(pose, (float) (startX - camera.x), (float) (startY - camera.y), (float) (startZ - camera.z))
                .setColor(red, green, blue, alpha)
                .setLineWidth(2.5F)
                .setNormal(pose, nx, ny, nz);
        vertices.addVertex(pose, (float) (endX - camera.x), (float) (endY - camera.y), (float) (endZ - camera.z))
                .setColor(red, green, blue, alpha)
                .setLineWidth(2.5F)
                .setNormal(pose, nx, ny, nz);
    }
}

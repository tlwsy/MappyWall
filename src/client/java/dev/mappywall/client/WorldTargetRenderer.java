package dev.mappywall.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class WorldTargetRenderer {
    private static final double FAR_MARKER_DISTANCE = 160.0;
    private static final double BEAM_BOTTOM_OFFSET = -48.0;
    private static final double BEAM_TOP_OFFSET = 192.0;

    private static final RenderLayer TARGET_LINES = RenderLayer.of(
            "mappywall_target_lines",
            RenderSetup.builder(RenderPipelines.LINES_TRANSLUCENT)
                    .expectedBufferSize(1536)
                    .translucent()
                    .build()
    );

    private WorldTargetRenderer() {
    }

    public static void register(MappyWallRuntime runtime) {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(context -> render(context, runtime));
    }

    private static void render(WorldRenderContext context, MappyWallRuntime runtime) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        runtime.renderTarget(client).ifPresent(target -> renderTarget(context, client, target));
    }

    private static void renderTarget(
            WorldRenderContext context,
            MinecraftClient client,
            MappyWallRuntime.RenderTarget target
    ) {
        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) {
            return;
        }

        VertexConsumer vertices = consumers.getBuffer(TARGET_LINES);
        Vec3d camera = context.gameRenderer().getCamera().getCameraPos();

        double playerY = client.player.getY();
        double centerX = target.targetX() + 0.5;
        double centerZ = target.targetZ() + 0.5;

        renderWaypointBeam(matrices, vertices, camera, playerY, centerX, centerZ);

        if (target.showPath()) {
            renderPath(matrices, vertices, camera, client, target, centerX, centerZ);
        }
    }

    private static void renderWaypointBeam(
            MatrixStack matrices,
            VertexConsumer vertices,
            Vec3d camera,
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
        line(matrices, vertices, camera, markerX - 2.0, centerY - 2.0, markerZ, markerX, centerY, markerZ + 2.0, 64, 224, 255, 255);
        line(matrices, vertices, camera, markerX, centerY, markerZ + 2.0, markerX + 2.0, centerY - 2.0, markerZ, 64, 224, 255, 255);
        line(matrices, vertices, camera, markerX + 2.0, centerY - 2.0, markerZ, markerX, centerY - 4.0, markerZ - 2.0, 64, 224, 255, 255);
        line(matrices, vertices, camera, markerX, centerY - 4.0, markerZ - 2.0, markerX - 2.0, centerY - 2.0, markerZ, 64, 224, 255, 255);
    }

    private static void renderPath(
            MatrixStack matrices,
            VertexConsumer vertices,
            Vec3d camera,
            MinecraftClient client,
            MappyWallRuntime.RenderTarget target,
            double centerX,
            double centerZ
    ) {
        double previousX = client.player.getX();
        double previousY = client.player.getY() + 0.25;
        double previousZ = client.player.getZ();
        if (target.path().isEmpty()) {
            return;
        }

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
            MatrixStack matrices,
            VertexConsumer vertices,
            Vec3d camera,
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
        float relativeStartX = (float) (startX - camera.x);
        float relativeStartY = (float) (startY - camera.y);
        float relativeStartZ = (float) (startZ - camera.z);
        float relativeEndX = (float) (endX - camera.x);
        float relativeEndY = (float) (endY - camera.y);
        float relativeEndZ = (float) (endZ - camera.z);

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
        MatrixStack.Entry entry = matrices.peek();
        vertices.vertex(entry, relativeStartX, relativeStartY, relativeStartZ)
                .color(red, green, blue, alpha)
                .lineWidth(2.5F)
                .normal(entry, nx, ny, nz);
        vertices.vertex(entry, relativeEndX, relativeEndY, relativeEndZ)
                .color(red, green, blue, alpha)
                .lineWidth(2.5F)
                .normal(entry, nx, ny, nz);
    }
}

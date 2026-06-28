package dev.mappywall.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class WorldTargetRenderer {
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
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> render(context, runtime));
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
        VertexConsumer vertices = context.consumers().getBuffer(TARGET_LINES);
        Vec3d camera = context.gameRenderer().getCamera().getCameraPos();

        double minX = target.minX();
        double maxX = target.maxX() + 1.0;
        double minZ = target.minZ();
        double maxZ = target.maxZ() + 1.0;
        double playerY = client.player.getY();
        double groundY = playerY + 0.08;
        double markerTopY = playerY + 12.0;
        double centerX = target.targetX() + 0.5;
        double centerZ = target.targetZ() + 0.5;

        line(matrices, vertices, camera, minX, groundY, minZ, maxX, groundY, minZ, 64, 224, 255, 255);
        line(matrices, vertices, camera, maxX, groundY, minZ, maxX, groundY, maxZ, 64, 224, 255, 255);
        line(matrices, vertices, camera, maxX, groundY, maxZ, minX, groundY, maxZ, 64, 224, 255, 255);
        line(matrices, vertices, camera, minX, groundY, maxZ, minX, groundY, minZ, 64, 224, 255, 255);
        line(matrices, vertices, camera, centerX, groundY, centerZ, centerX, markerTopY, centerZ, 64, 224, 255, 255);

        if (target.showPath()) {
            renderPath(matrices, vertices, camera, client, target, centerX, centerZ);
        }
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
            line(matrices, vertices, camera, previousX, previousY, previousZ, centerX, previousY, centerZ, 255, 216, 72, 255);
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

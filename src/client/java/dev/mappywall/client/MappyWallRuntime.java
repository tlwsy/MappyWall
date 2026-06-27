package dev.mappywall.client;

import dev.mappywall.core.BindingRepairResult;
import dev.mappywall.core.BindingVerification;
import dev.mappywall.core.HangingOrderFormatter;
import dev.mappywall.core.InventoryMapIndex;
import dev.mappywall.core.MapWallPlanner;
import dev.mappywall.core.MapWallProject;
import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.ObservedMap;
import dev.mappywall.core.PlayerBlockPos;
import dev.mappywall.core.ProjectStatus;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RunMode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class MappyWallRuntime {
    private static final int SAVE_INTERVAL_TICKS = 100;

    private final MapWallPlanner planner = new MapWallPlanner();
    private final PersistenceBridge persistence = new PersistenceBridge();
    private final InventoryMapIndex mapIndex = new InventoryMapIndex();
    private final InventoryMapScanner inventoryScanner = new InventoryMapScanner();
    private final MapOpenController mapOpenController = new MapOpenController(inventoryScanner);
    private final MovementController movementController = new MovementController();
    private final HangingOrderFormatter hangingOrderFormatter = new HangingOrderFormatter();

    private MapWallSave activeSave;
    private Path activePath;
    private int ticksSinceSave;
    private int emptyMapCount;

    public void openConfigScreen(Minecraft client) {
        client.setScreen(new MapWallConfigScreen(this));
    }

    public void startManualRun(Minecraft client, int scale, int width, int height) {
        if (!hasUsableWorld(client)) {
            return;
        }

        String serverKey = serverKey(client);
        String dimension = dimensionKey(client);
        String id = UUID.randomUUID().toString();
        MapWallProject project = planner.createProject(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                client.player.getX(),
                client.player.getZ(),
                RunMode.MANUAL
        );
        activeSave = planner.createSave(project);
        activePath = persistence.projectPath(serverKey, dimension, id);
        saveNow(client);
        client.player.displayClientMessage(Component.translatable("message.mappywall.started"), false);
    }

    public void togglePause(Minecraft client) {
        if (activeSave == null) {
            if (hasUsableWorld(client)) {
                client.player.displayClientMessage(Component.translatable("message.mappywall.no_project"), false);
            }
            return;
        }

        boolean paused = !activeSave.session().paused();
        ProjectStatus status = paused ? ProjectStatus.PAUSED : ProjectStatus.RUNNING;
        activeSave = activeSave
                .withProject(activeSave.project().withStatus(status))
                .withSession(activeSave.session().withPaused(paused));
        saveNow(client);

        Component message = Component.translatable(paused ? "message.mappywall.paused" : "message.mappywall.resumed");
        client.player.displayClientMessage(message, false);
    }

    public void tick(Minecraft client) {
        if (!hasUsableWorld(client)) {
            return;
        }

        if (activeSave == null) {
            loadMostRecentProject(client);
        }

        emptyMapCount = inventoryScanner.countEmptyMaps(client.player);
        if (activeSave == null || activeSave.project().status() == ProjectStatus.COMPLETE) {
            return;
        }

        activeSave = activeSave.withSession(activeSave.session().withLastPlayerPos(new PlayerBlockPos(
                client.player.blockPosition().getX(),
                client.player.blockPosition().getY(),
                client.player.blockPosition().getZ()
        )));

        if (activeSave.session().paused()) {
            periodicSave(client);
            return;
        }

        repairManualBindings(client);
        if (activeSave.project().mode().isAutomatic()) {
            movementController.tick(client, activeSave);
        }

        RouteStep target = planner.nextOpenStep(activeSave);
        if (target != null && target.region().bounds().contains(client.player.getX(), client.player.getZ())) {
            Optional<Integer> openedMapId = mapOpenController.tryOpenMapAtTarget(client, target);
            if (openedMapId.isPresent()) {
                activeSave = planner.bindCurrentStep(
                        activeSave,
                        openedMapId.get(),
                        Instant.now(),
                        BindingVerification.TARGET_CAPTURE
                );
                saveNow(client);
            }
        }

        if (planner.nextOpenStep(activeSave) == null && activeSave.project().status() != ProjectStatus.COMPLETE) {
            activeSave = activeSave.withProject(activeSave.project().withStatus(ProjectStatus.COMPLETE));
            saveNow(client);
            showCompletionOrder(client);
        }

        periodicSave(client);
    }

    public List<Component> hudLines(Minecraft client) {
        List<Component> lines = new ArrayList<>();
        if (activeSave == null) {
            return lines;
        }

        RouteStep target = planner.nextOpenStep(activeSave);
        int completed = activeSave.bindings().size();
        int total = activeSave.route().size();
        lines.add(Component.literal("MappyWall " + completed + "/" + total).withStyle(ChatFormatting.AQUA));

        if (activeSave.project().status() == ProjectStatus.COMPLETE) {
            lines.add(Component.translatable("hud.mappywall.complete").withStyle(ChatFormatting.GREEN));
        } else if (activeSave.session().paused()) {
            lines.add(Component.translatable("hud.mappywall.paused").withStyle(ChatFormatting.YELLOW));
        }

        lines.add(Component.translatable("hud.mappywall.empty_maps").append(": " + emptyMapCount));
        if (target != null) {
            double distance = Math.sqrt(target.targetBlock().distanceSquaredTo(client.player.getX(), client.player.getZ()));
            lines.add(Component.literal("Target " + target.targetBlock().x() + ", " + target.targetBlock().z()
                    + " (" + Math.round(distance) + " blocks)"));
            lines.add(Component.literal("Wall " + (target.wallPos().column() + 1) + ", " + (target.wallPos().row() + 1)));
        }

        if (activeSave.project().mode().isAutomatic()) {
            lines.add(Component.translatable("message.mappywall.auto_disabled").withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    public int defaultScale() {
        return 0;
    }

    private void repairManualBindings(Minecraft client) {
        List<ObservedMap> observedMaps = inventoryScanner.scanFilledMaps(client);
        BindingRepairResult result = mapIndex.repairManualOpenings(activeSave, observedMaps, Instant.now());
        if (!result.bindings().equals(activeSave.bindings())) {
            activeSave = activeSave.withBindings(result.bindings());
            saveNow(client);
        }
        if (result.hasWarnings()) {
            activeSave = activeSave.withProject(activeSave.project().withStatus(ProjectStatus.CONFLICT))
                    .withSession(activeSave.session().withPaused(true).withWarnings(result.warnings()));
            client.player.displayClientMessage(Component.literal(result.warnings().getFirst()).withStyle(ChatFormatting.RED), false);
            saveNow(client);
        }
    }

    private void loadMostRecentProject(Minecraft client) {
        String serverKey = serverKey(client);
        String dimension = dimensionKey(client);
        Optional<PersistenceBridge.LoadedProject> loaded = persistence.loadMostRecent(serverKey, dimension);
        if (loaded.isPresent()) {
            activeSave = loaded.get().save();
            activePath = loaded.get().path();
        }
    }

    private void periodicSave(Minecraft client) {
        ticksSinceSave++;
        if (ticksSinceSave >= SAVE_INTERVAL_TICKS) {
            saveNow(client);
        }
    }

    private void saveNow(Minecraft client) {
        if (activeSave == null || activePath == null) {
            return;
        }
        try {
            persistence.save(activePath, activeSave);
            ticksSinceSave = 0;
        } catch (IOException exception) {
            client.player.displayClientMessage(Component.literal("MappyWall save failed: " + exception.getMessage())
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    private void showCompletionOrder(Minecraft client) {
        client.player.displayClientMessage(Component.literal("MappyWall hanging order:"), false);
        for (String line : hangingOrderFormatter.format(activeSave)) {
            client.player.displayClientMessage(Component.literal(line), false);
        }
    }

    private boolean hasUsableWorld(Minecraft client) {
        return client.player != null && client.level != null;
    }

    private String dimensionKey(Minecraft client) {
        return client.level.dimension().location().toString();
    }

    private String serverKey(Minecraft client) {
        if (client.getCurrentServer() != null) {
            return "server_" + client.getCurrentServer().ip;
        }
        if (client.getSingleplayerServer() != null) {
            return "singleplayer_" + client.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "unknown";
    }

    private static final class PersistenceBridge {
        private final dev.mappywall.core.PersistenceService service = new dev.mappywall.core.PersistenceService();
        private final Path configRoot = FabricLoader.getInstance().getConfigDir().resolve("mappywall");

        Path projectPath(String serverKey, String dimension, String projectId) {
            return service.projectPath(configRoot, serverKey, dimension, projectId);
        }

        void save(Path path, MapWallSave save) throws IOException {
            service.save(path, save);
        }

        Optional<LoadedProject> loadMostRecent(String serverKey, String dimension) {
            Path dimensionDir = configRoot.resolve(sanitize(serverKey)).resolve(sanitize(dimension));
            if (!java.nio.file.Files.isDirectory(dimensionDir)) {
                return Optional.empty();
            }

            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dimensionDir)) {
                return files
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .max((left, right) -> {
                            try {
                                return java.nio.file.Files.getLastModifiedTime(left)
                                        .compareTo(java.nio.file.Files.getLastModifiedTime(right));
                            } catch (IOException exception) {
                                return 0;
                            }
                        })
                        .flatMap(path -> {
                            try {
                                return service.load(path).map(save -> new LoadedProject(path, save));
                            } catch (IOException exception) {
                                return Optional.empty();
                            }
                        });
            } catch (IOException exception) {
                return Optional.empty();
            }
        }

        private static String sanitize(String value) {
            StringBuilder builder = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                    builder.append(c);
                } else {
                    builder.append('_');
                }
            }
            return builder.toString();
        }

        private record LoadedProject(Path path, MapWallSave save) {
        }
    }
}


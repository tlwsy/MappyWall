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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

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
    private WorldContext activeContext;
    private int ticksSinceSave;
    private int emptyMapCount;

    public void openConfigScreen(MinecraftClient client) {
        client.setScreen(new MapWallTasksScreen(this));
    }

    public void openNewProjectScreen(MinecraftClient client) {
        client.setScreen(new MapWallConfigScreen(this));
    }

    public void startManualRun(MinecraftClient client, int scale, int width, int height) {
        if (!hasUsableWorld(client)) {
            return;
        }

        ensureWorldContext(client);
        WorldContext context = currentContext(client);
        if (activeSave != null) {
            activeSave = activeSave
                    .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                    .withSession(activeSave.session().withPaused(true));
            saveNow(client);
        }

        String id = UUID.randomUUID().toString();
        MapWallProject project = planner.createProject(
                id,
                context.serverKey(),
                context.dimension(),
                scale,
                width,
                height,
                client.player.getX(),
                client.player.getZ(),
                RunMode.MANUAL
        );
        activeSave = planner.createSave(project);
        activePath = persistence.projectPath(context.serverKey(), context.dimension(), id);
        activeContext = context;
        mapOpenController.reset();
        saveNow(client);
        client.player.sendMessage(Text.translatable("message.mappywall.started"), false);
        if (scale != 0) {
            client.player.sendMessage(Text.translatable("message.mappywall.scale_requires_existing_maps"), false);
        }
    }

    public void togglePause(MinecraftClient client) {
        if (activeSave == null) {
            if (hasUsableWorld(client)) {
                client.player.sendMessage(Text.translatable("message.mappywall.no_project"), false);
            }
            return;
        }

        boolean paused = !activeSave.session().paused();
        ProjectStatus status = paused ? ProjectStatus.PAUSED : ProjectStatus.RUNNING;
        activeSave = activeSave
                .withProject(activeSave.project().withStatus(status))
                .withSession(activeSave.session().withPaused(paused));
        saveNow(client);

        Text message = Text.translatable(paused ? "message.mappywall.paused" : "message.mappywall.resumed");
        client.player.sendMessage(message, false);
    }

    public void stopActiveProject(MinecraftClient client) {
        if (activeSave == null) {
            if (hasUsableWorld(client)) {
                client.player.sendMessage(Text.translatable("message.mappywall.no_project"), false);
            }
            return;
        }

        activeSave = activeSave
                .withProject(activeSave.project().withStatus(ProjectStatus.STOPPED))
                .withSession(activeSave.session().withPaused(true));
        saveNow(client);
        clearActiveProject();
        if (hasUsableWorld(client)) {
            client.player.sendMessage(Text.translatable("message.mappywall.stopped"), false);
        }
    }

    public void activateProject(MinecraftClient client, String projectId) {
        if (!hasUsableWorld(client)) {
            return;
        }

        WorldContext context = currentContext(client);
        Optional<PersistenceBridge.LoadedProject> loaded = persistence.loadProject(context.serverKey(), context.dimension(), projectId);
        if (loaded.isEmpty()) {
            client.player.sendMessage(Text.translatable("message.mappywall.project_missing"), false);
            return;
        }

        MapWallSave save = loaded.get().save();
        if (save.project().status() == ProjectStatus.COMPLETE) {
            client.player.sendMessage(Text.translatable("message.mappywall.project_inactive"), false);
            return;
        }

        if (activeSave != null && !activeSave.project().id().equals(projectId)) {
            activeSave = activeSave
                    .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                    .withSession(activeSave.session().withPaused(true));
            saveNow(client);
        }

        activeSave = save.withProject(save.project().withStatus(ProjectStatus.RUNNING))
                .withSession(save.session().withPaused(false));
        activePath = loaded.get().path();
        activeContext = context;
        mapOpenController.reset();
        saveNow(client);
        client.player.sendMessage(Text.translatable("message.mappywall.project_activated"), false);
    }

    public void deleteProject(MinecraftClient client, String projectId) {
        if (!hasUsableWorld(client)) {
            return;
        }

        WorldContext context = currentContext(client);
        if (activeSave != null && activeSave.project().id().equals(projectId)) {
            clearActiveProject();
        }

        if (persistence.deleteProject(context.serverKey(), context.dimension(), projectId)) {
            client.player.sendMessage(Text.translatable("message.mappywall.project_deleted"), false);
        } else {
            client.player.sendMessage(Text.translatable("message.mappywall.project_missing"), false);
        }
    }

    public void tick(MinecraftClient client) {
        if (!hasUsableWorld(client)) {
            activeSave = null;
            activePath = null;
            activeContext = null;
            mapOpenController.reset();
            return;
        }

        ensureWorldContext(client);
        if (activeSave == null) {
            loadMostRecentProject(client);
        }

        emptyMapCount = inventoryScanner.countEmptyMaps(client.player);
        if (activeSave == null) {
            return;
        }

        if (activeSave.project().status() == ProjectStatus.COMPLETE) {
            clearActiveProject();
            return;
        }

        if (client.isPaused()) {
            periodicSave(client);
            return;
        }

        activeSave = activeSave.withSession(activeSave.session().withLastPlayerPos(new PlayerBlockPos(
                client.player.getBlockPos().getX(),
                client.player.getBlockPos().getY(),
                client.player.getBlockPos().getZ()
        )));

        repairManualBindings(client);
        if (activeSave.session().paused()) {
            periodicSave(client);
            return;
        }

        if (activeSave.project().mode().isAutomatic()) {
            movementController.tick(client, activeSave);
        }

        RouteStep target = planner.nextOpenStep(activeSave);
        if (target != null
                && target.region().scale() == 0
                && target.region().bounds().contains(client.player.getX(), client.player.getZ())) {
            MapOpenController.MapOpenAttempt openAttempt = mapOpenController.tryOpenMapInRegion(client, target);
            if (openAttempt.openedMapIdOptional().isPresent()) {
                activeSave = planner.bindCurrentStep(
                        activeSave,
                        openAttempt.openedMapIdOptional().get(),
                        Instant.now(),
                        BindingVerification.TARGET_CAPTURE
                );
                saveNow(client);
            } else if (openAttempt.shouldPause()) {
                activeSave = activeSave
                        .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                        .withSession(activeSave.session().withPaused(true).withWarnings(List.of(openAttempt.pauseMessage().getString())));
                client.player.sendMessage(openAttempt.pauseMessage().copy().formatted(Formatting.YELLOW), false);
                saveNow(client);
            }
        }

        if (planner.nextOpenStep(activeSave) == null && activeSave.project().status() != ProjectStatus.COMPLETE) {
            activeSave = activeSave.withProject(activeSave.project().withStatus(ProjectStatus.COMPLETE));
            saveNow(client);
            showCompletionOrder(client);
            clearActiveProject();
            return;
        }

        periodicSave(client);
    }

    public List<Text> hudLines(MinecraftClient client) {
        List<Text> lines = new ArrayList<>();
        if (activeSave == null || !isActiveContext(client)) {
            return lines;
        }

        RouteStep target = planner.nextOpenStep(activeSave);
        int completed = activeSave.bindings().size();
        int total = activeSave.route().size();
        lines.add(Text.literal("MappyWall " + completed + "/" + total).formatted(Formatting.AQUA));

        if (activeSave.project().status() == ProjectStatus.COMPLETE) {
            lines.add(Text.translatable("hud.mappywall.complete").formatted(Formatting.GREEN));
        } else if (activeSave.session().paused()) {
            lines.add(Text.translatable("hud.mappywall.paused").formatted(Formatting.YELLOW));
        } else {
            lines.add(Text.translatable("hud.mappywall.pause_hint").formatted(Formatting.GRAY));
        }

        lines.add(Text.translatable("hud.mappywall.empty_maps").append(": " + emptyMapCount));
        if (target != null) {
            double distance = Math.sqrt(target.targetBlock().distanceSquaredTo(client.player.getX(), client.player.getZ()));
            lines.add(Text.literal("Target " + target.targetBlock().x() + ", " + target.targetBlock().z()
                    + " (" + Math.round(distance) + " blocks)"));
            if (target.region().bounds().contains(client.player.getX(), client.player.getZ())) {
                if (target.region().scale() == 0) {
                    lines.add(Text.translatable("hud.mappywall.inside_target_region").formatted(Formatting.GREEN));
                } else {
                    lines.add(Text.translatable("hud.mappywall.scale_needs_existing_map").formatted(Formatting.YELLOW));
                }
            } else if (target.region().scale() != 0) {
                lines.add(Text.translatable("hud.mappywall.scale_needs_existing_map").formatted(Formatting.YELLOW));
            } else {
                lines.add(Text.translatable("hud.mappywall.open_anywhere_in_region").formatted(Formatting.GRAY));
            }
            lines.add(Text.literal("Wall " + (target.wallPos().column() + 1) + ", " + (target.wallPos().row() + 1)));
        }

        if (activeSave.project().mode().isAutomatic()) {
            lines.add(Text.translatable("message.mappywall.auto_disabled").formatted(Formatting.RED));
        }
        return lines;
    }

    public List<ProjectListItem> listProjects(MinecraftClient client) {
        if (!hasUsableWorld(client)) {
            return List.of();
        }

        WorldContext context = currentContext(client);
        List<ProjectListItem> items = new ArrayList<>();
        for (PersistenceBridge.LoadedProject loaded : persistence.listProjects(context.serverKey(), context.dimension())) {
            MapWallSave save = loaded.save();
            RouteStep target = planner.nextOpenStep(save);
            int completed = save.bindings().size();
            int total = save.route().size();
            boolean active = activeSave != null && activeSave.project().id().equals(save.project().id()) && isActiveContext(client);
            String targetText = target == null
                    ? "-"
                    : (target.wallPos().column() + 1) + "," + (target.wallPos().row() + 1)
                            + " @ " + target.region().centerX() + "," + target.region().centerZ();
            items.add(new ProjectListItem(
                    save.project().id(),
                    save.project().status(),
                    save.project().width(),
                    save.project().height(),
                    save.project().scale(),
                    completed,
                    total,
                    targetText,
                    active
            ));
        }
        return items;
    }

    public int defaultScale() {
        return 0;
    }

    public boolean hasActiveProject() {
        return activeSave != null
                && activeSave.project().status() != ProjectStatus.COMPLETE
                && activeSave.project().status() != ProjectStatus.STOPPED;
    }

    private void repairManualBindings(MinecraftClient client) {
        List<ObservedMap> observedMaps = inventoryScanner.scanFilledMaps(client);
        BindingRepairResult result = mapIndex.repairManualOpenings(activeSave, observedMaps, Instant.now());
        if (!result.bindings().equals(activeSave.bindings())) {
            activeSave = activeSave.withBindings(result.bindings());
            saveNow(client);
        }
        if (result.hasWarnings()) {
            activeSave = activeSave.withProject(activeSave.project().withStatus(ProjectStatus.CONFLICT))
                    .withSession(activeSave.session().withPaused(true).withWarnings(result.warnings()));
            client.player.sendMessage(Text.literal(result.warnings().getFirst()).formatted(Formatting.RED), false);
            saveNow(client);
        }
    }

    private void loadMostRecentProject(MinecraftClient client) {
        WorldContext context = currentContext(client);
        Optional<PersistenceBridge.LoadedProject> loaded = persistence.loadMostRecentActive(context.serverKey(), context.dimension());
        if (loaded.isPresent()) {
            activeSave = loaded.get().save();
            activePath = loaded.get().path();
            activeContext = context;
            mapOpenController.reset();
        }
    }

    private void ensureWorldContext(MinecraftClient client) {
        WorldContext context = currentContext(client);
        if (Objects.equals(activeContext, context)) {
            return;
        }

        saveNow(client);
        activeSave = null;
        activePath = null;
        activeContext = context;
        ticksSinceSave = 0;
        mapOpenController.reset();
    }

    private boolean isActiveContext(MinecraftClient client) {
        return hasUsableWorld(client) && Objects.equals(activeContext, currentContext(client));
    }

    private void periodicSave(MinecraftClient client) {
        ticksSinceSave++;
        if (ticksSinceSave >= SAVE_INTERVAL_TICKS) {
            saveNow(client);
        }
    }

    private void saveNow(MinecraftClient client) {
        if (activeSave == null || activePath == null) {
            return;
        }
        try {
            persistence.save(activePath, activeSave);
            ticksSinceSave = 0;
        } catch (IOException exception) {
            client.player.sendMessage(Text.literal("MappyWall save failed: " + exception.getMessage())
                    .formatted(Formatting.RED), false);
        }
    }

    private void showCompletionOrder(MinecraftClient client) {
        client.player.sendMessage(Text.literal("MappyWall hanging order:"), false);
        for (String line : hangingOrderFormatter.format(activeSave)) {
            client.player.sendMessage(Text.literal(line), false);
        }
    }

    private void clearActiveProject() {
        activeSave = null;
        activePath = null;
        mapOpenController.reset();
    }

    private boolean hasUsableWorld(MinecraftClient client) {
        return client.player != null && client.world != null;
    }

    private String dimensionKey(MinecraftClient client) {
        return client.world.getRegistryKey().getValue().toString();
    }

    private String serverKey(MinecraftClient client) {
        if (client.getCurrentServerEntry() != null) {
            return "server_" + client.getCurrentServerEntry().address;
        }
        if (client.getServer() != null) {
            try {
                return "singleplayer_" + client.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            } catch (RuntimeException exception) {
                return "singleplayer_" + client.getServer().getSaveProperties().getLevelName();
            }
        }
        return "unknown";
    }

    private WorldContext currentContext(MinecraftClient client) {
        return new WorldContext(serverKey(client), dimensionKey(client));
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
            List<LoadedProject> projects = listProjects(serverKey, dimension);
            if (projects.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(projects.getFirst());
        }

        Optional<LoadedProject> loadMostRecentActive(String serverKey, String dimension) {
            return listProjects(serverKey, dimension).stream()
                    .filter(project -> project.save().project().status() != ProjectStatus.COMPLETE)
                    .filter(project -> project.save().project().status() != ProjectStatus.STOPPED)
                    .findFirst();
        }

        Optional<LoadedProject> loadProject(String serverKey, String dimension, String projectId) {
            Path path = projectPath(serverKey, dimension, projectId);
            try {
                return service.load(path).map(save -> new LoadedProject(path, save));
            } catch (IOException exception) {
                return Optional.empty();
            }
        }

        boolean deleteProject(String serverKey, String dimension, String projectId) {
            Path path = projectPath(serverKey, dimension, projectId);
            try {
                return java.nio.file.Files.deleteIfExists(path);
            } catch (IOException exception) {
                return false;
            }
        }

        List<LoadedProject> listProjects(String serverKey, String dimension) {
            Path dimensionDir = configRoot.resolve(sanitize(serverKey)).resolve(sanitize(dimension));
            if (!java.nio.file.Files.isDirectory(dimensionDir)) {
                return List.of();
            }

            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dimensionDir)) {
                return files
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted((left, right) -> {
                            try {
                                return java.nio.file.Files.getLastModifiedTime(right)
                                        .compareTo(java.nio.file.Files.getLastModifiedTime(left));
                            } catch (IOException exception) {
                                return 0;
                            }
                        })
                        .map(path -> {
                            try {
                                return service.load(path).map(save -> new LoadedProject(path, save)).orElse(null);
                            } catch (IOException exception) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing((LoadedProject loaded) -> loaded.save().project().createdAt()).reversed())
                        .toList();
            } catch (IOException exception) {
                return List.of();
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

    private record WorldContext(String serverKey, String dimension) {
    }

    public record ProjectListItem(
            String id,
            ProjectStatus status,
            int width,
            int height,
            int scale,
            int completedSteps,
            int totalSteps,
            String targetText,
            boolean active
    ) {
    }
}

package dev.mappywall.client;

import dev.mappywall.core.BindingRepairResult;
import dev.mappywall.core.BindingVerification;
import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.HangingOrderFormatter;
import dev.mappywall.core.InventoryMapIndex;
import dev.mappywall.core.MapBounds;
import dev.mappywall.core.MapBinding;
import dev.mappywall.core.MapWallPlanner;
import dev.mappywall.core.MapWallProject;
import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.ObservedMap;
import dev.mappywall.core.PlayerBlockPos;
import dev.mappywall.core.PostOpenMode;
import dev.mappywall.core.ProjectStatus;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RunMode;
import dev.mappywall.core.WallAnchorMode;
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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
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
    private List<BlockPos> movementPath = List.of();

    public void openConfigScreen(MinecraftClient client) {
        client.setScreen(new MapWallTasksScreen(this));
    }

    public void openNewProjectScreen(MinecraftClient client) {
        client.setScreen(new MapWallConfigScreen(this));
    }

    public void startManualRun(MinecraftClient client, int scale, int width, int height) {
        startRun(client, scale, width, height, RunMode.MANUAL);
    }

    public void startRun(MinecraftClient client, int scale, int width, int height, RunMode mode) {
        startRun(
                client,
                scale,
                width,
                height,
                mode,
                WallAnchorMode.FIRST_REGION,
                1,
                1,
                PostOpenMode.OPEN_FIRST,
                AutomationStyle.NORMAL
        );
    }

    public void startRun(
            MinecraftClient client,
            int scale,
            int width,
            int height,
            RunMode mode,
            WallAnchorMode anchorMode,
            int columnStepX,
            int rowStepZ
    ) {
        startRun(
                client,
                scale,
                width,
                height,
                mode,
                anchorMode,
                columnStepX,
                rowStepZ,
                PostOpenMode.OPEN_FIRST,
                AutomationStyle.NORMAL
        );
    }

    public void startRun(
            MinecraftClient client,
            int scale,
            int width,
            int height,
            RunMode mode,
            WallAnchorMode anchorMode,
            int columnStepX,
            int rowStepZ,
            PostOpenMode postOpenMode
    ) {
        startRun(client, scale, width, height, mode, anchorMode, columnStepX, rowStepZ, postOpenMode, AutomationStyle.NORMAL);
    }

    public void startRun(
            MinecraftClient client,
            int scale,
            int width,
            int height,
            RunMode mode,
            WallAnchorMode anchorMode,
            int columnStepX,
            int rowStepZ,
            PostOpenMode postOpenMode,
            AutomationStyle automationStyle
    ) {
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
                mode,
                anchorMode,
                columnStepX,
                rowStepZ,
                postOpenMode,
                automationStyle
        );
        activeSave = planner.createSave(project, columnStepX, rowStepZ);
        activePath = persistence.projectPath(context.serverKey(), context.dimension(), id);
        activeContext = context;
        mapOpenController.reset();
        saveNow(client);
        client.player.sendMessage(Text.translatable("message.mappywall.started"), false);
        if (scale != 0) {
            client.player.sendMessage(Text.translatable("message.mappywall.scale_empty_maps_open_as_zero"), false);
        }
        if (mode.isAutomatic()) {
            client.player.sendMessage(Text.translatable(autoMessageKey(mode, automationStyle)).formatted(Formatting.YELLOW), false);
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
        if (paused) {
            releaseMovementIfAutomatic(client);
        }
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

    public void emergencyStop(MinecraftClient client) {
        movementController.release(client);
        if (activeSave == null) {
            return;
        }

        activeSave = activeSave
                .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                .withSession(activeSave.session().withPaused(true).withWarnings(List.of("Emergency stop")));
        saveNow(client);
        if (hasUsableWorld(client)) {
            client.player.sendMessage(Text.translatable("message.mappywall.emergency_stop").formatted(Formatting.RED), false);
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

    public void printHangingOrder(MinecraftClient client, String projectId) {
        if (!hasUsableWorld(client)) {
            return;
        }

        WorldContext context = currentContext(client);
        Optional<PersistenceBridge.LoadedProject> loaded = persistence.loadProject(context.serverKey(), context.dimension(), projectId);
        if (loaded.isEmpty()) {
            client.player.sendMessage(Text.translatable("message.mappywall.project_missing"), false);
            return;
        }

        showCompletionOrder(client, loaded.get().save());
    }

    public void tick(MinecraftClient client) {
        if (!hasUsableWorld(client)) {
            activeSave = null;
            activePath = null;
            activeContext = null;
            movementPath = List.of();
            mapOpenController.reset();
            movementController.release(client);
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
            releaseMovementIfAutomatic(client);
            periodicSave(client);
            return;
        }

        activeSave = activeSave.withSession(activeSave.session().withLastPlayerPos(new PlayerBlockPos(
                client.player.getBlockPos().getX(),
                client.player.getBlockPos().getY(),
                client.player.getBlockPos().getZ()
        )));

        if (activeSave.session().paused()) {
            releaseMovementIfAutomatic(client);
            periodicSave(client);
            return;
        }

        repairManualBindings(client);
        if (activeSave.session().paused()) {
            releaseMovementIfAutomatic(client);
            periodicSave(client);
            return;
        }

        RouteStep fillStep = nextFillStepForRun(activeSave);
        if (fillStep != null) {
            MapWallSave repaired = repairZoomedFillBinding(client, activeSave, fillStep);
            if (repaired != activeSave) {
                activeSave = repaired;
                saveNow(client);
                fillStep = nextFillStepForRun(activeSave);
            }
        }
        RouteStep openTarget = fillStep == null ? planner.nextOpenStep(activeSave) : null;
        RouteStep movementTarget = fillStep == null ? openTarget : planner.fillNavigationStep(activeSave, fillStep);
        if (fillStep != null && !fillMapReadyForTargetScale(client, fillStep)) {
            Text message = activeSave.project().automationStyle() == AutomationStyle.AGGRESSIVE
                    ? aggressiveAutoZoom(client, fillStep)
                    : Text.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
            if (message == null) {
                periodicSave(client);
                return;
            }
            activeSave = activeSave
                    .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                    .withSession(activeSave.session().withPaused(true).withWarnings(List.of(message.getString())));
            movementController.release(client);
            movementPath = List.of();
            client.player.sendMessage(message.copy().formatted(Formatting.YELLOW), false);
            saveNow(client);
            periodicSave(client);
            return;
        }
        if (activeSave.project().mode().isAutomatic()) {
            MovementController.MovementResult movement = movementController.tick(client, activeSave, movementTarget);
            movementPath = movement.path();
            if (movement.shouldPause()) {
                activeSave = activeSave
                        .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                        .withSession(activeSave.session().withPaused(true).withWarnings(List.of(movement.pauseMessage().getString())));
                client.player.sendMessage(movement.pauseMessage().copy().formatted(Formatting.YELLOW), false);
                saveNow(client);
                periodicSave(client);
                return;
            }
        } else {
            movementPath = List.of();
        }

        if (fillStep != null && movementTarget != null && reachedFillTarget(client, movementTarget)) {
            activeSave = planner.advanceFillWaypoint(activeSave);
            saveNow(client);
            periodicSave(client);
            return;
        }

        if (openTarget != null
                && openTarget.region().bounds().contains(client.player.getX(), client.player.getZ())) {
            MapOpenController.MapOpenAttempt openAttempt = mapOpenController.tryOpenMapInRegion(client, openTarget);
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

        if (planner.nextOpenStep(activeSave) == null
                && nextFillStepForRun(activeSave) == null
                && activeSave.project().status() != ProjectStatus.COMPLETE) {
            activeSave = activeSave.withProject(activeSave.project().withStatus(ProjectStatus.COMPLETE));
            saveNow(client);
            showCompletionOrder(client, activeSave);
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

        RouteStep fillStep = nextFillStepForRun(activeSave);
        RouteStep target = fillStep == null ? planner.nextOpenStep(activeSave) : planner.fillNavigationStep(activeSave, fillStep);
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
            if (fillStep != null) {
                lines.add(Text.translatable(
                        "hud.mappywall.fill_waypoint",
                        activeSave.session().fillWaypointIndex() + 1,
                        planner.fillWaypointCount(fillStep.region())
                ).formatted(Formatting.GREEN));
                observedMapForFillStep(client, fillStep)
                        .filter(observed -> observed.exploredFraction() >= 0.0)
                        .ifPresent(observed -> lines.add(Text.translatable(
                                "hud.mappywall.map_explored",
                                Math.round(observed.exploredFraction() * 100.0)
                        ).formatted(Formatting.GRAY)));
            } else if (target.region().bounds().contains(client.player.getX(), client.player.getZ())) {
                lines.add(Text.translatable("hud.mappywall.inside_target_region").formatted(Formatting.GREEN));
            } else {
                lines.add(Text.translatable("hud.mappywall.open_anywhere_in_region").formatted(Formatting.GRAY));
            }
            if (target.region().scale() != 0) {
                lines.add(Text.translatable("hud.mappywall.scale_empty_maps_open_as_zero").formatted(Formatting.YELLOW));
            }
            lines.add(Text.literal("Wall " + (target.wallPos().column() + 1) + ", " + (target.wallPos().row() + 1)));
        }

        if (activeSave.project().mode().isAutomatic()) {
            String key = activeSave.project().mode() == RunMode.AUTO_ELYTRA
                    ? "hud.mappywall.auto_elytra_active"
                    : "hud.mappywall.auto_walk_active";
            lines.add(Text.translatable(key).formatted(Formatting.RED));
            if (activeSave.project().automationStyle() == AutomationStyle.AGGRESSIVE) {
                lines.add(Text.translatable("hud.mappywall.aggressive_active").formatted(Formatting.RED));
            }
            if (movementController.isWaitingForChunk()) {
                lines.add(Text.translatable("hud.mappywall.waiting_for_chunk").formatted(Formatting.YELLOW));
            }
            if (movementController.isPlanningPath()) {
                lines.add(Text.translatable("hud.mappywall.planning_path").formatted(Formatting.YELLOW));
            }
        }
        return lines;
    }

    public Optional<RenderTarget> renderTarget(MinecraftClient client) {
        if (activeSave == null || !isActiveContext(client)) {
            return Optional.empty();
        }
        if (activeSave.project().status() == ProjectStatus.COMPLETE
                || activeSave.project().status() == ProjectStatus.STOPPED) {
            return Optional.empty();
        }

        RouteStep fillStep = nextFillStepForRun(activeSave);
        RouteStep target = fillStep == null ? planner.nextOpenStep(activeSave) : planner.fillNavigationStep(activeSave, fillStep);
        if (target == null) {
            return Optional.empty();
        }

        MapBounds bounds = target.region().bounds();
        boolean showPath = activeSave.project().mode().isAutomatic() && !activeSave.session().paused();
        return Optional.of(new RenderTarget(
                target.targetBlock().x(),
                target.targetBlock().z(),
                bounds.minX(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxZ(),
                target.wallPos().column(),
                target.wallPos().row(),
                showPath,
                showPath ? movementPath : List.of()
        ));
    }

    public List<ProjectListItem> listProjects(MinecraftClient client) {
        if (!hasUsableWorld(client)) {
            return List.of();
        }

        WorldContext context = currentContext(client);
        List<ProjectListItem> items = new ArrayList<>();
        for (PersistenceBridge.LoadedProject loaded : persistence.listProjects(context.serverKey(), context.dimension())) {
            MapWallSave save = loaded.save();
            RouteStep fillStep = nextFillStepForRun(save);
            RouteStep target = fillStep == null ? planner.nextOpenStep(save) : planner.fillNavigationStep(save, fillStep);
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
                    save.project().postOpenMode(),
                    save.project().automationStyle(),
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

    private boolean reachedFillTarget(MinecraftClient client, RouteStep target) {
        return client.player != null
                && target.targetBlock().distanceSquaredTo(client.player.getX(), client.player.getZ()) <= 16.0;
    }

    private RouteStep nextFillStepForRun(MapWallSave save) {
        if (save == null || !save.project().mode().isAutomatic()) {
            return null;
        }
        return planner.nextFillStep(save);
    }

    private boolean fillMapReadyForTargetScale(MinecraftClient client, RouteStep fillStep) {
        int targetScale = fillStep.region().scale();
        if (targetScale == 0) {
            return true;
        }
        return observedMapForFillStep(client, fillStep)
                .filter(observed -> observed.scale() == targetScale)
                .filter(observed -> observed.regionSignature().equals(fillStep.region().signature()))
                .isPresent();
    }

    private MapWallSave repairZoomedFillBinding(MinecraftClient client, MapWallSave save, RouteStep fillStep) {
        if (fillStep.region().scale() == 0) {
            return save;
        }
        Optional<MapBinding> existing = bindingForRegion(save, fillStep.region().signature());
        if (existing.isEmpty()) {
            return save;
        }
        List<ObservedMap> matches = inventoryScanner.scanFilledMaps(client).stream()
                .filter(observed -> observed.scale() == fillStep.region().scale())
                .filter(observed -> observed.regionSignature().equals(fillStep.region().signature()))
                .toList();
        if (matches.size() != 1 || matches.getFirst().mapId() == existing.get().mapId()) {
            return save;
        }

        ObservedMap observed = matches.getFirst();
        List<MapBinding> bindings = new ArrayList<>(save.bindings());
        for (int index = 0; index < bindings.size(); index++) {
            MapBinding binding = bindings.get(index);
            if (binding.regionSignature().equals(fillStep.region().signature())) {
                bindings.set(index, new MapBinding(
                        binding.wallPos(),
                        binding.regionSignature(),
                        observed.mapId(),
                        binding.openedAt(),
                        BindingVerification.MAP_STATE
                ));
                return save.withBindings(bindings);
            }
        }
        return save;
    }

    private Optional<ObservedMap> observedMapForFillStep(MinecraftClient client, RouteStep fillStep) {
        if (activeSave == null) {
            return Optional.empty();
        }
        Optional<MapBinding> binding = bindingForRegion(activeSave, fillStep.region().signature());
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        for (ObservedMap observed : inventoryScanner.scanFilledMaps(client)) {
            if (observed.mapId() == binding.get().mapId()) {
                return Optional.of(observed);
            }
        }
        return Optional.empty();
    }

    private Optional<MapBinding> bindingForRegion(MapWallSave save, String regionSignature) {
        return save.bindings().stream()
                .filter(binding -> binding.regionSignature().equals(regionSignature))
                .findFirst();
    }

    private Text aggressiveAutoZoom(MinecraftClient client, RouteStep fillStep) {
        if (client.player == null) {
            return Text.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
        }
        if (!(client.player.currentScreenHandler instanceof CartographyTableScreenHandler handler)) {
            return Text.translatable("message.mappywall.auto_zoom_open_cartography", fillStep.region().scale());
        }
        if (handler.slots.size() < 3) {
            return Text.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
        }

        Optional<MapBinding> binding = bindingForRegion(activeSave, fillStep.region().signature());
        if (binding.isEmpty()) {
            return Text.translatable("message.mappywall.auto_zoom_no_bound_map");
        }

        Slot resultSlot = handler.slots.get(2);
        if (isFilledMap(resultSlot.getStack())) {
            return quickMoveSlot(client, resultSlot.id) ? null : Text.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
        }

        Slot mapInput = handler.slots.get(0);
        if (!isMapWithId(mapInput.getStack(), binding.get().mapId())) {
            int mapSlot = findMapSlot(handler, binding.get().mapId());
            if (mapSlot < 0) {
                return Text.translatable("message.mappywall.auto_zoom_no_bound_map");
            }
            return quickMoveSlot(client, handler.slots.get(mapSlot).id)
                    ? null
                    : Text.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
        }

        Slot paperInput = handler.slots.get(1);
        if (!paperInput.getStack().isOf(Items.PAPER)) {
            int paperSlot = findPaperSlot(handler);
            if (paperSlot < 0) {
                return Text.translatable("message.mappywall.auto_zoom_no_paper");
            }
            return quickMoveSlot(client, handler.slots.get(paperSlot).id)
                    ? null
                    : Text.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
        }
        return null;
    }

    private int findMapSlot(CartographyTableScreenHandler handler, int mapId) {
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (slot == 2 && handler.slots.size() > 2) {
                continue;
            }
            if (isMapWithId(handler.slots.get(slot).getStack(), mapId)) {
                return slot;
            }
        }
        return -1;
    }

    private int findPaperSlot(CartographyTableScreenHandler handler) {
        for (int slot = 3; slot < handler.slots.size(); slot++) {
            if (handler.slots.get(slot).getStack().isOf(Items.PAPER)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isMapWithId(ItemStack stack, int mapId) {
        Integer stackMapId = InventoryMapIds.readMapId(stack);
        return stackMapId != null && stackMapId == mapId;
    }

    private boolean isFilledMap(ItemStack stack) {
        return stack.isOf(Items.FILLED_MAP) && InventoryMapIds.readMapId(stack) != null;
    }

    private boolean quickMoveSlot(MinecraftClient client, int slotId) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                slotId,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
        return true;
    }

    private String autoMessageKey(RunMode mode, AutomationStyle automationStyle) {
        if (mode == RunMode.AUTO_ELYTRA) {
            return automationStyle == AutomationStyle.AGGRESSIVE
                    ? "message.mappywall.auto_elytra_aggressive_enabled"
                    : "message.mappywall.auto_elytra_enabled";
        }
        return automationStyle == AutomationStyle.AGGRESSIVE
                ? "message.mappywall.auto_walk_aggressive_enabled"
                : "message.mappywall.auto_walk_enabled";
    }

    private void repairManualBindings(MinecraftClient client) {
        List<ObservedMap> observedMaps = inventoryScanner.scanFilledMaps(client);
        BindingRepairResult result = mapIndex.repairManualOpenings(activeSave, observedMaps, Instant.now());
        if (!result.bindings().equals(activeSave.bindings())) {
            activeSave = activeSave.withBindings(result.bindings());
            saveNow(client);
        }
        if (result.hasWarnings()) {
            if (result.warnings().equals(activeSave.session().warnings())) {
                return;
            }
            activeSave = activeSave.withProject(activeSave.project().withStatus(ProjectStatus.CONFLICT))
                    .withSession(activeSave.session().withPaused(true).withWarnings(result.warnings()));
            client.player.sendMessage(Text.literal(result.warnings().getFirst()).formatted(Formatting.RED), false);
            saveNow(client);
        } else if (!activeSave.session().warnings().isEmpty()) {
            activeSave = activeSave.withSession(activeSave.session().withWarnings(List.of()));
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
        movementController.release(client);
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

    private void showCompletionOrder(MinecraftClient client, MapWallSave save) {
        if (save.bindings().isEmpty()) {
            client.player.sendMessage(Text.translatable("message.mappywall.order_empty").formatted(Formatting.YELLOW), false);
            return;
        }

        client.player.sendMessage(Text.translatable("message.mappywall.hanging_order"), false);
        for (String line : hangingOrderFormatter.format(save)) {
            client.player.sendMessage(Text.literal(line), false);
        }
    }

    private void clearActiveProject() {
        releaseMovementIfAutomatic(MinecraftClient.getInstance());
        activeSave = null;
        activePath = null;
        movementPath = List.of();
        mapOpenController.reset();
    }

    private void releaseMovementIfAutomatic(MinecraftClient client) {
        if (activeSave != null && activeSave.project().mode().isAutomatic()) {
            movementController.release(client);
        }
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
            PostOpenMode postOpenMode,
            AutomationStyle automationStyle,
            int completedSteps,
            int totalSteps,
            String targetText,
            boolean active
    ) {
    }

    public record RenderTarget(
            int targetX,
            int targetZ,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int wallColumn,
            int wallRow,
            boolean showPath,
            List<BlockPos> path
    ) {
    }
}

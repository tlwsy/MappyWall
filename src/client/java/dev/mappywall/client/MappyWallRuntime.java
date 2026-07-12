package dev.mappywall.client;

import dev.mappywall.core.BindingRepairResult;
import dev.mappywall.core.BindingVerification;
import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.CrossProjectMapIdIndex;
import dev.mappywall.core.HangingOrderFormatter;
import dev.mappywall.core.InventoryMapIndex;
import dev.mappywall.core.MapBounds;
import dev.mappywall.core.MapBinding;
import dev.mappywall.core.MapRegion;
import dev.mappywall.core.MapRegionMath;
import dev.mappywall.core.MapWallPlanner;
import dev.mappywall.core.MapWallProject;
import dev.mappywall.core.MapWallSave;
import dev.mappywall.core.ObservedMap;
import dev.mappywall.core.PlayerBlockPos;
import dev.mappywall.core.PostOpenMode;
import dev.mappywall.core.ProjectStatus;
import dev.mappywall.core.RouteStep;
import dev.mappywall.core.RouteStepState;
import dev.mappywall.core.RunMode;
import dev.mappywall.core.WallAnchorMode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.MapPostProcessing;
import net.minecraft.world.level.storage.LevelResource;

public final class MappyWallRuntime {
    private static final int SAVE_INTERVAL_TICKS = 100;
    private static final int BINDING_REPAIR_INTERVAL_TICKS = 5;
    private static final int INVENTORY_ACK_TICKS = 8;
    private static final int ZOOM_ACK_TIMEOUT_TICKS = 100;
    private static final int FILL_MIN_DWELL_TICKS = 20;
    private static final int FILL_ALREADY_EXPLORED_DWELL_TICKS = 30;
    private static final int FILL_STABLE_TICKS = 10;
    private static final int FILL_UPDATE_TIMEOUT_TICKS = 180;
    private static final int MAX_FILL_PASSES = 3;
    private static final double COVERAGE_EPSILON = 0.5 / (128.0 * 128.0);
    private static final String CROSS_PROJECT_WARNING_PREFIX = "[cross-project-map-id:";
    private static final String LOCAL_MAP_CONFLICT_PREFIX = "[local-map-id-conflict] ";
    private static final String CORRUPT_DELETE_PREFIX = "\u0000corrupt:";

    private final MapWallPlanner planner = new MapWallPlanner();
    private final PersistenceBridge persistence = new PersistenceBridge();
    private final NavigationConfigStore navigationConfigStore = new NavigationConfigStore();
    private final InventoryMapIndex mapIndex = new InventoryMapIndex();
    private final InventoryMapScanner inventoryScanner = new InventoryMapScanner();
    private final MapOpenController mapOpenController = new MapOpenController(inventoryScanner);
    private final MovementController movementController = new MovementController(navigationConfigStore.aggressiveConfig());
    private final HangingOrderFormatter hangingOrderFormatter = new HangingOrderFormatter();
    private final CrossProjectMapIdIndex crossProjectMapIdIndex = new CrossProjectMapIdIndex();

    private MapWallSave activeSave;
    private Path activePath;
    private WorldContext activeContext;
    private int ticksSinceSave;
    private int bindingRepairCooldown;
    private int emptyMapCount;
    private int inventoryInteractionCooldown;
    private int fillPassCount;
    private String fillPassRegionSignature;
    private List<BlockPos> movementPath = List.of();
    private ZoomAck zoomAck;
    private String zoomTimedOutRegion;
    private FillObservation fillObservation;
    private Component interactionHint;
    private String lastCrossProjectConflictFingerprint;

    public void openConfigScreen(Minecraft client) {
        if (hasUsableWorld(client)) {
            auditCrossProjectMapIds(client, true);
        }
        client.setScreenAndShow(new MapWallTasksScreen(this));
    }

    public void openNewProjectScreen(Minecraft client) {
        client.setScreenAndShow(new MapWallConfigScreen(this));
    }

    public void openNavigationSettingsScreen(Minecraft client, Screen parent) {
        client.setScreenAndShow(new NavigationSettingsScreen(this, parent));
    }

    AutoNavigationConfig aggressiveNavigationConfig() {
        return navigationConfigStore.aggressiveConfig();
    }

    boolean updateAggressiveBreakingConfig(
            boolean enabled,
            AutoNavigationConfig.ListMode listMode,
            Set<String> blockIds
    ) {
        try {
            navigationConfigStore.updateBreaking(enabled, listMode, blockIds);
            movementController.setAggressiveConfig(navigationConfigStore.aggressiveConfig());
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    boolean resetAggressiveBreakingConfig() {
        try {
            navigationConfigStore.resetBreakingDefaults();
            movementController.setAggressiveConfig(navigationConfigStore.aggressiveConfig());
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public void startManualRun(Minecraft client, int scale, int width, int height) {
        startRun(client, scale, width, height, RunMode.MANUAL);
    }

    public void startRun(Minecraft client, int scale, int width, int height, RunMode mode) {
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
            Minecraft client,
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
            Minecraft client,
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
            Minecraft client,
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
        movementController.hardReset(client);

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
        resetTransientAutomationState(false);
        saveNow(client);
        client.player.sendSystemMessage(Component.translatable("message.mappywall.started"));
        if (scale != 0) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.scale_empty_maps_open_as_zero"));
        }
        if (mode.isAutomatic()) {
            client.player.sendSystemMessage(Component.translatable(autoMessageKey(mode, automationStyle)).withStyle(ChatFormatting.YELLOW));
        }
    }

    public void togglePause(Minecraft client) {
        if (activeSave == null) {
            if (hasUsableWorld(client)) {
                client.player.sendSystemMessage(Component.translatable("message.mappywall.no_project"));
            }
            return;
        }

        if (activeSave.project().status() == ProjectStatus.CONFLICT) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.conflict_requires_resolution")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        boolean paused = !activeSave.session().paused();
        ProjectStatus status = paused ? ProjectStatus.PAUSED : ProjectStatus.RUNNING;
        activeSave = activeSave
                .withProject(activeSave.project().withStatus(status))
                .withSession(activeSave.session().withPaused(paused).withWarnings(paused
                        ? activeSave.session().warnings()
                        : List.of()));
        if (paused) {
            releaseMovementIfAutomatic(client);
            fillObservation = null;
        } else {
            bindingRepairCooldown = 0;
            zoomTimedOutRegion = null;
        }
        saveNow(client);

        Component message = Component.translatable(paused ? "message.mappywall.paused" : "message.mappywall.resumed");
        client.player.sendSystemMessage(message);
    }

    public void stopActiveProject(Minecraft client) {
        if (activeSave == null) {
            if (hasUsableWorld(client)) {
                client.player.sendSystemMessage(Component.translatable("message.mappywall.no_project"));
            }
            return;
        }

        activeSave = activeSave
                .withProject(activeSave.project().withStatus(ProjectStatus.STOPPED))
                .withSession(activeSave.session().withPaused(true));
        saveNow(client);
        clearActiveProject();
        if (hasUsableWorld(client)) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.stopped"));
        }
    }

    public void emergencyStop(Minecraft client) {
        movementController.hardReset(client);
        resetTransientAutomationState(false);
        if (activeSave == null) {
            return;
        }

        activeSave = activeSave
                .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                .withSession(activeSave.session().withPaused(true).withWarnings(List.of("Emergency stop")));
        saveNow(client);
        if (hasUsableWorld(client)) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.emergency_stop").withStyle(ChatFormatting.RED));
        }
    }

    public void activateProject(Minecraft client, String projectId) {
        if (!hasUsableWorld(client)) {
            return;
        }

        WorldContext context = currentContext(client);
        auditCrossProjectMapIds(client, true);
        Optional<PersistenceBridge.LoadedProject> loaded = persistence.loadProject(context.serverKey(), context.dimension(), projectId);
        if (loaded.isEmpty()) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.project_missing"));
            return;
        }

        MapWallSave save = normalizeLoadedSave(loaded.get().save());
        if (save.project().status() == ProjectStatus.COMPLETE) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.project_inactive"));
            return;
        }

        if (activeSave != null && !activeSave.project().id().equals(projectId)) {
            activeSave = activeSave
                    .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                    .withSession(activeSave.session().withPaused(true));
            saveNow(client);
        }

        movementController.hardReset(client);
        resetTransientAutomationState(false);

        boolean conflict = save.project().status() == ProjectStatus.CONFLICT;
        activeSave = save.withProject(save.project().withStatus(conflict ? ProjectStatus.CONFLICT : ProjectStatus.RUNNING))
                .withSession(save.session().withPaused(conflict).withWarnings(conflict
                        ? save.session().warnings()
                        : List.of()));
        activePath = loaded.get().path();
        activeContext = context;
        saveNow(client);
        client.player.sendSystemMessage(Component.translatable("message.mappywall.project_activated"));
    }

    public void deleteProject(Minecraft client, String projectId) {
        if (!hasUsableWorld(client)) {
            return;
        }

        WorldContext context = currentContext(client);
        if (activeSave != null && activeSave.project().id().equals(projectId)) {
            clearActiveProject();
        }

        boolean deleted = projectId.startsWith(CORRUPT_DELETE_PREFIX)
                ? persistence.deleteCorruptProject(context.serverKey(), context.dimension(), projectId)
                : persistence.deleteProject(context.serverKey(), context.dimension(), projectId);
        if (deleted) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.project_deleted"));
            auditCrossProjectMapIds(client, true);
        } else {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.project_missing"));
        }
    }

    public void printHangingOrder(Minecraft client, String projectId) {
        if (!hasUsableWorld(client)) {
            return;
        }

        WorldContext context = currentContext(client);
        Optional<PersistenceBridge.LoadedProject> loaded = persistence.loadProject(context.serverKey(), context.dimension(), projectId);
        if (loaded.isEmpty()) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.project_missing"));
            return;
        }

        MapWallSave save = normalizeLoadedSave(loaded.get().save());
        if (save.project().status() != ProjectStatus.COMPLETE) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.order_not_ready"));
            return;
        }
        showCompletionOrder(client, save);
    }

    public void tick(Minecraft client) {
        interactionHint = null;
        tickInteractionCooldowns();
        if (!hasUsableWorld(client)) {
            if (activeSave != null && activePath != null) {
                try {
                    persistence.save(activePath, activeSave);
                } catch (IOException ignored) {
                    // There is no player/HUD available during disconnect to report this.
                }
            }
            activeSave = null;
            activePath = null;
            activeContext = null;
            movementPath = List.of();
            resetTransientAutomationState(true);
            movementController.hardReset(client);
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
            movementController.release(client);
            movementPath = List.of();
            ticksSinceSave++;
            if (ticksSinceSave >= SAVE_INTERVAL_TICKS && saveNow(client)) {
                showCompletionSummary(client, activeSave);
                clearActiveProject();
            }
            return;
        }

        if (client.isPaused()) {
            releaseMovementIfAutomatic(client);
            periodicSave(client);
            return;
        }

        if (!client.isWindowActive()) {
            releaseMovementIfAutomatic(client);
            periodicSave(client);
            return;
        }

        if (!client.player.isAlive() || client.player.isSpectator()) {
            Component message = Component.translatable("message.mappywall.player_state_paused");
            pauseActiveProject(client, message, ChatFormatting.YELLOW);
            return;
        }

        activeSave = activeSave.withSession(activeSave.session().withLastPlayerPos(new PlayerBlockPos(
                client.player.blockPosition().getX(),
                client.player.blockPosition().getY(),
                client.player.blockPosition().getZ()
        )));

        if (!activeSave.session().paused() || activeSave.project().status() == ProjectStatus.CONFLICT) {
            if (bindingRepairCooldown <= 0) {
                repairManualBindings(client);
                bindingRepairCooldown = BINDING_REPAIR_INTERVAL_TICKS - 1;
            } else {
                bindingRepairCooldown--;
            }
        }
        if (activeSave.session().paused()) {
            releaseMovementIfAutomatic(client);
            periodicSave(client);
            return;
        }

        boolean automatic = activeSave.project().mode().isAutomatic();
        boolean aggressive = automatic
                && activeSave.project().automationStyle() == AutomationStyle.AGGRESSIVE;
        if (automatic && !aggressive && client.gui.screen() != null) {
            releaseMovementIfAutomatic(client);
            movementPath = List.of();
            periodicSave(client);
            return;
        }

        RouteStep fillStep = nextFillStepForRun(activeSave);
        RouteStep openTarget = fillStep == null ? planner.nextOpenStep(activeSave) : null;
        RouteStep zoomStep = fillStep == null && openTarget == null ? nextZoomStepForRun(activeSave) : null;
        RouteStep scaleStep = fillStep != null ? fillStep : zoomStep;
        if (scaleStep != null) {
            MapWallSave repaired = repairZoomedFillBinding(client, activeSave, scaleStep);
            if (!repaired.equals(activeSave)) {
                activeSave = repaired;
                saveNow(client);
                fillStep = nextFillStepForRun(activeSave);
                openTarget = fillStep == null ? planner.nextOpenStep(activeSave) : null;
                zoomStep = fillStep == null && openTarget == null ? nextZoomStepForRun(activeSave) : null;
            }
        }

        if (zoomStep != null && !fillMapReadyForTargetScale(client, zoomStep)) {
            if (aggressive) {
                interactionHint = aggressiveAutoZoom(client, zoomStep);
            } else if (automatic) {
                pauseActiveProject(
                        client,
                        Component.translatable("message.mappywall.fill_requires_zoomed_map", zoomStep.region().scale()),
                        ChatFormatting.YELLOW
                );
                return;
            } else {
                interactionHint = Component.translatable(
                        "message.mappywall.fill_requires_zoomed_map",
                        zoomStep.region().scale()
                );
            }
            movementController.release(client);
            movementPath = List.of();
            periodicSave(client);
            return;
        }

        RouteStep movementTarget = fillStep == null ? openTarget : planner.fillNavigationStep(activeSave, fillStep);
        if (movementTarget == null && completedStepCount(activeSave) < activeSave.route().size()) {
            interactionHint = Component.translatable("message.mappywall.waiting_map_state");
            movementController.release(client);
            movementPath = List.of();
            periodicSave(client);
            return;
        }
        boolean fillMapReady = fillStep == null || fillMapReadyForTargetScale(client, fillStep);
        if (fillStep != null && !fillMapReady) {
            if (aggressive) {
                interactionHint = aggressiveAutoZoom(client, fillStep);
                movementController.release(client);
                movementPath = List.of();
                periodicSave(client);
                return;
            } else {
                Component message = fillReadinessMessage(client, fillStep);
                pauseActiveProject(client, message, ChatFormatting.YELLOW);
                return;
            }
        } else if (fillStep != null && !ensureFillMapHeld(client, fillStep)) {
            interactionHint = client.player != null
                            && !client.player.getOffhandItem().isEmpty()
                            && !client.player.getOffhandItem().is(Items.FILLED_MAP)
                    ? Component.translatable("message.mappywall.fill_offhand_required")
                    : Component.translatable("message.mappywall.fill_map_not_held");
            movementController.release(client);
            movementPath = List.of();
            periodicSave(client);
            return;
        }

        boolean reachedFillTarget = fillStep != null
                && movementTarget != null
                && reachedFillTarget(client, movementTarget);
        boolean stagingMapOpen = automatic
                && openTarget != null
                && mapOpenController.canOpenAtCurrentPosition(client, openTarget);
        if (automatic && (reachedFillTarget || stagingMapOpen)) {
            movementController.release(client);
            movementPath = List.of();
        } else if (automatic) {
            MovementController.MovementResult movement = movementController.tick(client, activeSave, movementTarget);
            movementPath = movement.path();
            if (movement.shouldPause()) {
                pauseActiveProject(client, movement.pauseMessage(), ChatFormatting.YELLOW);
                return;
            }
        } else {
            movementPath = List.of();
        }

        if (reachedFillTarget && handleReachedFillTarget(client, fillStep)) {
            if (finishProjectIfDone(client)) {
                return;
            }
            periodicSave(client);
            return;
        }

        if (automatic && openTarget != null) {
            MapOpenController.MapOpenAttempt openAttempt = mapOpenController.tryOpenMapInRegion(client, openTarget);
            if (openAttempt.openedMapIdOptional().isPresent()) {
                activeSave = planner.bindCurrentStep(
                        activeSave,
                        openAttempt.openedMapIdOptional().get(),
                        Instant.now(),
                        BindingVerification.MAP_STATE
                );
                saveNow(client);
            } else if (openAttempt.shouldPause()) {
                pauseActiveProject(client, openAttempt.pauseMessage(), ChatFormatting.YELLOW);
                return;
            }
        }

        if (finishProjectIfDone(client)) {
            return;
        }

        periodicSave(client);
    }

    public List<Component> hudLines(Minecraft client) {
        List<Component> lines = new ArrayList<>();
        if (activeSave == null || !isActiveContext(client)) {
            return lines;
        }

        RouteStep fillStep = nextFillStepForRun(activeSave);
        RouteStep target = fillStep == null ? planner.nextOpenStep(activeSave) : planner.fillNavigationStep(activeSave, fillStep);
        int completed = completedStepCount(activeSave);
        int total = activeSave.route().size();
        lines.add(Component.literal("MappyWall " + completed + "/" + total).withStyle(ChatFormatting.AQUA));

        if (activeSave.project().status() == ProjectStatus.COMPLETE) {
            lines.add(Component.translatable("hud.mappywall.complete").withStyle(ChatFormatting.GREEN));
        } else if (activeSave.session().paused()) {
            lines.add(Component.translatable("hud.mappywall.paused").withStyle(ChatFormatting.YELLOW));
        } else {
            lines.add(Component.translatable("hud.mappywall.pause_hint").withStyle(ChatFormatting.GRAY));
        }

        lines.add(Component.translatable("hud.mappywall.empty_maps").append(": " + emptyMapCount));
        if (interactionHint != null) {
            lines.add(interactionHint.copy().withStyle(ChatFormatting.YELLOW));
        }
        if (target != null) {
            double distance = Math.sqrt(target.targetBlock().distanceSquaredTo(client.player.getX(), client.player.getZ()));
            lines.add(Component.literal("Target " + target.targetBlock().x() + ", " + target.targetBlock().z()
                    + " (" + Math.round(distance) + " blocks)"));
            if (fillStep != null) {
                lines.add(Component.translatable(
                        "hud.mappywall.fill_waypoint",
                        activeSave.session().fillWaypointIndex() + 1,
                        planner.fillWaypointCount(fillStep.region())
                ).withStyle(ChatFormatting.GREEN));
                observedMapForFillStep(client, fillStep)
                        .filter(observed -> observed.exploredFraction() >= 0.0)
                        .ifPresent(observed -> lines.add(Component.translatable(
                                "hud.mappywall.map_explored",
                                Math.round(observed.exploredFraction() * 100.0)
                        ).withStyle(ChatFormatting.GRAY)));
            } else if (mapOpenController.canOpenAtCurrentPosition(client, target)) {
                lines.add(Component.translatable("hud.mappywall.inside_target_region").withStyle(ChatFormatting.GREEN));
            } else {
                lines.add(Component.translatable("hud.mappywall.open_anywhere_in_region").withStyle(ChatFormatting.GRAY));
            }
            if (target.region().scale() != 0) {
                lines.add(Component.translatable("hud.mappywall.scale_empty_maps_open_as_zero").withStyle(ChatFormatting.YELLOW));
            }
            lines.add(Component.literal("Wall " + (target.wallPos().column() + 1) + ", " + (target.wallPos().row() + 1)));
        }

        if (activeSave.project().mode().isAutomatic()) {
            String key = activeSave.project().mode() == RunMode.AUTO_ELYTRA
                    ? "hud.mappywall.auto_elytra_active"
                    : "hud.mappywall.auto_walk_active";
            lines.add(Component.translatable(key).withStyle(ChatFormatting.RED));
            String styleKey = activeSave.project().automationStyle() == AutomationStyle.AGGRESSIVE
                    ? "hud.mappywall.automation_style_aggressive"
                    : "hud.mappywall.automation_style_normal";
            lines.add(Component.translatable(styleKey).withStyle(ChatFormatting.RED));
            if (movementController.isWaitingForChunk()) {
                lines.add(Component.translatable("hud.mappywall.waiting_for_chunk").withStyle(ChatFormatting.YELLOW));
            }
            if (movementController.isPlanningPath()) {
                lines.add(Component.translatable("hud.mappywall.planning_path").withStyle(ChatFormatting.YELLOW));
            }
        }
        return lines;
    }

    public Optional<RenderTarget> renderTarget(Minecraft client) {
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

    public List<ProjectListItem> listProjects(Minecraft client) {
        if (!hasUsableWorld(client)) {
            return List.of();
        }

        WorldContext context = currentContext(client);
        List<ProjectListItem> items = new ArrayList<>();
        for (PersistenceBridge.LoadedProject loaded : persistence.listProjects(context.serverKey(), context.dimension())) {
            MapWallSave save = normalizeLoadedSave(loaded.save());
            RouteStep fillStep = nextFillStepForRun(save);
            RouteStep target = fillStep == null ? planner.nextOpenStep(save) : planner.fillNavigationStep(save, fillStep);
            int completed = completedStepCount(save);
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
                    active,
                    false,
                    save.project().id()
            ));
        }
        for (PersistenceBridge.CorruptProject corrupt : persistence.listCorruptProjects(
                context.serverKey(),
                context.dimension()
        )) {
            items.add(new ProjectListItem(
                    corrupt.projectId(),
                    ProjectStatus.CONFLICT,
                    0,
                    0,
                    0,
                    PostOpenMode.OPEN_FIRST,
                    AutomationStyle.NORMAL,
                    0,
                    0,
                    Component.translatable("screen.mappywall.tasks.corrupt").getString(),
                    false,
                    true,
                    CORRUPT_DELETE_PREFIX + corrupt.path().getFileName()
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

    private boolean reachedFillTarget(Minecraft client, RouteStep target) {
        return client.player != null
                && target.targetBlock().distanceSquaredTo(client.player.getX(), client.player.getZ()) <= 16.0;
    }

    private RouteStep nextFillStepForRun(MapWallSave save) {
        if (save == null || !save.project().mode().isAutomatic()) {
            return null;
        }
        return planner.nextFillStep(save);
    }

    private RouteStep nextZoomStepForRun(MapWallSave save) {
        if (save == null
                || save.project().scale() == 0
                || save.project().postOpenMode() != PostOpenMode.OPEN_FIRST) {
            return null;
        }
        for (RouteStep step : save.route()) {
            if (step.state() == RouteStepState.OPENED
                    && bindingForRegion(save, step.region().signature()).isPresent()) {
                return step;
            }
        }
        return null;
    }

    private int completedStepCount(MapWallSave save) {
        return (int) save.route().stream()
                .filter(step -> step.state() == RouteStepState.BOUND)
                .count();
    }

    private boolean fillMapReadyForTargetScale(Minecraft client, RouteStep fillStep) {
        return observedMapForFillStep(client, fillStep)
                .filter(observed -> observed.scale() == fillStep.region().scale())
                .filter(observed -> observed.regionSignature().equals(fillStep.region().signature()))
                .isPresent();
    }

    private MapWallSave repairZoomedFillBinding(Minecraft client, MapWallSave save, RouteStep fillStep) {
        Optional<MapBinding> existing = bindingForRegion(save, fillStep.region().signature());
        if (existing.isEmpty()) {
            return save;
        }

        List<ObservedMap> observedMaps = inventoryScanner.scanFilledMaps(client);
        if (zoomAck != null && zoomAck.regionSignature().equals(fillStep.region().signature())) {
            List<ObservedMap> acknowledged = observedMaps.stream()
                    .filter(observed -> !zoomAck.knownMapIds().contains(observed.mapId()))
                    .filter(observed -> observed.scale() == zoomAck.expectedScale())
                    .filter(observed -> mapsToTargetRegion(observed, fillStep))
                    .toList();
            if (acknowledged.size() == 1) {
                int sourceMapId = zoomAck.sourceMapId();
                zoomAck = null;
                inventoryInteractionCooldown = INVENTORY_ACK_TICKS;
                return updateRegionBinding(save, fillStep, acknowledged.getFirst(), sourceMapId);
            }
            return save;
        }

        Optional<ObservedMap> existingObserved = observedMapAnywhere(client, existing.get().mapId())
                .filter(observed -> mapsToTargetRegion(observed, fillStep));
        if (existingObserved
                .filter(observed -> observed.scale() == fillStep.region().scale())
                .filter(observed -> observed.regionSignature().equals(fillStep.region().signature()))
                .isPresent()
                && existing.get().verifiedBy() != BindingVerification.TARGET_SCALE) {
            return updateRegionBinding(save, fillStep, existingObserved.get());
        }

        if (fillStep.region().scale() == 0) {
            return save;
        }

        int existingScale = existingObserved
                .map(ObservedMap::scale)
                .orElse(-1);
        List<ObservedMap> candidates = observedMaps.stream()
                .filter(observed -> mapsToTargetRegion(observed, fillStep))
                .toList();
        int highestScale = candidates.stream().mapToInt(ObservedMap::scale).max().orElse(-1);
        if (highestScale <= existingScale) {
            return save;
        }
        List<ObservedMap> highest = candidates.stream()
                .filter(observed -> observed.scale() == highestScale)
                .toList();
        if (highest.size() != 1) {
            return save;
        }
        return updateRegionBinding(save, fillStep, highest.getFirst());
    }

    private MapWallSave updateRegionBinding(MapWallSave save, RouteStep fillStep, ObservedMap observed) {
        return updateRegionBinding(save, fillStep, observed, null);
    }

    private MapWallSave updateRegionBinding(
            MapWallSave save,
            RouteStep fillStep,
            ObservedMap observed,
            Integer consumedSourceMapId
    ) {
        List<MapBinding> bindings = new ArrayList<>(save.bindings());
        if (consumedSourceMapId != null && consumedSourceMapId != observed.mapId()) {
            bindings.removeIf(binding -> binding.mapId() == consumedSourceMapId
                    && binding.regionSignature().equals(fillStep.region().signature()));
        }
        for (int index = 0; index < bindings.size(); index++) {
            MapBinding binding = bindings.get(index);
            if (binding.mapId() == observed.mapId()) {
                if (!binding.regionSignature().equals(fillStep.region().signature())) {
                    return save;
                }
                bindings.set(index, new MapBinding(
                        binding.wallPos(),
                        binding.regionSignature(),
                        observed.mapId(),
                        binding.openedAt(),
                        observed.scale() == fillStep.region().scale()
                                        && observed.regionSignature().equals(fillStep.region().signature())
                                ? BindingVerification.TARGET_SCALE
                                : BindingVerification.MAP_STATE
                ));
                return planner.reconcileBindings(save, bindings);
            }
        }
        bindings.add(new MapBinding(
                fillStep.wallPos(),
                fillStep.region().signature(),
                observed.mapId(),
                Instant.now(),
                observed.scale() == fillStep.region().scale()
                                && observed.regionSignature().equals(fillStep.region().signature())
                        ? BindingVerification.TARGET_SCALE
                        : BindingVerification.MAP_STATE
        ));
        return planner.reconcileBindings(save, bindings);
    }

    private boolean mapsToTargetRegion(ObservedMap observed, RouteStep fillStep) {
        if (!observed.dimension().equals(fillStep.region().dimension())
                || observed.scale() > fillStep.region().scale()) {
            return false;
        }
        MapRegion projected = MapRegionMath.regionForBlock(
                observed.dimension(),
                fillStep.region().scale(),
                observed.centerX(),
                observed.centerZ()
        );
        return projected.signature().equals(fillStep.region().signature());
    }

    private Optional<ObservedMap> observedMapForFillStep(Minecraft client, RouteStep fillStep) {
        if (activeSave == null) {
            return Optional.empty();
        }
        Set<Integer> mapIds = activeSave.bindingsForRegion(fillStep.region().signature()).stream()
                .map(MapBinding::mapId)
                .collect(java.util.stream.Collectors.toSet());
        if (mapIds.isEmpty()) {
            return Optional.empty();
        }
        Integer offhandMapId = client.player == null
                ? null
                : InventoryMapIds.readMapId(client.player.getOffhandItem());
        return inventoryScanner.scanFilledMaps(client).stream()
                .filter(observed -> mapIds.contains(observed.mapId()))
                .filter(observed -> mapsToTargetRegion(observed, fillStep))
                .sorted(java.util.Comparator
                        .comparingInt(ObservedMap::scale).reversed()
                        .thenComparing(observed -> !Objects.equals(observed.mapId(), offhandMapId))
                        .thenComparingInt(ObservedMap::mapId))
                .findFirst();
    }

    private Optional<ObservedMap> observedMapForRegionAnywhere(Minecraft client, RouteStep step) {
        if (activeSave == null) {
            return Optional.empty();
        }
        Set<Integer> mapIds = activeSave.bindingsForRegion(step.region().signature()).stream()
                .map(MapBinding::mapId)
                .collect(java.util.stream.Collectors.toSet());
        if (mapIds.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, ObservedMap> observedById = new HashMap<>();
        for (ObservedMap observed : inventoryScanner.scanFilledMaps(client)) {
            if (mapIds.contains(observed.mapId()) && mapsToTargetRegion(observed, step)) {
                observedById.putIfAbsent(observed.mapId(), observed);
            }
        }
        if (client.player != null && client.player.containerMenu instanceof CartographyTableMenu handler) {
            for (int slotIndex = 0; slotIndex < Math.min(3, handler.slots.size()); slotIndex++) {
                inventoryScanner.observeFilledMap(client, handler.slots.get(slotIndex).getItem())
                        .filter(observed -> mapIds.contains(observed.mapId()))
                        .filter(observed -> mapsToTargetRegion(observed, step))
                        .ifPresent(observed -> observedById.putIfAbsent(observed.mapId(), observed));
            }
        }
        Integer offhandMapId = client.player == null
                ? null
                : InventoryMapIds.readMapId(client.player.getOffhandItem());
        Integer tableInputMapId = client.player != null
                        && client.player.containerMenu instanceof CartographyTableMenu handler
                        && !handler.slots.isEmpty()
                ? InventoryMapIds.readMapId(handler.slots.getFirst().getItem())
                : null;
        return observedById.values().stream()
                .sorted(java.util.Comparator
                        .comparing((ObservedMap observed) -> !Objects.equals(observed.mapId(), tableInputMapId))
                        .thenComparing(java.util.Comparator.comparingInt(ObservedMap::scale).reversed())
                        .thenComparing(observed -> !Objects.equals(observed.mapId(), offhandMapId))
                        .thenComparingInt(ObservedMap::mapId))
                .findFirst();
    }

    private Optional<ObservedMap> observedMapAnywhere(Minecraft client, int mapId) {
        for (ObservedMap observed : inventoryScanner.scanFilledMaps(client)) {
            if (observed.mapId() == mapId) {
                return Optional.of(observed);
            }
        }
        if (client.player != null && client.player.containerMenu instanceof CartographyTableMenu handler) {
            for (int slotIndex = 0; slotIndex < Math.min(3, handler.slots.size()); slotIndex++) {
                ItemStack stack = handler.slots.get(slotIndex).getItem();
                if (isMapWithId(stack, mapId)) {
                    Optional<ObservedMap> observed = inventoryScanner.observeFilledMap(client, stack);
                    if (observed.isPresent()) {
                        return observed;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<MapBinding> bindingForRegion(MapWallSave save, String regionSignature) {
        return save.preferredBindingForRegion(regionSignature);
    }

    private Component aggressiveAutoZoom(Minecraft client, RouteStep fillStep) {
        if (client.player == null) {
            return Component.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
        }
        if (zoomTimedOutRegion != null
                && !zoomTimedOutRegion.equals(fillStep.region().signature())) {
            zoomTimedOutRegion = null;
        }
        if (fillStep.region().signature().equals(zoomTimedOutRegion)) {
            return Component.translatable("message.mappywall.auto_zoom_ack_timeout");
        }
        if (zoomAck != null) {
            return Component.translatable("message.mappywall.auto_zoom_waiting_ack");
        }
        Optional<ObservedMap> currentMap = observedMapForRegionAnywhere(client, fillStep);
        if (currentMap.isEmpty()) {
            return Component.translatable("message.mappywall.auto_zoom_no_bound_map");
        }
        Optional<MapBinding> binding = activeSave.bindingForMapId(currentMap.get().mapId());
        if (binding.isEmpty()) {
            return Component.translatable("message.mappywall.auto_zoom_no_bound_map");
        }
        if (currentMap.get().scale() >= fillStep.region().scale()) {
            return Component.translatable("message.mappywall.auto_zoom_return_map");
        }
        if (!(client.player.containerMenu instanceof CartographyTableMenu handler)
                || client.gui.screen() == null) {
            return Component.translatable("message.mappywall.auto_zoom_open_cartography", fillStep.region().scale());
        }
        if (handler.slots.size() < 3) {
            return Component.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
        }
        if (inventoryInteractionCooldown > 0) {
            return Component.translatable("message.mappywall.auto_zoom_waiting_ack");
        }

        Slot mapInput = handler.slots.get(0);
        if (mapInput.getItem().isEmpty()) {
            int mapSlot = findMapSlot(handler, binding.get().mapId());
            if (mapSlot < 0 && InventoryMapIds.isFilledMapWithId(
                    client.player.getOffhandItem(), binding.get().mapId())) {
                int emptySlot = findEmptyPlayerSlot(handler, client.player);
                if (emptySlot < 0) {
                    return Component.translatable("message.mappywall.auto_zoom_need_space");
                }
                swapOffhandIntoSlot(client, handler, emptySlot);
                return Component.translatable("message.mappywall.auto_zoom_waiting_ack");
            }
            if (mapSlot < 0) {
                return Component.translatable("message.mappywall.auto_zoom_no_bound_map");
            }
            quickMoveSlot(client, handler, mapSlot);
            return Component.translatable("message.mappywall.auto_zoom_waiting_ack");
        }
        if (!isMapWithId(mapInput.getItem(), binding.get().mapId())) {
            return Component.translatable("message.mappywall.auto_zoom_table_busy");
        }

        Slot paperInput = handler.slots.get(1);
        if (paperInput.getItem().isEmpty()) {
            int paperSlot = findPaperSlot(handler);
            if (paperSlot < 0) {
                return Component.translatable("message.mappywall.auto_zoom_no_paper");
            }
            quickMoveSlot(client, handler, paperSlot);
            return Component.translatable("message.mappywall.auto_zoom_waiting_ack");
        }
        if (!paperInput.getItem().is(Items.PAPER)) {
            return Component.translatable("message.mappywall.auto_zoom_table_busy");
        }

        ItemStack result = handler.slots.get(2).getItem();
        if (result.isEmpty()) {
            return Component.translatable("message.mappywall.auto_zoom_waiting_result");
        }
        if (!isMapWithId(result, binding.get().mapId())
                || result.get(DataComponents.MAP_POST_PROCESSING) != MapPostProcessing.SCALE) {
            return Component.translatable("message.mappywall.auto_zoom_table_busy");
        }

        Set<Integer> knownMapIds = inventoryScanner.scanFilledMapIds(client.player);
        if (quickMoveSlot(client, handler, 2)) {
            zoomAck = new ZoomAck(
                    fillStep.region().signature(),
                    currentMap.get().mapId(),
                    currentMap.get().scale() + 1,
                    knownMapIds,
                    ZOOM_ACK_TIMEOUT_TICKS
            );
        }
        return Component.translatable("message.mappywall.auto_zoom_waiting_ack");
    }

    private int findMapSlot(CartographyTableMenu handler, int mapId) {
        for (int slot = 3; slot < handler.slots.size(); slot++) {
            if (isMapWithId(handler.slots.get(slot).getItem(), mapId)) {
                return slot;
            }
        }
        return -1;
    }

    private int findPaperSlot(CartographyTableMenu handler) {
        for (int slot = 3; slot < handler.slots.size(); slot++) {
            if (handler.slots.get(slot).getItem().is(Items.PAPER)) {
                return slot;
            }
        }
        return -1;
    }

    private int findEmptyPlayerSlot(
            CartographyTableMenu handler,
            net.minecraft.client.player.LocalPlayer player
    ) {
        for (int slot = 3; slot < handler.slots.size(); slot++) {
            Slot candidate = handler.slots.get(slot);
            if (candidate.container == player.getInventory() && candidate.getItem().isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private boolean swapOffhandIntoSlot(Minecraft client, CartographyTableMenu handler, int slotId) {
        if (client.player == null
                || client.gameMode == null
                || client.gui.screen() == null
                || client.player.containerMenu != handler
                || inventoryInteractionCooldown > 0) {
            return false;
        }
        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                Inventory.SLOT_OFFHAND,
                ContainerInput.SWAP,
                client.player
        );
        inventoryScanner.invalidateInventorySnapshot();
        inventoryInteractionCooldown = INVENTORY_ACK_TICKS;
        return true;
    }

    private boolean isMapWithId(ItemStack stack, int mapId) {
        Integer stackMapId = InventoryMapIds.readMapId(stack);
        return stackMapId != null && stackMapId == mapId;
    }

    private boolean quickMoveSlot(Minecraft client, CartographyTableMenu handler, int slotId) {
        if (client.player == null
                || client.gameMode == null
                || client.gui.screen() == null
                || client.player.containerMenu != handler
                || inventoryInteractionCooldown > 0
                || slotId < 0
                || slotId >= handler.slots.size()) {
            return false;
        }
        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );
        inventoryScanner.invalidateInventorySnapshot();
        inventoryInteractionCooldown = INVENTORY_ACK_TICKS;
        return true;
    }

    private Component fillReadinessMessage(Minecraft client, RouteStep fillStep) {
        if (bindingForRegion(activeSave, fillStep.region().signature()).isEmpty()
                || observedMapForFillStep(client, fillStep).isEmpty()) {
            return Component.translatable("message.mappywall.fill_map_missing");
        }
        return Component.translatable("message.mappywall.fill_requires_zoomed_map", fillStep.region().scale());
    }

    private boolean ensureFillMapHeld(Minecraft client, RouteStep fillStep) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        Optional<ObservedMap> observed = observedMapForFillStep(client, fillStep)
                .filter(map -> map.scale() == fillStep.region().scale())
                .filter(map -> map.regionSignature().equals(fillStep.region().signature()));
        if (observed.isEmpty()) {
            return false;
        }
        int mapId = observed.get().mapId();
        if (isFillMapLeased(client.player, mapId)) {
            return true;
        }
        if (client.gui.screen() != null
                || client.player.containerMenu != client.player.inventoryMenu
                || inventoryInteractionCooldown > 0) {
            return false;
        }

        int inventorySlot = inventoryScanner.findInventoryFilledMap(client.player, mapId);
        if (inventorySlot < 0) {
            return false;
        }
        ItemStack offhand = client.player.getOffhandItem();
        if (!offhand.isEmpty() && !offhand.is(Items.FILLED_MAP)) {
            return false;
        }
        int menuSlot = findPlayerInventoryMenuSlot(client.player, inventorySlot);
        if (menuSlot >= 0) {
            client.gameMode.handleContainerInput(
                    client.player.inventoryMenu.containerId,
                    menuSlot,
                    Inventory.SLOT_OFFHAND,
                    ContainerInput.SWAP,
                    client.player
            );
            inventoryScanner.invalidateInventorySnapshot();
            inventoryInteractionCooldown = INVENTORY_ACK_TICKS;
        }
        return false;
    }

    private int findPlayerInventoryMenuSlot(net.minecraft.client.player.LocalPlayer player, int inventorySlot) {
        for (int menuSlot = 0; menuSlot < player.inventoryMenu.slots.size(); menuSlot++) {
            Slot slot = player.inventoryMenu.slots.get(menuSlot);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                return menuSlot;
            }
        }
        return -1;
    }

    private boolean isFillMapLeased(net.minecraft.client.player.LocalPlayer player, int mapId) {
        return InventoryMapIds.isFilledMapWithId(player.getOffhandItem(), mapId);
    }

    private boolean handleReachedFillTarget(Minecraft client, RouteStep fillStep) {
        Optional<ObservedMap> observedOptional = observedMapForFillStep(client, fillStep)
                .filter(observed -> observed.scale() == fillStep.region().scale())
                .filter(observed -> observed.regionSignature().equals(fillStep.region().signature()));
        Optional<MapBinding> binding = observedOptional.flatMap(observed -> activeSave.bindingForMapId(observed.mapId()));
        if (observedOptional.isEmpty()
                || binding.isEmpty()
                || !isFillMapLeased(client.player, binding.get().mapId())) {
            fillObservation = null;
            return false;
        }

        ObservedMap observed = observedOptional.get();
        int waypointIndex = activeSave.session().fillWaypointIndex();
        if (fillObservation == null
                || !fillObservation.matches(fillStep.region().signature(), observed.mapId(), waypointIndex)) {
            fillObservation = new FillObservation(
                    fillStep.region().signature(),
                    observed.mapId(),
                    waypointIndex,
                    observed.exploredFraction()
            );
        } else {
            fillObservation.observe(observed.exploredFraction());
        }

        if (fillObservation.dwellTicks() >= FILL_UPDATE_TIMEOUT_TICKS
                && !fillObservation.sawGrowth()
                && fillObservation.baselineCoverage() <= COVERAGE_EPSILON) {
            pauseActiveProject(
                    client,
                    Component.translatable("message.mappywall.fill_map_not_updating"),
                    ChatFormatting.YELLOW
            );
            return true;
        }

        boolean settled = fillObservation.dwellTicks() >= FILL_MIN_DWELL_TICKS
                && fillObservation.stableTicks() >= FILL_STABLE_TICKS;
        boolean confirmed = fillObservation.sawGrowth()
                || (fillObservation.baselineCoverage() > COVERAGE_EPSILON
                        && fillObservation.dwellTicks() >= FILL_ALREADY_EXPLORED_DWELL_TICKS);
        if (!settled || !confirmed) {
            return false;
        }

        int waypointCount = planner.fillWaypointCount(fillStep.region());
        boolean finalWaypoint = waypointIndex + 1 >= waypointCount;
        if (finalWaypoint && observed.exploredFraction() < minimumFinalCoverage(fillStep.region().scale())) {
            updateFillPassRegion(fillStep.region().signature());
            fillPassCount++;
            fillObservation = null;
            if (fillPassCount >= MAX_FILL_PASSES) {
                pauseActiveProject(
                        client,
                        Component.translatable(
                                "message.mappywall.fill_coverage_too_low",
                                Math.round(observed.exploredFraction() * 100.0)
                        ),
                        ChatFormatting.YELLOW
                );
                return true;
            }
            activeSave = activeSave.withSession(activeSave.session().withFillWaypointIndex(0));
            movementController.hardReset(client);
            saveNow(client);
            return true;
        }

        activeSave = planner.advanceFillWaypoint(activeSave);
        movementController.hardReset(client);
        fillObservation = null;
        if (finalWaypoint) {
            fillPassCount = 0;
            fillPassRegionSignature = null;
        }
        saveNow(client);
        return true;
    }

    private void updateFillPassRegion(String regionSignature) {
        if (!Objects.equals(fillPassRegionSignature, regionSignature)) {
            fillPassRegionSignature = regionSignature;
            fillPassCount = 0;
        }
    }

    private double minimumFinalCoverage(int scale) {
        return switch (scale) {
            case 0 -> 0.90;
            case 1 -> 0.85;
            case 2 -> 0.82;
            case 3 -> 0.78;
            default -> 0.72;
        };
    }

    private boolean finishProjectIfDone(Minecraft client) {
        if (activeSave == null
                || planner.nextOpenStep(activeSave) != null
                || nextFillStepForRun(activeSave) != null
                || completedStepCount(activeSave) != activeSave.route().size()) {
            return false;
        }
        if (activeSave.project().status() != ProjectStatus.COMPLETE) {
            activeSave = activeSave.withProject(activeSave.project().withStatus(ProjectStatus.COMPLETE));
        }
        movementController.hardReset(client);
        movementPath = List.of();
        if (!saveNow(client)) {
            return true;
        }
        showCompletionSummary(client, activeSave);
        clearActiveProject();
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

    private void repairManualBindings(Minecraft client) {
        MapWallSave beforeRepair = activeSave;
        boolean wasConflict = beforeRepair.project().status() == ProjectStatus.CONFLICT;
        List<ObservedMap> observedMaps = inventoryScanner.scanFilledMaps(client);
        BindingRepairResult result = mapIndex.repairManualOpenings(beforeRepair, observedMaps, Instant.now());
        MapWallSave reconciled = result.bindings().equals(beforeRepair.bindings())
                ? beforeRepair
                : planner.reconcileBindings(beforeRepair, result.bindings());
        boolean routeOrBindingsChanged = !reconciled.bindings().equals(beforeRepair.bindings())
                || !reconciled.route().equals(beforeRepair.route());
        if (routeOrBindingsChanged) {
            // Inventory reconciliation can confirm an automatic opening before the
            // interaction controller observes its acknowledgement. Never carry that
            // pending opening into the next route region.
            mapOpenController.reset();
            notifyNewAliasChoices(client, beforeRepair, reconciled);
        }
        if (result.hasTrueConflicts()) {
            List<String> localConflictWarnings = result.warnings().stream()
                    .map(warning -> LOCAL_MAP_CONFLICT_PREFIX + warning)
                    .toList();
            boolean shouldNotify = !wasConflict
                    || !localConflictWarnings.equals(beforeRepair.session().warnings());
            MapWallSave desired = reconciled
                    .withProject(reconciled.project().withStatus(ProjectStatus.CONFLICT))
                    .withSession(reconciled.session().withPaused(true).withWarnings(localConflictWarnings));
            boolean changed = !desired.equals(beforeRepair);
            activeSave = desired;
            if (changed) {
                movementController.hardReset(client);
                saveNow(client);
            }
            if (shouldNotify) {
                client.player.sendSystemMessage(Component.literal(result.warnings().getFirst()).withStyle(ChatFormatting.RED));
                notifyTasksReferencingMapIds(client, result.trueConflictMapIds());
            }
        } else if (wasConflict) {
            boolean hadLocalConflict = beforeRepair.session().warnings().stream()
                    .anyMatch(this::isLocalMapConflictWarning);
            List<String> remainingWarnings = beforeRepair.session().warnings().stream()
                    .filter(warning -> !isLocalMapConflictWarning(warning))
                    .toList();
            boolean crossConflictRemains = remainingWarnings.stream().anyMatch(this::isCrossProjectWarning);
            MapWallSave desired = reconciled
                    .withProject(reconciled.project().withStatus(
                            crossConflictRemains ? ProjectStatus.CONFLICT : ProjectStatus.PAUSED
                    ))
                    .withSession(reconciled.session().withPaused(true).withWarnings(remainingWarnings));
            activeSave = desired;
            if (!desired.equals(beforeRepair)) {
                movementController.hardReset(client);
                saveNow(client);
            }
            if (hadLocalConflict && !crossConflictRemains) {
                client.player.sendSystemMessage(Component.translatable("message.mappywall.conflict_resolved")
                        .withStyle(ChatFormatting.GREEN));
            }
        } else {
            activeSave = reconciled;
            if (!activeSave.equals(beforeRepair)) {
                saveNow(client);
            }
        }
        if (routeOrBindingsChanged || result.hasTrueConflicts()) {
            auditCrossProjectMapIds(client, true);
        }
    }

    private void notifyNewAliasChoices(Minecraft client, MapWallSave before, MapWallSave after) {
        Map<String, List<MapBinding>> beforeByRegion = before.finalBindings().stream()
                .collect(java.util.stream.Collectors.groupingBy(MapBinding::regionSignature));
        Map<String, List<MapBinding>> afterByRegion = after.finalBindings().stream()
                .collect(java.util.stream.Collectors.groupingBy(MapBinding::regionSignature));
        for (RouteStep step : after.route()) {
            List<MapBinding> choices = afterByRegion.getOrDefault(step.region().signature(), List.of());
            int previousCount = beforeByRegion.getOrDefault(step.region().signature(), List.of()).size();
            if (choices.size() <= 1 || choices.size() == previousCount) {
                continue;
            }
            String ids = choices.stream()
                    .map(MapBinding::mapId)
                    .sorted()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("/"));
            String wallCell = (step.wallPos().column() + 1) + "," + (step.wallPos().row() + 1);
            client.player.sendSystemMessage(Component.translatable(
                    "message.mappywall.duplicate_map_choices",
                    wallCell,
                    ids
            ).withStyle(ChatFormatting.YELLOW));
        }
    }

    private void notifyTasksReferencingMapIds(Minecraft client, Set<Integer> mapIds) {
        if (mapIds.isEmpty()) {
            return;
        }
        WorldContext context = currentContext(client);
        List<PersistenceBridge.LoadedProject> projects = persistence.listServerProjects(context.serverKey());
        for (int mapId : mapIds.stream().sorted().toList()) {
            String projectIds = projects.stream()
                    .filter(project -> project.save().bindingForMapId(mapId).isPresent())
                    .map(project -> shortProjectId(project.save().project().id()))
                    .distinct()
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
            if (!projectIds.isEmpty()) {
                client.player.sendSystemMessage(Component.translatable(
                        "message.mappywall.true_map_id_conflict_tasks",
                        mapId,
                        projectIds
                ).withStyle(ChatFormatting.RED));
            }
        }
    }

    private void auditCrossProjectMapIds(Minecraft client, boolean notify) {
        if (!hasUsableWorld(client)) {
            return;
        }
        WorldContext context = currentContext(client);
        if (activeSave != null && Objects.equals(activeContext, context)) {
            // Never replace the in-memory active job with an older disk snapshot
            // when the latest progress could not be persisted.
            if (!saveNow(client)) {
                return;
            }
        }
        List<PersistenceBridge.LoadedProject> loadedProjects = persistence.listServerProjects(context.serverKey());
        List<CrossProjectMapIdIndex.Conflict> conflicts = crossProjectMapIdIndex.findConflicts(
                loadedProjects.stream().map(PersistenceBridge.LoadedProject::save).toList()
        );
        String fingerprint = conflicts.stream()
                .map(conflict -> conflict.mapId() + ":" + conflict.involvedProjectIds().stream()
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(",")))
                .collect(java.util.stream.Collectors.joining("|"));

        Map<String, List<String>> warningsByProject = new HashMap<>();
        for (CrossProjectMapIdIndex.Conflict conflict : conflicts) {
            String projectIds = conflict.involvedProjectIds().stream()
                    .map(this::shortProjectId)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
            for (String projectId : conflict.involvedProjectIds()) {
                warningsByProject.computeIfAbsent(projectId, ignored -> new ArrayList<>())
                        .add("map " + conflict.mapId() + " -> " + projectIds);
            }
            if (notify && !fingerprint.equals(lastCrossProjectConflictFingerprint)) {
                client.player.sendSystemMessage(Component.translatable(
                        "message.mappywall.cross_task_map_conflict",
                        conflict.mapId(),
                        projectIds
                ).withStyle(ChatFormatting.RED));
            }
        }

        for (PersistenceBridge.LoadedProject loaded : loadedProjects) {
            MapWallSave save = normalizeLoadedSave(loaded.save());
            List<String> nonCrossWarnings = save.session().warnings().stream()
                    .filter(warning -> !isCrossProjectWarning(warning))
                    .toList();
            List<String> conflictDetails = warningsByProject.getOrDefault(save.project().id(), List.of());
            MapWallSave desired = save;
            if (!conflictDetails.isEmpty()) {
                ProjectStatus previousStatus = previousStatusBeforeCrossConflict(save).orElse(save.project().status());
                List<String> warnings = new ArrayList<>(nonCrossWarnings);
                for (String detail : conflictDetails) {
                    warnings.add(crossProjectWarning(previousStatus, detail));
                }
                desired = save
                        .withProject(save.project().withStatus(ProjectStatus.CONFLICT))
                        .withSession(save.session().withPaused(true).withWarnings(warnings));
            } else if (save.session().warnings().stream().anyMatch(this::isCrossProjectWarning)) {
                List<String> warnings = new ArrayList<>(nonCrossWarnings);
                ProjectStatus restored = previousStatusBeforeCrossConflict(save).orElse(ProjectStatus.PAUSED);
                if (warnings.stream().anyMatch(this::isLocalMapConflictWarning)) {
                    restored = ProjectStatus.CONFLICT;
                } else if (restored != ProjectStatus.COMPLETE && restored != ProjectStatus.STOPPED) {
                    restored = ProjectStatus.PAUSED;
                }
                desired = save
                        .withProject(save.project().withStatus(restored))
                        .withSession(save.session().withPaused(restored != ProjectStatus.COMPLETE).withWarnings(warnings));
            }

            if (!desired.equals(loaded.save())) {
                try {
                    persistence.save(loaded.path(), desired);
                } catch (IOException exception) {
                    client.player.sendSystemMessage(Component.literal(
                            "MappyWall save failed: " + exception.getMessage()
                    ).withStyle(ChatFormatting.RED));
                }
            }
            if (activeSave != null
                    && activeSave.project().id().equals(desired.project().id())
                    && activeSave.project().dimension().equals(desired.project().dimension())) {
                boolean newlyPaused = !activeSave.session().paused() && desired.session().paused();
                activeSave = desired;
                activePath = loaded.path();
                if (newlyPaused) {
                    movementController.hardReset(client);
                    movementPath = List.of();
                }
            }
        }

        if (conflicts.isEmpty()
                && lastCrossProjectConflictFingerprint != null
                && !lastCrossProjectConflictFingerprint.isEmpty()
                && notify) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.conflict_resolved")
                    .withStyle(ChatFormatting.GREEN));
        }
        lastCrossProjectConflictFingerprint = fingerprint.isEmpty() ? null : fingerprint;
    }

    private String crossProjectWarning(ProjectStatus previousStatus, String detail) {
        return CROSS_PROJECT_WARNING_PREFIX + previousStatus.name() + "] " + detail;
    }

    private boolean isCrossProjectWarning(String warning) {
        return warning.startsWith(CROSS_PROJECT_WARNING_PREFIX);
    }

    private boolean isLocalMapConflictWarning(String warning) {
        return warning.startsWith(LOCAL_MAP_CONFLICT_PREFIX);
    }

    private Optional<ProjectStatus> previousStatusBeforeCrossConflict(MapWallSave save) {
        return save.session().warnings().stream()
                .filter(this::isCrossProjectWarning)
                .map(warning -> {
                    int end = warning.indexOf(']', CROSS_PROJECT_WARNING_PREFIX.length());
                    if (end < 0) {
                        return null;
                    }
                    try {
                        return ProjectStatus.valueOf(warning.substring(CROSS_PROJECT_WARNING_PREFIX.length(), end));
                    } catch (IllegalArgumentException invalidStatus) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .findFirst();
    }

    private String shortProjectId(String projectId) {
        return projectId.length() <= 8 ? projectId : projectId.substring(0, 8);
    }

    private void loadMostRecentProject(Minecraft client) {
        WorldContext context = currentContext(client);
        auditCrossProjectMapIds(client, true);
        Optional<PersistenceBridge.LoadedProject> loaded = persistence
                .listProjects(context.serverKey(), context.dimension())
                .stream()
                .map(project -> new PersistenceBridge.LoadedProject(
                        project.path(),
                        normalizeLoadedSave(project.save())
                ))
                .filter(project -> project.save().project().status() != ProjectStatus.COMPLETE)
                .filter(project -> project.save().project().status() != ProjectStatus.STOPPED)
                .findFirst();
        if (loaded.isPresent()) {
            MapWallSave loadedSave = loaded.get().save();
            boolean conflict = loadedSave.project().status() == ProjectStatus.CONFLICT;
            activeSave = loadedSave
                    .withProject(loadedSave.project().withStatus(conflict ? ProjectStatus.CONFLICT : ProjectStatus.PAUSED))
                    .withSession(loadedSave.session().withPaused(true));
            activePath = loaded.get().path();
            activeContext = context;
            resetTransientAutomationState(false);
            saveNow(client);
        }
    }

    private MapWallSave normalizeLoadedSave(MapWallSave save) {
        return planner.reconcileBindings(save, save.bindings());
    }

    private void ensureWorldContext(Minecraft client) {
        WorldContext context = currentContext(client);
        if (Objects.equals(activeContext, context)) {
            return;
        }

        saveNow(client);
        activeSave = null;
        activePath = null;
        activeContext = context;
        ticksSinceSave = 0;
        resetTransientAutomationState(true);
        movementController.hardReset(client);
    }

    private boolean isActiveContext(Minecraft client) {
        return hasUsableWorld(client) && Objects.equals(activeContext, currentContext(client));
    }

    private void periodicSave(Minecraft client) {
        ticksSinceSave++;
        if (ticksSinceSave >= SAVE_INTERVAL_TICKS) {
            saveNow(client);
        }
    }

    private boolean saveNow(Minecraft client) {
        if (activeSave == null || activePath == null) {
            return false;
        }
        try {
            persistence.save(activePath, activeSave);
            ticksSinceSave = 0;
            return true;
        } catch (IOException exception) {
            ticksSinceSave = 0;
            client.player.sendSystemMessage(Component.literal("MappyWall save failed: " + exception.getMessage())
                    .withStyle(ChatFormatting.RED));
            return false;
        }
    }

    private void showCompletionSummary(Minecraft client, MapWallSave save) {
        client.player.sendSystemMessage(Component.translatable(
                "message.mappywall.complete_summary",
                completedStepCount(save)
        ).withStyle(ChatFormatting.GREEN));
    }

    private void showCompletionOrder(Minecraft client, MapWallSave save) {
        if (save.bindings().isEmpty()) {
            client.player.sendSystemMessage(Component.translatable("message.mappywall.order_empty").withStyle(ChatFormatting.YELLOW));
            return;
        }

        client.player.sendSystemMessage(Component.translatable("message.mappywall.hanging_order"));
        for (String line : hangingOrderFormatter.format(save)) {
            client.player.sendSystemMessage(Component.literal(line));
        }
    }

    private void clearActiveProject() {
        movementController.hardReset(Minecraft.getInstance());
        activeSave = null;
        activePath = null;
        movementPath = List.of();
        resetTransientAutomationState(false);
    }

    private void pauseActiveProject(Minecraft client, Component message, ChatFormatting style) {
        if (activeSave == null) {
            return;
        }
        activeSave = activeSave
                .withProject(activeSave.project().withStatus(ProjectStatus.PAUSED))
                .withSession(activeSave.session().withPaused(true).withWarnings(List.of(message.getString())));
        movementController.hardReset(client);
        movementPath = List.of();
        fillObservation = null;
        client.player.sendSystemMessage(message.copy().withStyle(style));
        saveNow(client);
    }

    private void tickInteractionCooldowns() {
        if (inventoryInteractionCooldown > 0) {
            inventoryInteractionCooldown--;
        }
        if (zoomAck != null) {
            zoomAck = zoomAck.tick();
            if (zoomAck.ticksRemaining() <= 0) {
                zoomTimedOutRegion = zoomAck.regionSignature();
                zoomAck = null;
                inventoryInteractionCooldown = INVENTORY_ACK_TICKS;
                interactionHint = Component.translatable("message.mappywall.auto_zoom_ack_timeout");
            }
        }
    }

    private void resetTransientAutomationState(boolean clearScannerCache) {
        mapOpenController.reset();
        inventoryInteractionCooldown = 0;
        fillPassCount = 0;
        fillPassRegionSignature = null;
        zoomAck = null;
        zoomTimedOutRegion = null;
        fillObservation = null;
        interactionHint = null;
        bindingRepairCooldown = 0;
        if (clearScannerCache) {
            inventoryScanner.clearCache();
        }
    }

    private void releaseMovementIfAutomatic(Minecraft client) {
        if (activeSave != null && activeSave.project().mode().isAutomatic()) {
            movementController.release(client);
        }
    }

    private boolean hasUsableWorld(Minecraft client) {
        return client.player != null && client.level != null;
    }

    private String dimensionKey(Minecraft client) {
        return client.level.dimension().identifier().toString();
    }

    private String serverKey(Minecraft client) {
        if (client.getCurrentServer() != null) {
            return "server_" + client.getCurrentServer().ip;
        }
        if (client.getSingleplayerServer() != null) {
            try {
                return "singleplayer_" + client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            } catch (RuntimeException exception) {
                return "singleplayer_" + client.getSingleplayerServer().getWorldData().getLevelName();
            }
        }
        return "unknown";
    }

    private WorldContext currentContext(Minecraft client) {
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

        Optional<LoadedProject> loadProject(String serverKey, String dimension, String projectId) {
            Path path = projectPath(serverKey, dimension, projectId);
            try {
                return service.load(path).map(save -> new LoadedProject(path, save));
            } catch (IOException exception) {
                return Optional.empty();
            }
        }

        boolean deleteProject(String serverKey, String dimension, String projectId) {
            return deleteProjectPath(projectPath(serverKey, dimension, projectId));
        }

        boolean deleteCorruptProject(String serverKey, String dimension, String deleteToken) {
            if (!deleteToken.startsWith(CORRUPT_DELETE_PREFIX)) {
                return false;
            }
            String fileName = deleteToken.substring(CORRUPT_DELETE_PREFIX.length());
            Path dimensionDir = configRoot.resolve(sanitize(serverKey)).resolve(sanitize(dimension)).toAbsolutePath().normalize();
            Path path = dimensionDir.resolve(fileName).toAbsolutePath().normalize();
            if (!Objects.equals(path.getParent(), dimensionDir) || !path.getFileName().toString().endsWith(".json")) {
                return false;
            }
            return deleteProjectPath(path);
        }

        private boolean deleteProjectPath(Path path) {
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (IOException ignored) {
                return false;
            }
            if (java.nio.file.Files.exists(path)) {
                return false;
            }
            try {
                java.nio.file.Files.deleteIfExists(service.backupPath(path));
            } catch (IOException ignored) {
                return false;
            }
            return !java.nio.file.Files.exists(service.backupPath(path));
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
                        .toList();
            } catch (IOException exception) {
                return List.of();
            }
        }

        List<LoadedProject> listServerProjects(String serverKey) {
            Path serverDir = configRoot.resolve(sanitize(serverKey));
            if (!java.nio.file.Files.isDirectory(serverDir)) {
                return List.of();
            }
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(serverDir, 2)) {
                return files
                        .filter(java.nio.file.Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .map(path -> {
                            try {
                                return service.load(path).map(save -> new LoadedProject(path, save)).orElse(null);
                            } catch (IOException exception) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .toList();
            } catch (IOException exception) {
                return List.of();
            }
        }

        List<CorruptProject> listCorruptProjects(String serverKey, String dimension) {
            Path dimensionDir = configRoot.resolve(sanitize(serverKey)).resolve(sanitize(dimension));
            if (!java.nio.file.Files.isDirectory(dimensionDir)) {
                return List.of();
            }
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dimensionDir)) {
                return files
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> {
                            try {
                                return service.load(path).isEmpty();
                            } catch (IOException exception) {
                                return true;
                            }
                        })
                        .map(path -> {
                            String name = path.getFileName().toString();
                            return new CorruptProject(path, name.substring(0, name.length() - ".json".length()));
                        })
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

        private record CorruptProject(Path path, String projectId) {
        }
    }

    private record WorldContext(String serverKey, String dimension) {
    }

    private record ZoomAck(
            String regionSignature,
            int sourceMapId,
            int expectedScale,
            Set<Integer> knownMapIds,
            int ticksRemaining
    ) {
        private ZoomAck {
            knownMapIds = Set.copyOf(knownMapIds);
        }

        ZoomAck tick() {
            return new ZoomAck(regionSignature, sourceMapId, expectedScale, knownMapIds, ticksRemaining - 1);
        }
    }

    private static final class FillObservation {
        private final String regionSignature;
        private final int mapId;
        private final int waypointIndex;
        private final double baselineCoverage;
        private double maximumCoverage;
        private int dwellTicks;
        private int stableTicks;
        private boolean sawGrowth;

        private FillObservation(String regionSignature, int mapId, int waypointIndex, double coverage) {
            this.regionSignature = regionSignature;
            this.mapId = mapId;
            this.waypointIndex = waypointIndex;
            this.baselineCoverage = coverage;
            this.maximumCoverage = coverage;
        }

        boolean matches(String expectedRegionSignature, int expectedMapId, int expectedWaypointIndex) {
            return regionSignature.equals(expectedRegionSignature)
                    && mapId == expectedMapId
                    && waypointIndex == expectedWaypointIndex;
        }

        void observe(double coverage) {
            dwellTicks++;
            if (coverage > maximumCoverage + COVERAGE_EPSILON) {
                maximumCoverage = coverage;
                stableTicks = 0;
                sawGrowth = true;
            } else {
                stableTicks++;
            }
        }

        double baselineCoverage() {
            return baselineCoverage;
        }

        int dwellTicks() {
            return dwellTicks;
        }

        int stableTicks() {
            return stableTicks;
        }

        boolean sawGrowth() {
            return sawGrowth;
        }
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
            boolean active,
            boolean corrupt,
            String deleteId
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

package dev.mappywall.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MapWallPlanner {
    public MapWallProject createProject(
            String id,
            String serverKey,
            String dimension,
            int scale,
            int width,
            int height,
            double playerX,
            double playerZ,
            RunMode mode
    ) {
        return createProject(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                playerX,
                playerZ,
                mode,
                WallAnchorMode.FIRST_REGION,
                1,
                1,
                PostOpenMode.OPEN_FIRST
        );
    }

    public MapWallProject createProject(
            String id,
            String serverKey,
            String dimension,
            int scale,
            int width,
            int height,
            double playerX,
            double playerZ,
            RunMode mode,
            WallAnchorMode anchorMode,
            int columnStepX,
            int rowStepZ
    ) {
        return createProject(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                playerX,
                playerZ,
                mode,
                anchorMode,
                columnStepX,
                rowStepZ,
                PostOpenMode.OPEN_FIRST,
                AutomationStyle.NORMAL
        );
    }

    public MapWallProject createProject(
            String id,
            String serverKey,
            String dimension,
            int scale,
            int width,
            int height,
            double playerX,
            double playerZ,
            RunMode mode,
            WallAnchorMode anchorMode,
            int columnStepX,
            int rowStepZ,
            PostOpenMode postOpenMode
    ) {
        return createProject(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                playerX,
                playerZ,
                mode,
                anchorMode,
                columnStepX,
                rowStepZ,
                postOpenMode,
                AutomationStyle.NORMAL
        );
    }

    public MapWallProject createProject(
            String id,
            String serverKey,
            String dimension,
            int scale,
            int width,
            int height,
            double playerX,
            double playerZ,
            RunMode mode,
            WallAnchorMode anchorMode,
            int columnStepX,
            int rowStepZ,
            PostOpenMode postOpenMode,
            AutomationStyle automationStyle
    ) {
        validateDirectionStep(columnStepX, "columnStepX");
        validateDirectionStep(rowStepZ, "rowStepZ");
        Objects.requireNonNull(anchorMode, "anchorMode");
        if (postOpenMode == null) {
            postOpenMode = PostOpenMode.OPEN_FIRST;
        }
        if (automationStyle == null) {
            automationStyle = AutomationStyle.NORMAL;
        }
        MapRegion reference = MapRegionMath.regionForBlock(dimension, scale, playerX, playerZ);
        MapRegion anchor = anchorFor(reference, width, height, anchorMode, columnStepX, rowStepZ);
        return new MapWallProject(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                anchor,
                mode,
                postOpenMode,
                automationStyle,
                ProjectStatus.RUNNING,
                Instant.now(),
                columnStepX,
                rowStepZ
        );
    }

    public List<RouteStep> planRoute(MapWallProject project) {
        return planRoute(project, project.effectiveColumnStepX(), project.effectiveRowStepZ());
    }

    public List<RouteStep> planRoute(MapWallProject project, int columnStepX, int rowStepZ) {
        Objects.requireNonNull(project, "project");
        validateDirectionStep(columnStepX, "columnStepX");
        validateDirectionStep(rowStepZ, "rowStepZ");
        List<RouteStep> steps = new ArrayList<>(project.mapCount());

        for (int row = 0; row < project.height(); row++) {
            boolean reverse = row % 2 == 1;
            for (int i = 0; i < project.width(); i++) {
                int column = reverse ? project.width() - 1 - i : i;
                MapRegion region = MapRegionMath.offset(project.anchorRegion(), column * columnStepX, row * rowStepZ);
                steps.add(new RouteStep(
                        new WallPos(column, row),
                        region,
                        new BlockTarget(region.centerX(), region.centerZ()),
                        RouteStepState.PENDING
                ));
            }
        }

        return steps;
    }

    public MapWallSave createSave(MapWallProject project) {
        return createSave(project, project.effectiveColumnStepX(), project.effectiveRowStepZ());
    }

    public MapWallSave createSave(MapWallProject project, int columnStepX, int rowStepZ) {
        if (project.columnStepX() != 0 && project.columnStepX() != columnStepX) {
            throw new IllegalArgumentException("columnStepX does not match the project direction");
        }
        if (project.rowStepZ() != 0 && project.rowStepZ() != rowStepZ) {
            throw new IllegalArgumentException("rowStepZ does not match the project direction");
        }
        List<RouteStep> route = planRoute(project, columnStepX, rowStepZ);
        RunSessionState session = new RunSessionState(
                0,
                false,
                null,
                project.mode().name(),
                List.of()
        );
        return new MapWallSave(MapWallSave.CURRENT_SCHEMA_VERSION, project, route, List.of(), session);
    }

    public RouteStep nextOpenStep(MapWallSave save) {
        for (RouteStep step : save.route()) {
            boolean hasAnyBinding = hasBinding(save, step.region().signature());
            if (!hasAnyBinding || !hasTrustedOpeningBinding(save, step.region().signature())) {
                return step;
            }
        }
        return null;
    }

    public MapWallSave migrateLegacyBindingData(MapWallSave save) {
        Objects.requireNonNull(save, "save");
        if (save.session().bindingDataVersion() >= RunSessionState.CURRENT_BINDING_DATA_VERSION) {
            return save;
        }
        List<MapBinding> provisional = save.bindings().stream()
                .map(binding -> new MapBinding(
                        binding.wallPos(),
                        binding.regionSignature(),
                        binding.mapId(),
                        binding.openedAt(),
                        BindingVerification.TARGET_CAPTURE
                ))
                .toList();
        RunSessionState migratedSession = save.session()
                .withPendingMapOpening(null)
                .withPendingMapZoom(null)
                .withBindingDataVersion(RunSessionState.CURRENT_BINDING_DATA_VERSION);
        return reconcileBindings(save.withSession(migratedSession), provisional);
    }

    public MapWallSave bindCurrentStep(MapWallSave save, int mapId, Instant openedAt, BindingVerification verifiedBy) {
        for (MapBinding binding : save.bindings()) {
            if (binding.mapId() == mapId) {
                return save;
            }
        }
        RouteStep next = nextOpenStep(save);
        if (next == null) {
            return save.route().stream().allMatch(step -> step.state() == RouteStepState.BOUND)
                    ? save.withProject(save.project().withStatus(ProjectStatus.COMPLETE))
                    : save;
        }

        return bindStep(save, next, mapId, openedAt, verifiedBy);
    }

    public MapWallSave bindStep(
            MapWallSave save,
            RouteStep target,
            int mapId,
            Instant openedAt,
            BindingVerification verifiedBy
    ) {
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(verifiedBy, "verifiedBy");
        RouteStep canonicalTarget = save.route().stream()
                .filter(step -> step.wallPos().equals(target.wallPos()))
                .filter(step -> step.region().signature().equals(target.region().signature()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("target is outside the route"));
        if (save.bindingForMapId(mapId).isPresent()) {
            return save;
        }
        List<MapBinding> bindings = new ArrayList<>(save.bindings());
        bindings.add(new MapBinding(
                canonicalTarget.wallPos(),
                canonicalTarget.region().signature(),
                mapId,
                openedAt,
                verifiedBy
        ));
        return reconcileBindings(save, bindings);
    }

    /**
     * Applies a repaired binding set as one aggregate state transition. This avoids the
     * legacy state where a manually discovered binding was persisted while its route step
     * remained PENDING and was therefore skipped by fill-after-open processing.
     */
    public MapWallSave reconcileBindings(MapWallSave save, List<MapBinding> repairedBindings) {
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(repairedBindings, "repairedBindings");

        MapWallSave normalized = new MapWallSave(
                save.schemaVersion(),
                save.project(),
                save.route(),
                repairedBindings,
                save.session()
        );
        Set<String> boundRegions = new HashSet<>();
        for (MapBinding binding : normalized.bindings()) {
            boundRegions.add(binding.regionSignature());
        }

        String previousFillRegion = firstOpenFillRegion(save.route(), save.bindings());
        List<RouteStep> route = new ArrayList<>(save.route().size());
        for (RouteStep step : save.route()) {
            boolean bound = boundRegions.contains(step.region().signature());
            RouteStepState state = step.state();
            if (bound) {
                boolean targetScaleReady = !normalized.finalBindingsForRegion(step.region().signature()).isEmpty();
                if (save.project().postOpenMode() == PostOpenMode.FILL_AFTER_OPEN) {
                    state = state == RouteStepState.BOUND && targetScaleReady
                            ? RouteStepState.BOUND
                            : RouteStepState.OPENED;
                } else {
                    state = targetScaleReady ? RouteStepState.BOUND : RouteStepState.OPENED;
                }
            } else if (state == RouteStepState.OPENED || state == RouteStepState.BOUND) {
                state = RouteStepState.PENDING;
            }
            route.add(step.withState(state));
        }

        int currentStep = route.size();
        for (int index = 0; index < route.size(); index++) {
            if (!boundRegions.contains(route.get(index).region().signature())) {
                currentStep = index;
                break;
            }
        }
        String nextFillRegion = firstOpenFillRegion(route, normalized.bindings());
        int fillWaypointIndex = Objects.equals(previousFillRegion, nextFillRegion)
                ? save.session().fillWaypointIndex()
                : 0;
        RunSessionState session = save.session()
                .withCurrentStep(currentStep)
                .withFillWaypointIndex(fillWaypointIndex);

        boolean complete = route.stream().allMatch(step -> step.state() == RouteStepState.BOUND);
        ProjectStatus status = save.project().status();
        if (complete && status != ProjectStatus.CONFLICT && status != ProjectStatus.STOPPED) {
            status = ProjectStatus.COMPLETE;
        } else if (status == ProjectStatus.COMPLETE) {
            status = ProjectStatus.RUNNING;
        }
        return new MapWallSave(
                save.schemaVersion(),
                save.project().withStatus(status),
                route,
                normalized.bindings(),
                session
        );
    }

    public RouteStep nextFillStep(MapWallSave save) {
        if (save.project().postOpenMode() != PostOpenMode.FILL_AFTER_OPEN) {
            return null;
        }
        for (RouteStep step : save.route()) {
            if (!hasBinding(save, step.region().signature())) {
                // A wrong-region opening may legitimately pre-bind a later route
                // cell. Do not let that future alias pull navigation away from the
                // earliest missing cell; open the replacement first.
                return null;
            }
            if (!hasTrustedOpeningBinding(save, step.region().signature())) {
                // Legacy client observations may have used placeholder centers.
                // Reopen from a validated position before filling at any scale.
                return null;
            }
            if (step.state() == RouteStepState.OPENED && hasBinding(save, step.region().signature())) {
                return step;
            }
        }
        return null;
    }

    public RouteStep fillNavigationStep(MapWallSave save, RouteStep step) {
        List<BlockTarget> targets = fillTargets(step.region());
        BlockTarget target = targets.get(Math.min(
                save.session().fillWaypointIndex(),
                targets.size() - 1
        ));
        return new RouteStep(step.wallPos(), step.region(), target, RouteStepState.OPENED);
    }

    public MapWallSave advanceFillWaypoint(MapWallSave save) {
        RouteStep fillStep = nextFillStep(save);
        if (fillStep == null) {
            return save;
        }

        List<BlockTarget> targets = fillTargets(fillStep.region());
        int nextWaypoint = save.session().fillWaypointIndex() + 1;
        if (nextWaypoint < targets.size()) {
            return save.withSession(save.session().withFillWaypointIndex(nextWaypoint));
        }

        List<RouteStep> route = new ArrayList<>(save.route());
        int stepIndex = route.indexOf(fillStep);
        route.set(stepIndex, fillStep.withState(RouteStepState.BOUND));
        RunSessionState session = save.session().withFillWaypointIndex(0);
        MapWallSave updated = new MapWallSave(
                MapWallSave.CURRENT_SCHEMA_VERSION,
                save.project(),
                route,
                save.bindings(),
                session
        );
        if (nextOpenStep(updated) == null && nextFillStep(updated) == null) {
            updated = updated.withProject(updated.project().withStatus(ProjectStatus.COMPLETE));
        }
        return updated;
    }

    public int fillWaypointCount(MapRegion region) {
        return fillTargets(region).size();
    }

    private boolean hasTrustedOpeningBinding(MapWallSave save, String regionSignature) {
        return save.bindingsForRegion(regionSignature).stream()
                .anyMatch(binding -> binding.verifiedBy() != BindingVerification.TARGET_CAPTURE
                        && binding.verifiedBy() != BindingVerification.PENDING_VERIFICATION);
    }

    private boolean hasBinding(MapWallSave save, String regionSignature) {
        for (MapBinding binding : save.bindings()) {
            if (binding.regionSignature().equals(regionSignature)) {
                return true;
            }
        }
        return false;
    }

    private List<BlockTarget> fillTargets(MapRegion region) {
        MapBounds bounds = region.bounds();
        int width = bounds.maxX() - bounds.minX() + 1;
        int depth = bounds.maxZ() - bounds.minZ() + 1;
        int samples = fillSamplesPerAxis(region.scale());
        if (samples <= 1) {
            return List.of(new BlockTarget(region.centerX(), region.centerZ()));
        }

        int marginX = Math.max(8, Math.min(64, width / 8));
        int marginZ = Math.max(8, Math.min(64, depth / 8));
        int west = bounds.minX() + marginX;
        int east = bounds.maxX() - marginX;
        int north = bounds.minZ() + marginZ;
        int south = bounds.maxZ() - marginZ;

        List<BlockTarget> targets = new ArrayList<>(samples * samples + 1);
        Set<BlockTarget> seen = new LinkedHashSet<>();
        addFillTarget(targets, seen, new BlockTarget(region.centerX(), region.centerZ()));
        for (int row = 0; row < samples; row++) {
            int z = interpolate(north, south, row, samples);
            if (row % 2 == 0) {
                for (int column = 0; column < samples; column++) {
                    addFillTarget(targets, seen, new BlockTarget(interpolate(west, east, column, samples), z));
                }
            } else {
                for (int column = samples - 1; column >= 0; column--) {
                    addFillTarget(targets, seen, new BlockTarget(interpolate(west, east, column, samples), z));
                }
            }
        }
        return List.copyOf(targets);
    }

    private int fillSamplesPerAxis(int scale) {
        return switch (scale) {
            case 0 -> 1;
            case 1 -> 3;
            case 2 -> 4;
            case 3 -> 5;
            default -> 7;
        };
    }

    private int interpolate(int min, int max, int index, int samples) {
        if (samples <= 1) {
            return (min + max) / 2;
        }
        return Math.round(min + (max - min) * (index / (float) (samples - 1)));
    }

    private void addFillTarget(List<BlockTarget> targets, Set<BlockTarget> seen, BlockTarget target) {
        if (seen.add(target)) {
            targets.add(target);
        }
    }

    private String firstOpenFillRegion(List<RouteStep> route, List<MapBinding> bindings) {
        Set<String> boundRegions = new HashSet<>();
        for (MapBinding binding : bindings) {
            boundRegions.add(binding.regionSignature());
        }
        for (RouteStep step : route) {
            if (step.state() == RouteStepState.OPENED && boundRegions.contains(step.region().signature())) {
                return step.region().signature();
            }
        }
        return null;
    }

    private MapRegion anchorFor(
            MapRegion reference,
            int width,
            int height,
            WallAnchorMode anchorMode,
            int columnStepX,
            int rowStepZ
    ) {
        if (anchorMode == WallAnchorMode.FIRST_REGION) {
            return reference;
        }

        int anchorGridX = checkedGridCoordinate(
                (long) reference.gridX() - (long) Math.floorDiv(width, 2) * columnStepX,
                "anchor grid X"
        );
        int anchorGridZ = checkedGridCoordinate(
                (long) reference.gridZ() - (long) Math.floorDiv(height, 2) * rowStepZ,
                "anchor grid Z"
        );
        return MapRegionMath.regionForGrid(reference.dimension(), reference.scale(), anchorGridX, anchorGridZ);
    }

    private int checkedGridCoordinate(long value, String name) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return (int) value;
    }

    private void validateDirectionStep(int step, String name) {
        if (step != 1 && step != -1) {
            throw new IllegalArgumentException(name + " must be 1 or -1");
        }
    }
}

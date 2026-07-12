package dev.mappywall.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record MapWallSave(
        int schemaVersion,
        MapWallProject project,
        List<RouteStep> route,
        List<MapBinding> bindings,
        RunSessionState session
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public MapWallSave {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema version: " + schemaVersion);
        }
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(session, "session");
        route = List.copyOf(route);
        bindings = normalizeBindings(bindings);
        project = resolveLegacyDirections(project, route);
        validateRoute(project, route);
        validateBindings(route, bindings);
        if (session.currentStep() > route.size()) {
            throw new IllegalArgumentException("session currentStep exceeds route size");
        }
    }

    public MapWallSave withProject(MapWallProject newProject) {
        return new MapWallSave(schemaVersion, newProject, route, bindings, session);
    }

    public MapWallSave withBindings(List<MapBinding> newBindings) {
        return new MapWallSave(schemaVersion, project, route, newBindings, session);
    }

    public MapWallSave withSession(RunSessionState newSession) {
        return new MapWallSave(schemaVersion, project, route, bindings, newSession);
    }

    /** Returns every map id associated with a route region, including zoom ancestors. */
    public List<MapBinding> bindingsForRegion(String regionSignature) {
        Objects.requireNonNull(regionSignature, "regionSignature");
        return bindings.stream()
                .filter(binding -> binding.regionSignature().equals(regionSignature))
                .toList();
    }

    /**
     * Returns only maps that are actually suitable for the finished wall. Lower-scale
     * zoom ancestors remain useful automation state, but they are not interchangeable
     * hanging choices for a higher-scale target.
     */
    public List<MapBinding> finalBindingsForRegion(String regionSignature) {
        RouteStep step = route.stream()
                .filter(candidate -> candidate.region().signature().equals(regionSignature))
                .findFirst()
                .orElse(null);
        if (step == null) {
            return List.of();
        }
        return bindingsForRegion(regionSignature).stream()
                .filter(binding -> step.region().scale() == 0
                        ? binding.verifiedBy() == BindingVerification.MAP_STATE
                                || binding.verifiedBy() == BindingVerification.MANUAL_REPAIR
                                || binding.verifiedBy() == BindingVerification.TARGET_SCALE
                        : binding.verifiedBy() == BindingVerification.TARGET_SCALE)
                .toList();
    }

    public List<MapBinding> finalBindings() {
        Map<String, Integer> scaleByRegion = new HashMap<>();
        for (RouteStep step : route) {
            scaleByRegion.put(step.region().signature(), step.region().scale());
        }
        return bindings.stream()
                .filter(binding -> {
                    Integer scale = scaleByRegion.get(binding.regionSignature());
                    if (scale == null) {
                        return false;
                    }
                    return scale == 0
                            ? binding.verifiedBy() == BindingVerification.MAP_STATE
                                    || binding.verifiedBy() == BindingVerification.MANUAL_REPAIR
                                    || binding.verifiedBy() == BindingVerification.TARGET_SCALE
                            : binding.verifiedBy() == BindingVerification.TARGET_SCALE;
                })
                .toList();
    }

    /**
     * Chooses the most useful representative of a region while retaining every alias
     * in {@link #bindings()}.  Callers that need one map for filling or zooming should
     * use this instead of depending on list order.
     */
    public Optional<MapBinding> preferredBindingForRegion(String regionSignature) {
        return bindingsForRegion(regionSignature).stream()
                .max(java.util.Comparator
                        .comparingInt((MapBinding binding) -> verificationStrength(binding.verifiedBy()))
                        .thenComparing(java.util.Comparator.comparingInt(MapBinding::mapId).reversed()));
    }

    public Optional<MapBinding> bindingForMapId(int mapId) {
        return bindings.stream()
                .filter(binding -> binding.mapId() == mapId)
                .findFirst();
    }

    private static MapWallProject resolveLegacyDirections(MapWallProject project, List<RouteStep> route) {
        if (project.columnStepX() != 0 && project.rowStepZ() != 0) {
            return project;
        }

        Map<WallPos, RouteStep> byWallPosition = new HashMap<>();
        for (RouteStep step : route) {
            byWallPosition.putIfAbsent(step.wallPos(), step);
        }
        int columnStep = project.columnStepX() == 0
                ? inferStep(byWallPosition, new WallPos(0, 0), new WallPos(1, 0), true)
                : project.columnStepX();
        int rowStep = project.rowStepZ() == 0
                ? inferStep(byWallPosition, new WallPos(0, 0), new WallPos(0, 1), false)
                : project.rowStepZ();
        return project.withDirections(columnStep, rowStep);
    }

    private static int inferStep(
            Map<WallPos, RouteStep> route,
            WallPos originPosition,
            WallPos adjacentPosition,
            boolean xAxis
    ) {
        RouteStep origin = route.get(originPosition);
        RouteStep adjacent = route.get(adjacentPosition);
        if (origin == null || adjacent == null) {
            return 1;
        }
        int originGrid = xAxis ? origin.region().gridX() : origin.region().gridZ();
        int adjacentGrid = xAxis ? adjacent.region().gridX() : adjacent.region().gridZ();
        long difference = (long) adjacentGrid - originGrid;
        if (difference != -1 && difference != 1) {
            throw new IllegalArgumentException("legacy route direction is not adjacent");
        }
        return (int) difference;
    }

    private static void validateRoute(MapWallProject project, List<RouteStep> route) {
        if (route.size() != project.mapCount()) {
            throw new IllegalArgumentException("route size does not match project dimensions");
        }

        MapRegion canonicalAnchor = MapRegionMath.regionForGrid(
                project.dimension(),
                project.scale(),
                project.anchorRegion().gridX(),
                project.anchorRegion().gridZ()
        );
        if (!canonicalAnchor.equals(project.anchorRegion())) {
            throw new IllegalArgumentException("project anchor region is not canonical");
        }

        Map<WallPos, RouteStep> byWallPosition = new HashMap<>();
        Map<String, WallPos> wallPositionByRegion = new HashMap<>();
        for (RouteStep step : route) {
            WallPos wallPos = step.wallPos();
            if (wallPos.column() >= project.width() || wallPos.row() >= project.height()) {
                throw new IllegalArgumentException("route wall position is outside project dimensions: " + wallPos);
            }
            if (byWallPosition.put(wallPos, step) != null) {
                throw new IllegalArgumentException("duplicate route wall position: " + wallPos);
            }

            int expectedGridX = checkedGridOffset(
                    project.anchorRegion().gridX(),
                    wallPos.column(),
                    project.columnStepX()
            );
            int expectedGridZ = checkedGridOffset(
                    project.anchorRegion().gridZ(),
                    wallPos.row(),
                    project.rowStepZ()
            );
            MapRegion expectedRegion = MapRegionMath.regionForGrid(
                    project.dimension(),
                    project.scale(),
                    expectedGridX,
                    expectedGridZ
            );
            if (!expectedRegion.equals(step.region())) {
                throw new IllegalArgumentException("route region does not match its wall position: " + wallPos);
            }
            WallPos previous = wallPositionByRegion.put(step.region().signature(), wallPos);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate route region: " + step.region().signature());
            }
        }
    }

    private static int checkedGridOffset(int anchor, int index, int direction) {
        long result = (long) anchor + (long) index * direction;
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("route grid coordinate overflows the supported range");
        }
        return (int) result;
    }

    private static List<MapBinding> normalizeBindings(List<MapBinding> bindings) {
        LinkedHashMap<Integer, MapBinding> byMapId = new LinkedHashMap<>();
        Map<Integer, String> regionByMapId = new HashMap<>();
        Map<WallPos, String> regionByWallPosition = new HashMap<>();
        for (MapBinding binding : List.copyOf(bindings)) {
            Objects.requireNonNull(binding, "binding");
            String mapIdRegion = regionByMapId.putIfAbsent(binding.mapId(), binding.regionSignature());
            if (mapIdRegion != null && !mapIdRegion.equals(binding.regionSignature())) {
                throw new IllegalArgumentException("map id " + binding.mapId() + " is bound to multiple regions");
            }
            String wallRegion = regionByWallPosition.putIfAbsent(binding.wallPos(), binding.regionSignature());
            if (wallRegion != null && !wallRegion.equals(binding.regionSignature())) {
                throw new IllegalArgumentException("wall position " + binding.wallPos() + " is bound to multiple regions");
            }

            MapBinding existing = byMapId.get(binding.mapId());
            if (existing == null) {
                byMapId.put(binding.mapId(), binding);
                continue;
            }
            if (!existing.regionSignature().equals(binding.regionSignature())
                    || !existing.wallPos().equals(binding.wallPos())) {
                throw new IllegalArgumentException("map id " + binding.mapId() + " has conflicting bindings");
            }
            byMapId.put(binding.mapId(), mergeDuplicateBinding(existing, binding));
        }
        return List.copyOf(byMapId.values());
    }

    private static MapBinding mergeDuplicateBinding(MapBinding left, MapBinding right) {
        Instant openedAt = left.openedAt().isBefore(right.openedAt()) ? left.openedAt() : right.openedAt();
        BindingVerification verifiedBy = verificationStrength(left.verifiedBy()) >= verificationStrength(right.verifiedBy())
                ? left.verifiedBy()
                : right.verifiedBy();
        return new MapBinding(left.wallPos(), left.regionSignature(), left.mapId(), openedAt, verifiedBy);
    }

    private static int verificationStrength(BindingVerification verification) {
        return switch (verification) {
            case PENDING_VERIFICATION -> 0;
            case TARGET_CAPTURE -> 1;
            case MANUAL_REPAIR -> 2;
            case MAP_STATE -> 3;
            case TARGET_SCALE -> 4;
        };
    }

    private static void validateBindings(List<RouteStep> route, List<MapBinding> bindings) {
        Map<String, RouteStep> routeByRegion = new HashMap<>();
        for (RouteStep step : route) {
            routeByRegion.put(step.region().signature(), step);
        }
        for (MapBinding binding : bindings) {
            RouteStep step = routeByRegion.get(binding.regionSignature());
            if (step == null) {
                throw new IllegalArgumentException("binding references a region outside the route");
            }
            if (!step.wallPos().equals(binding.wallPos())) {
                throw new IllegalArgumentException("binding wall position does not match its route region");
            }
        }
    }
}

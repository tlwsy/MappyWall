package dev.mappywall.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
                1
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
        validateDirectionStep(columnStepX, "columnStepX");
        validateDirectionStep(rowStepZ, "rowStepZ");
        Objects.requireNonNull(anchorMode, "anchorMode");
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
                ProjectStatus.RUNNING,
                Instant.now()
        );
    }

    public List<RouteStep> planRoute(MapWallProject project) {
        return planRoute(project, 1, 1);
    }

    public List<RouteStep> planRoute(MapWallProject project, int columnStepX, int rowStepZ) {
        Objects.requireNonNull(project, "project");
        validateDirectionStep(columnStepX, "columnStepX");
        validateDirectionStep(rowStepZ, "rowStepZ");
        List<RouteStep> steps = new ArrayList<>(project.width() * project.height());

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
        return createSave(project, 1, 1);
    }

    public MapWallSave createSave(MapWallProject project, int columnStepX, int rowStepZ) {
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
            if (!hasBinding(save, step.region().signature())) {
                return step;
            }
        }
        return null;
    }

    public MapWallSave bindCurrentStep(MapWallSave save, int mapId, Instant openedAt, BindingVerification verifiedBy) {
        RouteStep next = nextOpenStep(save);
        if (next == null) {
            return save.withProject(save.project().withStatus(ProjectStatus.COMPLETE));
        }

        List<MapBinding> bindings = new ArrayList<>(save.bindings());
        bindings.add(new MapBinding(next.wallPos(), next.region().signature(), mapId, openedAt, verifiedBy));

        List<RouteStep> route = new ArrayList<>(save.route());
        int nextIndex = route.indexOf(next);
        route.set(nextIndex, next.withState(RouteStepState.BOUND));

        int following = Math.min(nextIndex + 1, route.size());
        ProjectStatus status = following >= route.size() ? ProjectStatus.COMPLETE : save.project().status();
        RunSessionState session = save.session().withCurrentStep(following);
        return new MapWallSave(
                MapWallSave.CURRENT_SCHEMA_VERSION,
                save.project().withStatus(status),
                route,
                bindings,
                session
        );
    }

    private boolean hasBinding(MapWallSave save, String regionSignature) {
        for (MapBinding binding : save.bindings()) {
            if (binding.regionSignature().equals(regionSignature)) {
                return true;
            }
        }
        return false;
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

        int anchorGridX = reference.gridX() - Math.floorDiv(width, 2) * columnStepX;
        int anchorGridZ = reference.gridZ() - Math.floorDiv(height, 2) * rowStepZ;
        return MapRegionMath.regionForGrid(reference.dimension(), reference.scale(), anchorGridX, anchorGridZ);
    }

    private void validateDirectionStep(int step, String name) {
        if (step != 1 && step != -1) {
            throw new IllegalArgumentException(name + " must be 1 or -1");
        }
    }
}

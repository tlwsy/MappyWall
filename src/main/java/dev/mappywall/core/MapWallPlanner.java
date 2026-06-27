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
        MapRegion anchor = MapRegionMath.regionForBlock(dimension, scale, playerX, playerZ);
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
        Objects.requireNonNull(project, "project");
        List<RouteStep> steps = new ArrayList<>(project.width() * project.height());

        for (int row = 0; row < project.height(); row++) {
            boolean reverse = row % 2 == 1;
            for (int i = 0; i < project.width(); i++) {
                int column = reverse ? project.width() - 1 - i : i;
                MapRegion region = MapRegionMath.offset(project.anchorRegion(), column, row);
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
        List<RouteStep> route = planRoute(project);
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
}


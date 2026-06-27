package dev.mappywall.core;

import java.util.List;
import java.util.Objects;

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
        bindings = List.copyOf(bindings);
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
}


package dev.mappywall.core;

import java.time.Instant;
import java.util.Objects;

public record MapWallProject(
        String id,
        String serverKey,
        String dimension,
        int scale,
        int width,
        int height,
        MapRegion anchorRegion,
        RunMode mode,
        PostOpenMode postOpenMode,
        AutomationStyle automationStyle,
        ProjectStatus status,
        Instant createdAt,
        int columnStepX,
        int rowStepZ
) {
    public static final int MAX_MAP_COUNT = 4_096;

    public MapWallProject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(serverKey, "serverKey");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(anchorRegion, "anchorRegion");
        Objects.requireNonNull(mode, "mode");
        if (postOpenMode == null) {
            postOpenMode = PostOpenMode.OPEN_FIRST;
        }
        if (automationStyle == null) {
            automationStyle = AutomationStyle.NORMAL;
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        MapRegionMath.validateScale(scale);
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        validatePersistedDirection(columnStepX, "columnStepX");
        validatePersistedDirection(rowStepZ, "rowStepZ");
        checkedMapCount(width, height);
        if (!dimension.equals(anchorRegion.dimension()) || scale != anchorRegion.scale()) {
            throw new IllegalArgumentException("anchor region must use the project dimension and scale");
        }
    }

    public MapWallProject(
            String id,
            String serverKey,
            String dimension,
            int scale,
            int width,
            int height,
            MapRegion anchorRegion,
            RunMode mode,
            PostOpenMode postOpenMode,
            AutomationStyle automationStyle,
            ProjectStatus status,
            Instant createdAt
    ) {
        this(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                anchorRegion,
                mode,
                postOpenMode,
                automationStyle,
                status,
                createdAt,
                1,
                1
        );
    }

    public MapWallProject(
            String id,
            String serverKey,
            String dimension,
            int scale,
            int width,
            int height,
            MapRegion anchorRegion,
            RunMode mode,
            ProjectStatus status,
            Instant createdAt
    ) {
        this(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                anchorRegion,
                mode,
                PostOpenMode.OPEN_FIRST,
                AutomationStyle.NORMAL,
                status,
                createdAt,
                1,
                1
        );
    }

    public int mapCount() {
        return checkedMapCount(width, height);
    }

    public int effectiveColumnStepX() {
        return columnStepX == 0 ? 1 : columnStepX;
    }

    public int effectiveRowStepZ() {
        return rowStepZ == 0 ? 1 : rowStepZ;
    }

    public MapWallProject withDirections(int newColumnStepX, int newRowStepZ) {
        if (newColumnStepX == 0 || newRowStepZ == 0) {
            throw new IllegalArgumentException("resolved direction steps must be 1 or -1");
        }
        return new MapWallProject(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                anchorRegion,
                mode,
                postOpenMode,
                automationStyle,
                status,
                createdAt,
                newColumnStepX,
                newRowStepZ
        );
    }

    public MapWallProject withStatus(ProjectStatus newStatus) {
        return new MapWallProject(
                id,
                serverKey,
                dimension,
                scale,
                width,
                height,
                anchorRegion,
                mode,
                postOpenMode,
                automationStyle,
                newStatus,
                createdAt,
                columnStepX,
                rowStepZ
        );
    }

    private static int checkedMapCount(int width, int height) {
        long count = (long) width * height;
        if (count > MAX_MAP_COUNT) {
            throw new IllegalArgumentException("map wall may contain at most " + MAX_MAP_COUNT + " maps");
        }
        return (int) count;
    }

    private static void validatePersistedDirection(int step, String name) {
        // Zero is accepted only as the Gson default for schema-v1 saves written before
        // direction fields were persisted. MapWallSave resolves it from the stored route.
        if (step != -1 && step != 0 && step != 1) {
            throw new IllegalArgumentException(name + " must be -1, 0, or 1");
        }
    }
}

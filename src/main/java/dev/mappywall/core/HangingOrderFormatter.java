package dev.mappywall.core;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class HangingOrderFormatter {
    public List<String> format(MapWallSave save) {
        Map<WallPos, List<Integer>> mapIdsByWallPosition = new HashMap<>();
        for (MapBinding binding : save.finalBindings()) {
            mapIdsByWallPosition
                    .computeIfAbsent(binding.wallPos(), ignored -> new ArrayList<>())
                    .add(binding.mapId());
        }
        List<WallPos> wallPositions = mapIdsByWallPosition.keySet().stream()
                .sorted(Comparator.comparingInt(WallPos::row).thenComparingInt(WallPos::column))
                .toList();
        Map<WallPos, RouteStep> stepsByWallPosition = new HashMap<>();
        for (RouteStep step : save.route()) {
            stepsByWallPosition.put(step.wallPos(), step);
        }

        return java.util.stream.IntStream.range(0, wallPositions.size())
                .mapToObj(index -> {
                    WallPos wallPos = wallPositions.get(index);
                    RouteStep step = stepsByWallPosition.get(wallPos);
                    String target = step == null
                            ? "unknown region"
                            : "center " + step.region().centerX() + ", " + step.region().centerZ();
                    String mapIds = mapIdsByWallPosition.get(wallPos).stream()
                            .sorted()
                            .map(String::valueOf)
                            .collect(Collectors.joining("/"));
                    return (index + 1)
                            + ". row " + (wallPos.row() + 1)
                            + ", column " + (wallPos.column() + 1)
                            + " -> map #" + mapIds
                            + " (" + target + ")";
                })
                .toList();
    }
}

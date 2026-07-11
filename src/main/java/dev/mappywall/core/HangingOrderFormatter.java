package dev.mappywall.core;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HangingOrderFormatter {
    public List<String> format(MapWallSave save) {
        List<MapBinding> bindings = save.bindings().stream()
                .sorted(Comparator
                        .comparingInt((MapBinding binding) -> binding.wallPos().row())
                        .thenComparingInt(binding -> binding.wallPos().column()))
                .toList();
        Map<WallPos, RouteStep> stepsByWallPosition = new HashMap<>();
        for (RouteStep step : save.route()) {
            stepsByWallPosition.put(step.wallPos(), step);
        }

        return java.util.stream.IntStream.range(0, bindings.size())
                .mapToObj(index -> {
                    MapBinding binding = bindings.get(index);
                    RouteStep step = stepsByWallPosition.get(binding.wallPos());
                    String target = step == null
                            ? binding.regionSignature()
                            : "center " + step.region().centerX() + ", " + step.region().centerZ();
                    return (index + 1)
                            + ". row " + (binding.wallPos().row() + 1)
                            + ", column " + (binding.wallPos().column() + 1)
                            + " -> map #" + binding.mapId()
                            + " (" + target + ")";
                })
                .toList();
    }
}

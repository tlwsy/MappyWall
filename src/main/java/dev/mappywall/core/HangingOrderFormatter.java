package dev.mappywall.core;

import java.util.Comparator;
import java.util.List;

public final class HangingOrderFormatter {
    public List<String> format(MapWallSave save) {
        return save.bindings().stream()
                .sorted(Comparator
                        .comparingInt((MapBinding binding) -> binding.wallPos().row())
                        .thenComparingInt(binding -> binding.wallPos().column()))
                .map(binding -> {
                    RouteStep step = findStep(save, binding.wallPos());
                    String target = step == null
                            ? binding.regionSignature()
                            : "center " + step.region().centerX() + ", " + step.region().centerZ();
                    return "row " + binding.wallPos().row()
                            + ", column " + binding.wallPos().column()
                            + " -> map #" + binding.mapId()
                            + " (" + target + ")";
                })
                .toList();
    }

    private RouteStep findStep(MapWallSave save, WallPos wallPos) {
        for (RouteStep step : save.route()) {
            if (step.wallPos().equals(wallPos)) {
                return step;
            }
        }
        return null;
    }
}

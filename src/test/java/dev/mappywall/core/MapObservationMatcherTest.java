package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MapObservationMatcherTest {
    @Test
    void boundMapIdMakesClientPlaceholderUsableWithoutTrustingPlaceholderCenter() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "bound-placeholder", "local", "minecraft:overworld", 0, 1, 1, 1024, 1024, RunMode.AUTO_WALK
        );
        MapWallSave save = planner.bindCurrentStep(
                planner.createSave(project), 31, Instant.EPOCH, BindingVerification.MAP_STATE
        );
        RouteStep target = save.route().getFirst();
        ObservedMap placeholder = new ObservedMap(
                31, "minecraft:overworld", 0, 0, 0, 0.5, false
        );

        assertTrue(new MapObservationMatcher().matchesBoundRegion(save, target, placeholder));
        assertTrue(new MapObservationMatcher().isExactTargetScale(save, target, placeholder));
    }

    @Test
    void unboundClientPlaceholderCannotClaimTargetRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "unbound-placeholder", "local", "minecraft:overworld", 0, 1, 1, 1024, 1024, RunMode.AUTO_WALK
        );
        MapWallSave save = planner.createSave(project);
        RouteStep target = save.route().getFirst();
        ObservedMap placeholder = new ObservedMap(
                31, "minecraft:overworld", 0, 0, 0, 0.5, false
        );

        assertFalse(new MapObservationMatcher().matchesBoundRegion(save, target, placeholder));
    }

    @Test
    void reliableWrongRegionStillOverridesBindingLineage() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "reliable-mismatch", "local", "minecraft:overworld", 0, 1, 1, 1024, 1024, RunMode.AUTO_WALK
        );
        MapWallSave save = planner.bindCurrentStep(
                planner.createSave(project), 31, Instant.EPOCH, BindingVerification.MAP_STATE
        );
        RouteStep target = save.route().getFirst();
        MapRegion wrong = MapRegionMath.offset(target.region(), 1, 0);
        ObservedMap reliableWrong = new ObservedMap(
                31, wrong.dimension(), wrong.scale(), wrong.centerX(), wrong.centerZ()
        );

        assertFalse(new MapObservationMatcher().matchesBoundRegion(save, target, reliableWrong));
    }
}

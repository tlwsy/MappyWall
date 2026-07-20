# Confirmed Water Transit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Acquire and use a boat only when the accepted route proves at least 12 blocks of continuous boatable water, while reliably falling back to uninterrupted swimming and supporting submerged `SWIM` waypoints.

**Architecture:** A pure `WaterTransitPolicy` owns completed-distance accumulation and the per-water-run eligibility latch. A second pure `BoatAcquisitionPolicy` owns bounded inventory, placement, entity-confirmation, and boarding phases. A client-thread `WaterRouteEvidenceAdapter` converts accepted `PathStep` data into resolved water-surface evidence; `MovementController` samples Minecraft state and executes decisions without moving world reads to the path worker.

**Tech Stack:** Java 25, Minecraft 26.2 official mappings, Fabric Loom 1.17.13, Gson 2.13.2, JUnit 5.14, Gradle Wrapper.

## Global Constraints

- The mod remains client-only and compatible with vanilla servers; add no custom packet, server entity, item, block, or server dependency.
- The default minimum confirmed crossing is exactly 12 blocks; persisted values are integers in the inclusive range 1 through 128.
- The 12-block threshold controls both boarding a nearby empty boat and placing a carried boat.
- Count only active accepted steps plus an accepted `CONTINUOUS` buffer; never count pending, stale, hidden-retreat, unloaded, obstructed, or guessed terrain.
- Preserve completed confirmed water distance across an ordinary compatible same-target replan; reset on a disconnected/non-water run, automation-style change, target/world change, pause, hard reset, or terminal transition.
- A mounted boat follows submerged `SWIM` waypoints through their resolved surface and must neither dismount nor fail waypoint completion solely because the planner cell is underwater.
- `InteractionResult.consumesAction()` is only client prediction; a placed boat is confirmed only by a new route-adjacent entity ID absent from the pre-placement baseline, and boarding is confirmed only by passenger state.
- Short crossings continue swimming even when a nearby empty boat exists.
- Opening a GUI may defer inventory/world transactions but must not stop aggressive swimming.
- Do not increase walking, sprinting, swimming, boat, jump, placement, breaking, or packet-rate constants.
- Use `apply_patch` for edits and `E:\MappyWall\.gradle-user-home` for every Gradle command.

---

## File map

- Create `src/main/java/dev/mappywall/core/WaterTransitPolicy.java`: pure water-run distance, surface identity, threshold, and eligibility latch.
- Create `src/main/java/dev/mappywall/core/BoatAcquisitionPolicy.java`: pure bounded selection/placement/entity/boarding state machine.
- Create `src/client/java/dev/mappywall/client/WaterRouteEvidenceAdapter.java`: client-thread submerged-waypoint surface resolution and accepted-path evidence collection.
- Create `src/client/java/dev/mappywall/client/NavigationSettingsInput.java`: pure numeric threshold parser for the GUI.
- Create `src/test/java/dev/mappywall/core/WaterTransitPolicyTest.java`.
- Create `src/test/java/dev/mappywall/core/BoatAcquisitionPolicyTest.java`.
- Create `src/test/java/dev/mappywall/client/WaterRouteEvidenceAdapterTest.java`.
- Create `src/test/java/dev/mappywall/client/NavigationConfigStoreTest.java`.
- Create `src/test/java/dev/mappywall/client/NavigationSettingsInputTest.java`.
- Create `src/test/java/dev/mappywall/client/MovementControllerWaterTransitTest.java`.
- Modify `src/client/java/dev/mappywall/client/AutoNavigationConfig.java`: threshold field and defensive normalization.
- Modify `src/client/java/dev/mappywall/client/NavigationConfigStore.java`: old-file migration, injectable path, atomic settings save/reset.
- Modify `src/client/java/dev/mappywall/client/LocalPathPlanner.java`: retain the threshold when cloning a non-modifying config.
- Modify `src/client/java/dev/mappywall/client/MovementController.java`: evidence tracking, deep-water completion/dismount, and acquisition execution.
- Modify `src/client/java/dev/mappywall/client/MappyWallRuntime.java`: persist and immediately apply the full navigation settings.
- Modify `src/client/java/dev/mappywall/client/NavigationSettingsScreen.java`: numeric 1-128 field.
- Modify `src/test/java/dev/mappywall/client/MovementControllerDismountTest.java`: update deep-water dismount source contracts.
- Modify `src/client/resources/assets/mappywall/lang/en_us.json` and `zh_cn.json`: generalized navigation labels and boat-distance text.
- Modify `docs/design.md`: document confirmed long-water acquisition and fallback semantics.

### Task 1: Model confirmed water-run distance and eligibility

**Files:**
- Create: `src/test/java/dev/mappywall/core/WaterTransitPolicyTest.java`
- Create: `src/main/java/dev/mappywall/core/WaterTransitPolicy.java`

**Interfaces:**
- Consumes: a resolved surface Y, newly completed accepted-edge distances, currently confirmed future distance, configured minimum, and riding state.
- Produces: `WaterTransitPolicy.TravelDecision`, `observe(RunObservation)`, `recordCompletedEdge(int, double)`, `leaveWaterRun()`, `reset()`, and read-only state accessors.

- [ ] **Step 1: Write the failing water-run policy tests**

Create `WaterTransitPolicyTest` with these cases:

```java
package dev.mappywall.core;

import static dev.mappywall.core.WaterTransitPolicy.TravelDecision.ACQUIRE_BOAT;
import static dev.mappywall.core.WaterTransitPolicy.TravelDecision.CONTINUE_RIDING;
import static dev.mappywall.core.WaterTransitPolicy.TravelDecision.SWIM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class WaterTransitPolicyTest {
    @Test
    void requiresTwelveConfirmedBlocksAtTheDefaultBoundary() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(SWIM, policy.observe(run(64, 11.999, false)));
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 12.0, false)));
    }

    @Test
    void completedPrefixCombinesWithLaterRollingEvidenceExactlyOnce() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(SWIM, policy.observe(run(64, 8.0, false)));
        for (int edge = 0; edge < 4; edge++) {
            policy.recordCompletedEdge(64, 1.0);
        }
        assertEquals(4.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 8.0, false)));
        assertEquals(4.0, policy.completedDistanceBlocks(), 1.0e-9);
    }

    @Test
    void diagonalDistanceUsesGeometricLength() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.observe(run(64, 0.0, false));
        policy.recordCompletedEdge(64, Math.sqrt(2.0));
        assertEquals(Math.sqrt(2.0), policy.completedDistanceBlocks(), 1.0e-9);
    }

    @Test
    void unobservedOrDifferentSurfaceDoesNotCountTheBoundaryEdge() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.recordCompletedEdge(64, 1.0);
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
        policy.observe(run(64, 0.0, false));
        policy.recordCompletedEdge(64, 1.0);
        policy.recordCompletedEdge(65, 1.0);
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertEquals(65, policy.surfaceY().orElseThrow());
    }

    @Test
    void eligibilityLatchesUntilTheWaterRunEnds() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 12.0, false)));
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 1.0, false)));
        assertTrue(policy.acquisitionLatched());
        policy.leaveWaterRun();
        assertFalse(policy.acquisitionLatched());
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
    }

    @Test
    void compatiblePlanningGapDoesNotEraseCompletedDistance() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.observe(run(64, 4.0, false));
        policy.recordCompletedEdge(64, 3.0);
        // A same-target planning gap makes no policy call.
        assertEquals(3.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertEquals(SWIM, policy.observe(run(64, 8.0, false)));
    }

    @Test
    void incompatibleSurfaceStartsASeparateRun() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.observe(run(64, 12.0, false));
        policy.recordCompletedEdge(64, 2.0);
        assertEquals(SWIM, policy.observe(run(65, 3.0, false)));
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertFalse(policy.acquisitionLatched());
    }

    @Test
    void ridingBoatContinuesWithoutReapplyingTheAcquisitionThreshold() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(CONTINUE_RIDING, policy.observe(run(64, 1.0, true)));
    }

    @Test
    void observationsRejectInvalidNumbersAndThresholds() {
        assertThrows(IllegalArgumentException.class, () -> run(64, Double.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> new WaterTransitPolicy.RunObservation(
                OptionalInt.of(64), 1.0, 0, false));
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertThrows(IllegalArgumentException.class, () -> policy.recordCompletedEdge(64, -1.0));
    }

    private static WaterTransitPolicy.RunObservation run(int surfaceY, double future, boolean riding) {
        return new WaterTransitPolicy.RunObservation(
                OptionalInt.of(surfaceY),
                future,
                WaterTransitPolicy.DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS,
                riding
        );
    }
}
```

- [ ] **Step 2: Run the policy test and confirm RED**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.core.WaterTransitPolicyTest" --rerun-tasks
```

Expected: compilation fails because `WaterTransitPolicy` does not exist.

- [ ] **Step 3: Implement the complete pure water-run policy**

Create `WaterTransitPolicy.java`:

```java
package dev.mappywall.core;

import java.util.Objects;
import java.util.OptionalInt;

public final class WaterTransitPolicy {
    public static final int DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS = 12;
    public static final int MINIMUM_BOAT_DISTANCE_BLOCKS = 1;
    public static final int MAXIMUM_BOAT_DISTANCE_BLOCKS = 128;
    private static final double DISTANCE_EPSILON = 1.0e-9;

    public enum TravelDecision {
        SWIM,
        ACQUIRE_BOAT,
        CONTINUE_RIDING
    }

    public record RunObservation(
            OptionalInt surfaceY,
            double futureConfirmedDistanceBlocks,
            int minimumBoatDistanceBlocks,
            boolean ridingBoat
    ) {
        public RunObservation {
            Objects.requireNonNull(surfaceY, "surfaceY");
            if (!Double.isFinite(futureConfirmedDistanceBlocks)
                    || futureConfirmedDistanceBlocks < 0.0) {
                throw new IllegalArgumentException("future distance must be finite and non-negative");
            }
            if (minimumBoatDistanceBlocks < MINIMUM_BOAT_DISTANCE_BLOCKS
                    || minimumBoatDistanceBlocks > MAXIMUM_BOAT_DISTANCE_BLOCKS) {
                throw new IllegalArgumentException("minimum boat distance is out of range");
            }
        }
    }

    private OptionalInt surfaceY = OptionalInt.empty();
    private double completedDistanceBlocks;
    private boolean acquisitionLatched;

    public TravelDecision observe(RunObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (observation.surfaceY().isEmpty()) {
            leaveWaterRun();
            return TravelDecision.SWIM;
        }
        int observedY = observation.surfaceY().getAsInt();
        if (surfaceY.isEmpty() || surfaceY.getAsInt() != observedY) {
            beginRun(observedY);
        }
        if (completedDistanceBlocks + observation.futureConfirmedDistanceBlocks()
                + DISTANCE_EPSILON >= observation.minimumBoatDistanceBlocks()) {
            acquisitionLatched = true;
        }
        if (observation.ridingBoat()) {
            return TravelDecision.CONTINUE_RIDING;
        }
        return acquisitionLatched ? TravelDecision.ACQUIRE_BOAT : TravelDecision.SWIM;
    }

    public void recordCompletedEdge(int edgeSurfaceY, double distanceBlocks) {
        if (!Double.isFinite(distanceBlocks) || distanceBlocks < 0.0) {
            throw new IllegalArgumentException("completed distance must be finite and non-negative");
        }
        if (surfaceY.isEmpty() || surfaceY.getAsInt() != edgeSurfaceY) {
            beginRun(edgeSurfaceY);
            return;
        }
        completedDistanceBlocks += distanceBlocks;
    }

    public void leaveWaterRun() {
        surfaceY = OptionalInt.empty();
        completedDistanceBlocks = 0.0;
        acquisitionLatched = false;
    }

    public void reset() {
        leaveWaterRun();
    }

    public OptionalInt surfaceY() {
        return surfaceY;
    }

    public double completedDistanceBlocks() {
        return completedDistanceBlocks;
    }

    public boolean acquisitionLatched() {
        return acquisitionLatched;
    }

    private void beginRun(int observedY) {
        surfaceY = OptionalInt.of(observedY);
        completedDistanceBlocks = 0.0;
        acquisitionLatched = false;
    }
}
```

- [ ] **Step 4: Run the focused test and confirm GREEN**

Run the Step 2 command again.

Expected: all `WaterTransitPolicyTest` cases pass.

- [ ] **Step 5: Commit the policy**

```powershell
git add -- src/main/java/dev/mappywall/core/WaterTransitPolicy.java src/test/java/dev/mappywall/core/WaterTransitPolicyTest.java
git commit -m "Model confirmed water-run distance"
```

### Task 2: Persist and edit the 12-block threshold safely

**Files:**
- Modify: `src/client/java/dev/mappywall/client/AutoNavigationConfig.java`
- Modify: `src/client/java/dev/mappywall/client/NavigationConfigStore.java`
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`
- Create: `src/test/java/dev/mappywall/client/NavigationConfigStoreTest.java`

**Interfaces:**
- Consumes: `WaterTransitPolicy` range/default constants from Task 1.
- Produces: `AutoNavigationConfig.minimumBoatDistanceBlocks()`, `NavigationConfigStore(Path)`, `updateNavigationSettings(...)`, and `resetNavigationDefaults()`.

- [ ] **Step 1: Write failing migration, normalization, round-trip, and reset tests**

Create `NavigationConfigStoreTest` with the complete legacy fixture and assertions below:

```java
package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NavigationConfigStoreTest {
    @TempDir
    Path directory;

    private int fixtureIndex;

    @Test
    void legacyConfigKeepsEveryOldFieldAndAddsTwelve() throws IOException {
        AutoNavigationConfig loaded = new NavigationConfigStore(writeConfig(null)).aggressiveConfig();
        assertLegacyFields(loaded);
        assertEquals(12, loaded.minimumBoatDistanceBlocks());
    }

    @Test
    void malformedBoatDistanceUsesTwelveWithoutDiscardingOtherFields() throws IOException {
        AutoNavigationConfig loaded = new NavigationConfigStore(writeConfig(
                "\"minimumBoatDistanceBlocks\": \"bad\""
        )).aggressiveConfig();
        assertLegacyFields(loaded);
        assertEquals(12, loaded.minimumBoatDistanceBlocks());
    }

    @Test
    void numericBoatDistanceIsClampedToTheSupportedRange() throws IOException {
        assertEquals(1, loadDistance("0"));
        assertEquals(1, loadDistance("-7"));
        assertEquals(128, loadDistance("129"));
        assertEquals(12, loadDistance("12.5"));
    }

    @Test
    void customDistanceRoundTripsAndResetRestoresTwelve() throws IOException {
        Path path = directory.resolve("navigation.json");
        NavigationConfigStore store = new NavigationConfigStore(path);
        AutoNavigationConfig current = store.aggressiveConfig();
        store.updateNavigationSettings(
                current.blockBreakingEnabled(), current.breakListMode(), current.breakBlocks(), 37);
        assertEquals(37, new NavigationConfigStore(path).aggressiveConfig()
                .minimumBoatDistanceBlocks());
        store.resetNavigationDefaults();
        assertEquals(AutoNavigationConfig.aggressiveDefaults(), store.aggressiveConfig());
    }

    private int loadDistance(String rawJsonNumber) throws IOException {
        return new NavigationConfigStore(writeConfig(
                "\"minimumBoatDistanceBlocks\": " + rawJsonNumber
        )).aggressiveConfig().minimumBoatDistanceBlocks();
    }

    private Path writeConfig(String extraProperty) throws IOException {
        Path path = directory.resolve("legacy-" + fixtureIndex++ + ".json");
        String suffix = extraProperty == null ? "" : ",\n  " + extraProperty;
        Files.writeString(path, ("""
                {
                  "blockBreakingEnabled": true,
                  "breakListMode": "BLACKLIST",
                  "breakBlocks": ["minecraft:dirt"],
                  "blockPlacingEnabled": true,
                  "placeListMode": "WHITELIST",
                  "placeBlocks": ["minecraft:cobblestone"],
                  "eatingEnabled": false,
                  "foodListMode": "WHITELIST",
                  "foods": ["minecraft:bread"],
                  "eatAtFoodLevel": 7%s
                }
                """).formatted(suffix));
        return path;
    }

    private static void assertLegacyFields(AutoNavigationConfig loaded) {
        assertTrue(loaded.blockBreakingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.BLACKLIST, loaded.breakListMode());
        assertEquals(Set.of("minecraft:dirt"), loaded.breakBlocks());
        assertTrue(loaded.blockPlacingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.WHITELIST, loaded.placeListMode());
        assertEquals(Set.of("minecraft:cobblestone"), loaded.placeBlocks());
        assertFalse(loaded.eatingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.WHITELIST, loaded.foodListMode());
        assertEquals(Set.of("minecraft:bread"), loaded.foods());
        assertEquals(7, loaded.eatAtFoodLevel());
    }
}
```

- [ ] **Step 2: Run the config test and confirm RED**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationConfigStoreTest" --rerun-tasks
```

Expected: compilation fails because the threshold component, injectable constructor, and full-settings methods do not exist.

- [ ] **Step 3: Add the record component and preserve it at every constructor site**

Append `int minimumBoatDistanceBlocks` to `AutoNavigationConfig`. In its compact constructor normalize with:

```java
minimumBoatDistanceBlocks = Math.max(
        WaterTransitPolicy.MINIMUM_BOAT_DISTANCE_BLOCKS,
        Math.min(WaterTransitPolicy.MAXIMUM_BOAT_DISTANCE_BLOCKS, minimumBoatDistanceBlocks)
);
```

Import `dev.mappywall.core.WaterTransitPolicy`. Pass `DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS` from `defaults()`, copy `safe.minimumBoatDistanceBlocks()` from `aggressiveDefaults()`, and copy `config.minimumBoatDistanceBlocks()` in `LocalPathPlanner.planSinglePass()` when constructing the non-modifying configuration.

- [ ] **Step 4: Make old JSON migration explicit before Gson record construction**

Add a package-visible path constructor and make the production constructor delegate:

```java
NavigationConfigStore() {
    this(FabricLoader.getInstance().getConfigDir().resolve("mappywall").resolve("navigation.json"));
}

NavigationConfigStore(Path path) {
    this.path = Objects.requireNonNull(path, "path");
    this.aggressiveConfig = loadOrDefault();
}
```

Import `dev.mappywall.core.WaterTransitPolicy`, `JsonElement`, `JsonObject`, `JsonParser`, `BigDecimal`, and `Objects`. Replace direct `GSON.fromJson(reader, AutoNavigationConfig.class)` with:

```java
JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
object.addProperty("minimumBoatDistanceBlocks", normalizedBoatDistance(object.get(
        "minimumBoatDistanceBlocks")));
AutoNavigationConfig loaded = GSON.fromJson(object, AutoNavigationConfig.class);
return loaded == null ? defaults : loaded;
```

Add:

```java
private int normalizedBoatDistance(JsonElement element) {
    int fallback = WaterTransitPolicy.DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS;
    if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
        return fallback;
    }
    try {
        BigDecimal decimal = element.getAsBigDecimal();
        int value = decimal.intValueExact();
        return Math.max(
                WaterTransitPolicy.MINIMUM_BOAT_DISTANCE_BLOCKS,
                Math.min(WaterTransitPolicy.MAXIMUM_BOAT_DISTANCE_BLOCKS, value)
        );
    } catch (ArithmeticException | NumberFormatException invalid) {
        return fallback;
    }
}
```

- [ ] **Step 5: Save all visible settings atomically without losing hidden fields**

Implement:

```java
void updateNavigationSettings(
        boolean breakingEnabled,
        AutoNavigationConfig.ListMode mode,
        Set<String> blockIds,
        int minimumBoatDistanceBlocks
) throws IOException {
    AutoNavigationConfig current = aggressiveConfig;
    AutoNavigationConfig candidate = new AutoNavigationConfig(
            breakingEnabled,
            mode,
            blockIds,
            current.blockPlacingEnabled(),
            current.placeListMode(),
            current.placeBlocks(),
            current.eatingEnabled(),
            current.foodListMode(),
            current.foods(),
            current.eatAtFoodLevel(),
            minimumBoatDistanceBlocks
    );
    save(candidate);
    aggressiveConfig = candidate;
}

void resetNavigationDefaults() throws IOException {
    AutoNavigationConfig defaults = AutoNavigationConfig.aggressiveDefaults();
    save(defaults);
    aggressiveConfig = defaults;
}
```

Keep existing callers compiling by making `updateBreaking(...)` delegate with the current threshold and `resetBreakingDefaults()` delegate to `resetNavigationDefaults()` until Task 7 updates the runtime/UI names.

- [ ] **Step 6: Run focused config and planner tests**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationConfigStoreTest" --tests "dev.mappywall.client.LocalPathPlannerTest" --rerun-tasks
```

Expected: both classes pass; no `AutoNavigationConfig` constructor site fails compilation.

- [ ] **Step 7: Commit configuration support**

```powershell
git add -- src/client/java/dev/mappywall/client/AutoNavigationConfig.java src/client/java/dev/mappywall/client/NavigationConfigStore.java src/client/java/dev/mappywall/client/LocalPathPlanner.java src/test/java/dev/mappywall/client/NavigationConfigStoreTest.java
git commit -m "Persist minimum boat crossing distance"
```

### Task 3: Resolve submerged path cells to accepted boatable surfaces

**Files:**
- Create: `src/client/java/dev/mappywall/client/WaterRouteEvidenceAdapter.java`
- Create: `src/test/java/dev/mappywall/client/WaterRouteEvidenceAdapterTest.java`

**Interfaces:**
- Consumes: current water-run anchor, `List<LocalPathPlanner.PathStep>` from `previewStepSnapshot()`, a proof limit, and live client world data.
- Produces: `ResolvedSurface`, `Evidence`, `resolveBoatableSurface(...)`, and `collect(...)`.

- [ ] **Step 1: Write failing resolver and evidence tests**

Cover these exact cases in `WaterRouteEvidenceAdapterTest`:

```java
@Test
void submergedWaypointResolvesToTopOfLoadedWaterColumn() {
    BlockPos waypoint = new BlockPos(2, 60, 3);
    Set<BlockPos> water = Set.of(waypoint, waypoint.above(), waypoint.above(2));
    Optional<WaterRouteEvidenceAdapter.ResolvedSurface> resolved =
            WaterRouteEvidenceAdapter.resolveBoatableSurface(
                    waypoint,
                    80,
                    probe(water, Set.of(), Set.of())
            );
    assertEquals(waypoint.above(2), resolved.orElseThrow().waterPos());
    assertEquals(waypoint, resolved.orElseThrow().routePos());
}

@Test
void unloadedOrCoveredSurfaceIsRejected() {
    BlockPos waypoint = new BlockPos(2, 60, 3);
    assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
            waypoint, 80, probe(Set.of(waypoint), Set.of(waypoint), Set.of())).isEmpty());
    assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
            waypoint, 80, probe(Set.of(waypoint), Set.of(), Set.of(waypoint))).isEmpty());
}

@Test
void nonWaterAndBoatEnvelopeObstructionAreRejected() {
    BlockPos waypoint = new BlockPos(2, 60, 3);
    assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
            waypoint, 80, probe(Set.of(), Set.of(), Set.of())).isEmpty());
    assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
            waypoint, 80, probe(Set.of(waypoint), Set.of(waypoint), Set.of())).isEmpty());

    AABB envelope = WaterRouteEvidenceAdapter.boatEnvelope(waypoint);
    assertTrue(envelope.minX < waypoint.getX());
    assertTrue(envelope.maxX > waypoint.getX() + 1.0);
    assertTrue(envelope.minZ < waypoint.getZ());
    assertTrue(envelope.maxZ > waypoint.getZ() + 1.0);
    assertEquals(waypoint.getY() + 1.0, envelope.minY, 1.0e-9);
}

@Test
void liveEnvelopeProofIgnoresEntitiesButStillRejectsBlocks() throws IOException {
    String source = Files.readString(Path.of(
            "src", "client", "java", "dev", "mappywall", "client",
            "WaterRouteEvidenceAdapter.java"));
    String compact = source.replaceAll("\\s+", "");
    assertTrue(compact.contains("client.level.noBlockCollision(null,envelope)"));
    assertFalse(compact.contains("client.level.noCollision(null,envelope)"));
}

@Test
void acceptedContinuousStepsProveCardinalAndDiagonalDistance() {
    List<LocalPathPlanner.PathStep> steps = List.of(
            swim(1, 62, 0), swim(2, 62, 0), swim(3, 62, 1));
    WaterRouteEvidenceAdapter.Evidence evidence = WaterRouteEvidenceAdapter.collect(
            new BlockPos(0, 64, 0),
            steps,
            12.0,
            step -> Optional.of(surface(step, 62))
    );
    assertEquals(2.0 + Math.sqrt(2.0), evidence.futureConfirmedDistanceBlocks(), 1.0e-9);
    assertEquals(62, evidence.surfaceY().orElseThrow());
}

@Test
void nonSwimMissingAndDifferentHeightSurfacesTerminateEvidence() {
    BlockPos anchor = new BlockPos(0, 64, 0);
    List<LocalPathPlanner.PathStep> withWalk = List.of(
            swim(1, 62, 0), walk(2, 62, 0), swim(3, 62, 0));
    WaterRouteEvidenceAdapter.Evidence stoppedAtWalk = WaterRouteEvidenceAdapter.collect(
            anchor, withWalk, 12.0, step -> Optional.of(surface(step, 62)));
    assertEquals(1.0, stoppedAtWalk.futureConfirmedDistanceBlocks(), 1.0e-9);
    assertEquals(1, stoppedAtWalk.surfaces().size());

    List<LocalPathPlanner.PathStep> twoSwims = List.of(swim(1, 62, 0), swim(2, 62, 0));
    WaterRouteEvidenceAdapter.Evidence stoppedAtMissing = WaterRouteEvidenceAdapter.collect(
            anchor,
            twoSwims,
            12.0,
            step -> step.pos().getX() == 1
                    ? Optional.of(surface(step, 62))
                    : Optional.empty()
    );
    assertEquals(1.0, stoppedAtMissing.futureConfirmedDistanceBlocks(), 1.0e-9);
    assertEquals(1, stoppedAtMissing.surfaces().size());

    WaterRouteEvidenceAdapter.Evidence stoppedAtHeightChange = WaterRouteEvidenceAdapter.collect(
            anchor,
            twoSwims,
            12.0,
            step -> Optional.of(surface(step, step.pos().getX() == 1 ? 62 : 63))
    );
    assertEquals(1.0, stoppedAtHeightChange.futureConfirmedDistanceBlocks(), 1.0e-9);
    assertEquals(62, stoppedAtHeightChange.surfaceY().orElseThrow());
    assertEquals(1, stoppedAtHeightChange.surfaces().size());
}

@Test
void proofLimitStopsLookupWithoutReadingTheUnneededSuffix() {
    List<LocalPathPlanner.PathStep> steps = IntStream.rangeClosed(1, 12)
            .mapToObj(x -> swim(x, 62, 0))
            .toList();
    AtomicInteger lookups = new AtomicInteger();
    WaterRouteEvidenceAdapter.Evidence evidence = WaterRouteEvidenceAdapter.collect(
            new BlockPos(0, 64, 0),
            steps,
            4.0,
            step -> {
                lookups.incrementAndGet();
                return Optional.of(surface(step, 62));
            }
    );
    assertEquals(4.0, evidence.futureConfirmedDistanceBlocks(), 1.0e-9);
    assertEquals(4, evidence.surfaces().size());
    assertEquals(4, lookups.get());
}

@Test
void activeAndAcceptedContinuousStepsFormOneEvidenceSequence() {
    List<LocalPathPlanner.PathStep> active = List.of(swim(1, 62, 0), swim(2, 62, 0));
    List<LocalPathPlanner.PathStep> continuous = List.of(swim(3, 62, 0), swim(4, 62, 0));
    ArrayList<LocalPathPlanner.PathStep> preview = new ArrayList<>(active);
    preview.addAll(continuous);
    WaterRouteEvidenceAdapter.Evidence evidence = WaterRouteEvidenceAdapter.collect(
            new BlockPos(0, 64, 0),
            preview,
            12.0,
            step -> Optional.of(surface(step, 62))
    );
    assertEquals(4.0, evidence.futureConfirmedDistanceBlocks(), 1.0e-9);
    assertEquals(4, evidence.surfaces().size());
}

private static LocalPathPlanner.PathStep swim(int x, int y, int z) {
    return new LocalPathPlanner.PathStep(
            new BlockPos(x, y, z), LocalPathPlanner.StepAction.SWIM, null);
}

private static LocalPathPlanner.PathStep walk(int x, int y, int z) {
    return new LocalPathPlanner.PathStep(
            new BlockPos(x, y, z), LocalPathPlanner.StepAction.WALK, null);
}

private static WaterRouteEvidenceAdapter.ResolvedSurface surface(
        LocalPathPlanner.PathStep step,
        int surfaceY
) {
    return new WaterRouteEvidenceAdapter.ResolvedSurface(
            step.pos(),
            new BlockPos(step.pos().getX(), surfaceY, step.pos().getZ())
    );
}

private static WaterRouteEvidenceAdapter.SurfaceProbe probe(
        Set<BlockPos> water,
        Set<BlockPos> blockedEnvelopes,
        Set<BlockPos> unloaded
) {
    return new WaterRouteEvidenceAdapter.SurfaceProbe() {
        @Override
        public boolean loaded(BlockPos pos) {
            return !unloaded.contains(pos);
        }

        @Override
        public boolean water(BlockPos pos) {
            return water.contains(pos);
        }

        @Override
        public boolean boatEnvelopeClear(BlockPos waterSurface) {
            return !blockedEnvelopes.contains(waterSurface);
        }
    };
}
```

Use imports for `IOException`, `Files`, `Path`, `ArrayList`, `List`, `Optional`, `Set`, `AtomicInteger`, `IntStream`, and `AABB`, plus the JUnit assertions used above, including `assertFalse`.

- [ ] **Step 2: Run the adapter test and confirm RED**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.WaterRouteEvidenceAdapterTest" --rerun-tasks
```

Expected: compilation fails because `WaterRouteEvidenceAdapter` does not exist.

- [ ] **Step 3: Implement the pure probe resolver and path collector**

Create package-visible `final class WaterRouteEvidenceAdapter` in package `dev.mappywall.client`, importing `ArrayList`, `List`, `Objects`, `Optional`, and `OptionalInt`. Create these package-visible nested types in it:

```java
record ResolvedSurface(BlockPos routePos, BlockPos waterPos) {}

record Evidence(
        OptionalInt surfaceY,
        double futureConfirmedDistanceBlocks,
        List<ResolvedSurface> surfaces
) {
    Evidence {
        Objects.requireNonNull(surfaceY, "surfaceY");
        surfaces = List.copyOf(surfaces);
    }

    static Evidence none() {
        return new Evidence(OptionalInt.empty(), 0.0, List.of());
    }
}

interface SurfaceProbe {
    boolean loaded(BlockPos pos);
    boolean water(BlockPos pos);
    boolean boatEnvelopeClear(BlockPos waterSurface);
}

@FunctionalInterface
interface SurfaceLookup {
    Optional<ResolvedSurface> resolve(LocalPathPlanner.PathStep step);
}
```

Implement `resolveBoatableSurface(BlockPos waypoint, int maxY, SurfaceProbe probe)` exactly as follows so unloaded boundaries are never inferred:

```java
static Optional<ResolvedSurface> resolveBoatableSurface(
        BlockPos waypoint,
        int maxY,
        SurfaceProbe probe
) {
    Objects.requireNonNull(waypoint, "waypoint");
    Objects.requireNonNull(probe, "probe");
    if (!probe.loaded(waypoint) || !probe.water(waypoint)) {
        return Optional.empty();
    }
    BlockPos surface = waypoint;
    while (surface.getY() < maxY) {
        BlockPos above = surface.above();
        if (!probe.loaded(above)) {
            return Optional.empty();
        }
        if (!probe.water(above)) {
            return probe.boatEnvelopeClear(surface)
                    ? Optional.of(new ResolvedSurface(waypoint, surface))
                    : Optional.empty();
        }
        surface = above;
    }
    return Optional.empty();
}
```

Implement `collect(...)` with this exact loop behavior:

```java
static Evidence collect(
        BlockPos anchor,
        List<LocalPathPlanner.PathStep> acceptedSteps,
        double proofLimit,
        SurfaceLookup lookup
) {
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(acceptedSteps, "acceptedSteps");
    Objects.requireNonNull(lookup, "lookup");
    if (!Double.isFinite(proofLimit) || proofLimit <= 0.0) {
        throw new IllegalArgumentException("proofLimit must be finite and positive");
    }

    ArrayList<ResolvedSurface> surfaces = new ArrayList<>();
    OptionalInt surfaceY = OptionalInt.empty();
    BlockPos previous = anchor;
    double distance = 0.0;
    for (LocalPathPlanner.PathStep step : acceptedSteps) {
        if (step.action() != LocalPathPlanner.StepAction.SWIM) {
            break;
        }
        ResolvedSurface resolved = lookup.resolve(step).orElse(null);
        if (resolved == null) {
            break;
        }
        int resolvedY = resolved.waterPos().getY();
        if (surfaceY.isPresent() && surfaceY.getAsInt() != resolvedY) {
            break;
        }
        int dx = Math.abs(step.pos().getX() - previous.getX());
        int dz = Math.abs(step.pos().getZ() - previous.getZ());
        if (dx > 1 || dz > 1 || dx + dz == 0) {
            break;
        }
        if (surfaceY.isEmpty()) {
            surfaceY = OptionalInt.of(resolvedY);
        }
        distance += Math.hypot(dx, dz);
        surfaces.add(resolved);
        previous = step.pos();
        if (distance >= proofLimit) {
            break;
        }
    }
    return new Evidence(surfaceY, distance, surfaces);
}
```

- [ ] **Step 4: Add the live client-thread adapter**

Add these constants and helpers. The envelope deliberately spans neighboring blocks so waterlogged collision and narrow channels cannot contribute false evidence:

```java
private static final double BOAT_HALF_WIDTH = 0.6875;
private static final double BOAT_HEIGHT = 0.5625;

static AABB boatEnvelope(BlockPos waterSurface) {
    double centerX = waterSurface.getX() + 0.5;
    double centerZ = waterSurface.getZ() + 0.5;
    double bottomY = waterSurface.getY() + 1.0;
    return new AABB(
            centerX - BOAT_HALF_WIDTH,
            bottomY,
            centerZ - BOAT_HALF_WIDTH,
            centerX + BOAT_HALF_WIDTH,
            bottomY + BOAT_HEIGHT,
            centerZ + BOAT_HALF_WIDTH
    );
}
```

Add these instance overloads. They use only client-thread world reads; the final authoritative validation remains vanilla `BoatItem.use()` plus `InteractionResult.consumesAction()` in Task 6:

```java
Optional<ResolvedSurface> resolveBoatableSurface(Minecraft client, BlockPos waypoint) {
    Objects.requireNonNull(client, "client");
    if (client.level == null) {
        return Optional.empty();
    }
    return resolveBoatableSurface(
            waypoint,
            client.level.getMaxY() - 1,
            new SurfaceProbe() {
                @Override
                public boolean loaded(BlockPos pos) {
                    return client.level.hasChunkAt(pos);
                }

                @Override
                public boolean water(BlockPos pos) {
                    return client.level.getFluidState(pos).is(FluidTags.WATER);
                }

                @Override
                public boolean boatEnvelopeClear(BlockPos waterSurface) {
                    AABB envelope = boatEnvelope(waterSurface);
                    List<BlockPos> corners = List.of(
                            BlockPos.containing(envelope.minX, envelope.minY, envelope.minZ),
                            BlockPos.containing(envelope.minX, envelope.minY, envelope.maxZ),
                            BlockPos.containing(envelope.maxX, envelope.minY, envelope.minZ),
                            BlockPos.containing(envelope.maxX, envelope.minY, envelope.maxZ)
                    );
                    return corners.stream().allMatch(client.level::hasChunkAt)
                            && client.level.getWorldBorder().isWithinBounds(envelope)
                            && client.level.noBlockCollision(null, envelope);
                }
            }
    );
}

Evidence collect(
        Minecraft client,
        BlockPos anchor,
        List<LocalPathPlanner.PathStep> acceptedSteps,
        double proofLimit
) {
    return collect(
            anchor,
            acceptedSteps,
            proofLimit,
            step -> resolveBoatableSurface(client, step.pos())
    );
}

OptionalInt resolveBoatSurfaceY(Minecraft client, AbstractBoat boat) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(boat, "boat");
    if (client.level == null) {
        return OptionalInt.empty();
    }
    BlockPos hull = BlockPos.containing(
            boat.getX(), boat.getBoundingBox().minY - 0.05, boat.getZ());
    for (int depth = 0; depth <= 2; depth++) {
        Optional<ResolvedSurface> resolved = resolveBoatableSurface(
                client, hull.below(depth));
        if (resolved.isPresent()) {
            return OptionalInt.of(resolved.orElseThrow().waterPos().getY());
        }
    }
    return OptionalInt.empty();
}
```

Import `Minecraft`, `FluidTags`, `AbstractBoat`, and `AABB`, and never call these overloads from the path worker. Use `noBlockCollision`, not `noCollision`: route proof must reject solid geometry but must not be invalidated by the player or an already-present boat occupying the otherwise valid surface. Vanilla placement and boarding remain the authoritative entity-collision checks.

- [ ] **Step 5: Run adapter and path-coordinator tests**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.WaterRouteEvidenceAdapterTest" --tests "dev.mappywall.core.PathSegmentCoordinatorTest" --rerun-tasks
```

Expected: all tests pass; `PathSegmentCoordinator` remains unchanged.

- [ ] **Step 6: Commit surface evidence support**

```powershell
git add -- src/client/java/dev/mappywall/client/WaterRouteEvidenceAdapter.java src/test/java/dev/mappywall/client/WaterRouteEvidenceAdapterTest.java
git commit -m "Resolve accepted water routes to boat surfaces"
```

### Task 4: Model bounded boat acquisition

**Files:**
- Create: `src/main/java/dev/mappywall/core/BoatAcquisitionPolicy.java`
- Create: `src/test/java/dev/mappywall/core/BoatAcquisitionPolicyTest.java`

**Interfaces:**
- Consumes: water-run eligibility, transaction availability, held/carried boat observations, placement availability, route-adjacent/reachable/new boat observations, and passenger confirmation.
- Produces: a `Decision(Action, OptionalInt)`, explicit selection/placement/boarding result events, `FALLBACK` suppression, and reset.

- [ ] **Step 1: Write the failing state-machine tests**

Create `BoatAcquisitionPolicyTest` with explicit observations for every priority, confirmation, timeout, and reset boundary:

```java
package dev.mappywall.core;

import static dev.mappywall.core.BoatAcquisitionPolicy.Action.BOARD_SELECTED_BOAT;
import static dev.mappywall.core.BoatAcquisitionPolicy.Action.NONE;
import static dev.mappywall.core.BoatAcquisitionPolicy.Action.PLACE_HELD_BOAT;
import static dev.mappywall.core.BoatAcquisitionPolicy.Action.SELECT_CARRIED_BOAT;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.AWAITING_ENTITY;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.AWAITING_HELD_ITEM;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.AWAITING_PASSENGER;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.FALLBACK;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.IDLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class BoatAcquisitionPolicyTest {
    @Test
    void nearbyBoatHasPriorityOverHeldAndCarriedBoat() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision decision = policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false));
        assertEquals(BOARD_SELECTED_BOAT, decision.action());
        assertEquals(41, decision.boatEntityId().orElseThrow());
    }

    @Test
    void routeAdjacentBoatDefersInventoryUseUntilItBecomesReachable() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Observation unreachableRouteBoat =
                new BoatAcquisitionPolicy.Observation(
                        true, true, false, true, true, true, true,
                        OptionalInt.empty(), OptionalInt.empty(), false);
        assertEquals(NONE, policy.tick(unreachableRouteBoat).action());
        assertEquals(IDLE, policy.phase());

        BoatAcquisitionPolicy.Decision reachable = policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false));
        assertEquals(BOARD_SELECTED_BOAT, reachable.action());
        assertEquals(41, reachable.boatEntityId().orElseThrow());
    }

    @Test
    void unavailableTransactionsAreDeferredWithoutStartingATimer() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(NONE, policy.tick(observation(
                true, false, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false)).action());
        assertEquals(IDLE, policy.phase());
    }

    @Test
    void vanishedSelectionItemAndInvalidatedPlacementSurfaceAreBounded() {
        BoatAcquisitionPolicy selection = new BoatAcquisitionPolicy();
        assertEquals(SELECT_CARRIED_BOAT, selection.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        for (int tick = 0;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            selection.tick(observation(
                    true, true, false, false, false, true,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(FALLBACK, selection.phase());

        BoatAcquisitionPolicy placement = new BoatAcquisitionPolicy();
        assertEquals(PLACE_HELD_BOAT, placement.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        for (int tick = 0;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            placement.tick(observation(
                    true, true, false, true, true, false,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(FALLBACK, placement.phase());
    }

    @Test
    void carriedBoatSelectionRequiresHeldConfirmationAndTimesOut() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(SELECT_CARRIED_BOAT, policy.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.selectionRequested();
        assertEquals(AWAITING_HELD_ITEM, policy.phase());
        for (int tick = 0; tick < BoatAcquisitionPolicy.HELD_ITEM_CONFIRM_TIMEOUT_TICKS; tick++) {
            policy.tick(observation(true, true, false, false, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void twoRejectedPlacementsEnterFallback() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision placement = policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false));
        for (int attempt = 0; attempt < BoatAcquisitionPolicy.MAX_REJECTED_PLACEMENTS; attempt++) {
            assertEquals(PLACE_HELD_BOAT, placement.action());
            policy.placementResult(false);
            if (attempt + 1 < BoatAcquisitionPolicy.MAX_REJECTED_PLACEMENTS) {
                for (int tick = 1;
                        tick < BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS;
                        tick++) {
                    assertEquals(NONE, policy.tick(observation(
                            true, true, false, true, true, true,
                            OptionalInt.empty(), OptionalInt.empty(), false)).action());
                }
                placement = policy.tick(observation(
                        true, true, false, true, true, true,
                        OptionalInt.empty(), OptionalInt.empty(), false));
            }
        }
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void acceptedPlacementRequiresANewEntityAndTimesOutAtFortyTicks() {
        BoatAcquisitionPolicy policy = beginAcceptedPlacement();
        for (int tick = 0; tick < BoatAcquisitionPolicy.ENTITY_CONFIRM_TIMEOUT_TICKS; tick++) {
            assertEquals(NONE, policy.tick(observation(
                    true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
        }
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void newPlacedEntityIsBoardedAndPassengerConfirmationReturnsToIdle() {
        BoatAcquisitionPolicy policy = beginAcceptedPlacement();
        BoatAcquisitionPolicy.Decision board = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.empty(), OptionalInt.of(52), false));
        assertEquals(BOARD_SELECTED_BOAT, board.action());
        assertEquals(52, board.boatEntityId().orElseThrow());
        policy.boardingResult(52, true);
        assertEquals(AWAITING_PASSENGER, policy.phase());
        assertEquals(NONE, policy.tick(observation(
                true, true, true, false, false, false,
                OptionalInt.empty(), OptionalInt.empty(), true)).action());
        assertEquals(IDLE, policy.phase());
    }

    @Test
    void threeRejectedBoardingAttemptsEnterFallback() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision board = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        for (int attempt = 1; attempt <= BoatAcquisitionPolicy.MAX_BOARD_ATTEMPTS; attempt++) {
            assertEquals(BOARD_SELECTED_BOAT, board.action());
            policy.boardingResult(41, false);
            if (attempt < BoatAcquisitionPolicy.MAX_BOARD_ATTEMPTS) {
                board = advanceToBoardRetry(policy, 41);
            }
        }
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void acceptedBoardingRetriesRemainBoundedAndWaitsThirtyTicksForPassenger() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision board = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        policy.boardingResult(board.boatEntityId().orElseThrow(), true);
        for (int tick = 0;
                tick <= BoatAcquisitionPolicy.BOARD_CONFIRM_TIMEOUT_TICKS
                        && policy.phase() != FALLBACK;
                tick++) {
            BoatAcquisitionPolicy.Decision next = policy.tick(observation(
                    true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), true));
            if (next.action() == BOARD_SELECTED_BOAT) {
                policy.boardingResult(next.boatEntityId().orElseThrow(), true);
            }
        }
        assertEquals(BoatAcquisitionPolicy.MAX_BOARD_ATTEMPTS, policy.boardAttempts());
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void fallbackStaysSuppressedUntilWaterRunReset() {
        BoatAcquisitionPolicy policy = beginAcceptedPlacement();
        for (int tick = 0; tick < BoatAcquisitionPolicy.ENTITY_CONFIRM_TIMEOUT_TICKS; tick++) {
            policy.tick(observation(true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(NONE, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false)).action());
        policy.resetForWaterRun();
        assertEquals(BOARD_SELECTED_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false)).action());
    }

    @Test
    void invalidEventsAreRejected() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertThrows(IllegalStateException.class, policy::selectionRequested);
        assertThrows(IllegalStateException.class, () -> policy.placementResult(true));
        assertThrows(IllegalArgumentException.class, () -> policy.boardingResult(-1, true));
    }

    private static BoatAcquisitionPolicy beginAcceptedPlacement() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.placementResult(true);
        assertEquals(AWAITING_ENTITY, policy.phase());
        return policy;
    }

    private static BoatAcquisitionPolicy.Decision advanceToBoardRetry(
            BoatAcquisitionPolicy policy,
            int entityId
    ) {
        BoatAcquisitionPolicy.Decision decision = new BoatAcquisitionPolicy.Decision(
                NONE, OptionalInt.empty());
        for (int tick = 0;
                tick < BoatAcquisitionPolicy.BOARD_RETRY_INTERVAL_TICKS;
                tick++) {
            decision = policy.tick(observation(
                    true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), true));
        }
        assertEquals(entityId, decision.boatEntityId().orElseThrow());
        return decision;
    }

    private static BoatAcquisitionPolicy.Observation observation(
            boolean eligible,
            boolean transactionAvailable,
            boolean passengerInBoat,
            boolean heldBoatConfirmed,
            boolean carriedBoatAvailable,
            boolean placementSurfaceAvailable,
            OptionalInt nearbyBoatEntityId,
            OptionalInt newlySpawnedBoatEntityId,
            boolean selectedBoatPresent
    ) {
        return new BoatAcquisitionPolicy.Observation(
                eligible,
                transactionAvailable,
                passengerInBoat,
                heldBoatConfirmed,
                carriedBoatAvailable,
                placementSurfaceAvailable,
                nearbyBoatEntityId.isPresent(),
                nearbyBoatEntityId,
                newlySpawnedBoatEntityId,
                selectedBoatPresent
        );
    }
}
```

- [ ] **Step 2: Run the policy test and confirm RED**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.core.BoatAcquisitionPolicyTest" --rerun-tasks
```

Expected: compilation fails because `BoatAcquisitionPolicy` does not exist.

- [ ] **Step 3: Implement the state machine with fixed bounds**

Create `BoatAcquisitionPolicy` as a `public final` class in package `dev.mappywall.core`, importing `java.util.Objects` and `java.util.OptionalInt`. Use these exact public types and constants inside it:

```java
public static final int HELD_ITEM_CONFIRM_TIMEOUT_TICKS = 20;
public static final int REQUEST_VALIDITY_TIMEOUT_TICKS = 40;
public static final int PLACEMENT_RETRY_BACKOFF_TICKS = 40;
public static final int ENTITY_CONFIRM_TIMEOUT_TICKS = 40;
public static final int BOARD_CONFIRM_TIMEOUT_TICKS = 30;
public static final int BOARD_RETRY_INTERVAL_TICKS = 10;
public static final int MAX_REJECTED_PLACEMENTS = 2;
public static final int MAX_BOARD_ATTEMPTS = 3;

public enum Phase {
    IDLE,
    REQUESTING_SELECTION,
    AWAITING_HELD_ITEM,
    REQUESTING_PLACEMENT,
    PLACEMENT_BACKOFF,
    AWAITING_ENTITY,
    REQUESTING_BOARDING,
    AWAITING_PASSENGER,
    FALLBACK
}

public enum Action {
    NONE, SELECT_CARRIED_BOAT, PLACE_HELD_BOAT, BOARD_SELECTED_BOAT
}

public record Decision(Action action, OptionalInt boatEntityId) {
    public Decision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(boatEntityId, "boatEntityId");
        if ((action == Action.BOARD_SELECTED_BOAT) != boatEntityId.isPresent()) {
            throw new IllegalArgumentException("only boarding decisions require an entity id");
        }
    }
}

public record Observation(
        boolean eligibleWaterRun,
        boolean transactionAvailable,
        boolean passengerInBoat,
        boolean heldBoatConfirmed,
        boolean carriedBoatAvailable,
        boolean placementSurfaceAvailable,
        boolean routeAdjacentBoatAvailable,
        OptionalInt nearbyBoatEntityId,
        OptionalInt newlySpawnedBoatEntityId,
        boolean selectedBoatPresent
) {
    public Observation {
        Objects.requireNonNull(nearbyBoatEntityId, "nearbyBoatEntityId");
        Objects.requireNonNull(newlySpawnedBoatEntityId, "newlySpawnedBoatEntityId");
        if (nearbyBoatEntityId.isPresent() && nearbyBoatEntityId.getAsInt() < 0) {
            throw new IllegalArgumentException("nearby boat id must be non-negative");
        }
        if (newlySpawnedBoatEntityId.isPresent() && newlySpawnedBoatEntityId.getAsInt() < 0) {
            throw new IllegalArgumentException("new boat id must be non-negative");
        }
    }
}
```

Add these fields and implement the phase switch and event methods exactly as follows. Transaction-unavailable ticks pause all acquisition timers, so an open GUI cannot consume the retry budget:

```java
private static final Decision NO_DECISION = new Decision(Action.NONE, OptionalInt.empty());

private Phase phase = Phase.IDLE;
private int phaseTicks;
private int boardingTicks;
private int rejectedPlacements;
private int boardAttempts;
private OptionalInt selectedBoatId = OptionalInt.empty();

public Decision tick(Observation observation) {
    Objects.requireNonNull(observation, "observation");
    if (!observation.eligibleWaterRun()) {
        resetForWaterRun();
        return NO_DECISION;
    }
    if (observation.passengerInBoat()) {
        resetForWaterRun();
        return NO_DECISION;
    }
    if (phase == Phase.FALLBACK || !observation.transactionAvailable()) {
        return NO_DECISION;
    }
    return switch (phase) {
        case IDLE -> chooseInitialAction(observation);
        case REQUESTING_SELECTION -> tickRequestingSelection(observation);
        case REQUESTING_PLACEMENT -> tickRequestingPlacement(observation);
        case PLACEMENT_BACKOFF -> tickPlacementBackoff(observation);
        case REQUESTING_BOARDING -> tickRequestingBoarding(observation);
        case AWAITING_HELD_ITEM -> tickAwaitingHeldItem(observation);
        case AWAITING_ENTITY -> tickAwaitingEntity(observation);
        case AWAITING_PASSENGER -> tickAwaitingPassenger(observation);
        case FALLBACK -> NO_DECISION;
    };
}

public void selectionRequested() {
    requirePhase(Phase.REQUESTING_SELECTION);
    phase = Phase.AWAITING_HELD_ITEM;
    phaseTicks = 0;
}

public void placementResult(boolean accepted) {
    requirePhase(Phase.REQUESTING_PLACEMENT);
    if (accepted) {
        phase = Phase.AWAITING_ENTITY;
        phaseTicks = 0;
        return;
    }
    rejectedPlacements++;
    if (rejectedPlacements >= MAX_REJECTED_PLACEMENTS) {
        enterFallback();
    } else {
        phase = Phase.PLACEMENT_BACKOFF;
        phaseTicks = 0;
    }
}

public void boardingResult(int entityId, boolean accepted) {
    if (entityId < 0) {
        throw new IllegalArgumentException("entityId must be non-negative");
    }
    requirePhase(Phase.REQUESTING_BOARDING);
    if (selectedBoatId.isEmpty() || selectedBoatId.getAsInt() != entityId) {
        throw new IllegalArgumentException("boarding result does not match selected boat");
    }
    boardAttempts++;
    if (!accepted && boardAttempts >= MAX_BOARD_ATTEMPTS) {
        enterFallback();
        return;
    }
    phase = Phase.AWAITING_PASSENGER;
}

public void resetForWaterRun() {
    phase = Phase.IDLE;
    phaseTicks = 0;
    boardingTicks = 0;
    rejectedPlacements = 0;
    boardAttempts = 0;
    selectedBoatId = OptionalInt.empty();
}

public Phase phase() {
    return phase;
}

public int boardAttempts() {
    return boardAttempts;
}

public OptionalInt selectedBoatId() {
    return selectedBoatId;
}

private Decision chooseInitialAction(Observation observation) {
    if (observation.nearbyBoatEntityId().isPresent()) {
        return requestBoarding(observation.nearbyBoatEntityId().getAsInt(), true);
    }
    if (observation.routeAdjacentBoatAvailable()) {
        return NO_DECISION;
    }
    if (observation.heldBoatConfirmed() && observation.placementSurfaceAvailable()) {
        phase = Phase.REQUESTING_PLACEMENT;
        phaseTicks = 0;
        return new Decision(Action.PLACE_HELD_BOAT, OptionalInt.empty());
    }
    if (!observation.heldBoatConfirmed() && observation.carriedBoatAvailable()) {
        phase = Phase.REQUESTING_SELECTION;
        phaseTicks = 0;
        return new Decision(Action.SELECT_CARRIED_BOAT, OptionalInt.empty());
    }
    return NO_DECISION;
}

private Decision tickRequestingSelection(Observation observation) {
    if (observation.carriedBoatAvailable()) {
        return tickValidRequest(
                new Decision(Action.SELECT_CARRIED_BOAT, OptionalInt.empty()));
    }
    return tickInvalidRequest();
}

private Decision tickRequestingPlacement(Observation observation) {
    if (observation.heldBoatConfirmed() && observation.placementSurfaceAvailable()) {
        return tickValidRequest(
                new Decision(Action.PLACE_HELD_BOAT, OptionalInt.empty()));
    }
    return tickInvalidRequest();
}

private Decision tickPlacementBackoff(Observation observation) {
    phaseTicks++;
    if (phaseTicks < PLACEMENT_RETRY_BACKOFF_TICKS) {
        return NO_DECISION;
    }
    phase = Phase.IDLE;
    phaseTicks = 0;
    return chooseInitialAction(observation);
}

private Decision tickRequestingBoarding(Observation observation) {
    if (selectedBoatId.isPresent() && observation.selectedBoatPresent()) {
        return tickValidRequest(
                new Decision(Action.BOARD_SELECTED_BOAT, selectedBoatId));
    }
    return tickInvalidRequest();
}

private Decision tickInvalidRequest() {
    phaseTicks++;
    if (phaseTicks >= REQUEST_VALIDITY_TIMEOUT_TICKS) {
        enterFallback();
    }
    return NO_DECISION;
}

private Decision tickValidRequest(Decision decision) {
    phaseTicks++;
    if (phaseTicks >= REQUEST_VALIDITY_TIMEOUT_TICKS) {
        enterFallback();
        return NO_DECISION;
    }
    return decision;
}

private Decision tickAwaitingHeldItem(Observation observation) {
    if (observation.heldBoatConfirmed()) {
        phase = Phase.IDLE;
        phaseTicks = 0;
        return chooseInitialAction(observation);
    }
    phaseTicks++;
    if (phaseTicks >= HELD_ITEM_CONFIRM_TIMEOUT_TICKS) {
        enterFallback();
    }
    return NO_DECISION;
}

private Decision tickAwaitingEntity(Observation observation) {
    if (observation.newlySpawnedBoatEntityId().isPresent()) {
        return requestBoarding(observation.newlySpawnedBoatEntityId().getAsInt(), true);
    }
    phaseTicks++;
    if (phaseTicks >= ENTITY_CONFIRM_TIMEOUT_TICKS) {
        enterFallback();
    }
    return NO_DECISION;
}

private Decision tickAwaitingPassenger(Observation observation) {
    if (!observation.selectedBoatPresent()) {
        enterFallback();
        return NO_DECISION;
    }
    boardingTicks++;
    if (boardingTicks >= BOARD_CONFIRM_TIMEOUT_TICKS) {
        enterFallback();
        return NO_DECISION;
    }
    if (boardAttempts < MAX_BOARD_ATTEMPTS
            && boardingTicks % BOARD_RETRY_INTERVAL_TICKS == 0) {
        return requestBoarding(selectedBoatId.orElseThrow(), false);
    }
    return NO_DECISION;
}

private Decision requestBoarding(int entityId, boolean newSession) {
    if (entityId < 0) {
        throw new IllegalArgumentException("entityId must be non-negative");
    }
    selectedBoatId = OptionalInt.of(entityId);
    if (newSession) {
        boardingTicks = 0;
        boardAttempts = 0;
    }
    phaseTicks = 0;
    phase = Phase.REQUESTING_BOARDING;
    return new Decision(Action.BOARD_SELECTED_BOAT, selectedBoatId);
}

private void enterFallback() {
    phase = Phase.FALLBACK;
    selectedBoatId = OptionalInt.empty();
}

private void requirePhase(Phase expected) {
    if (phase != expected) {
        throw new IllegalStateException("expected " + expected + " but was " + phase);
    }
}
```

- [ ] **Step 4: Run the focused state-machine test and confirm GREEN**

Run the Step 2 command again.

Expected: every transition, timeout, priority, and reset test passes.

- [ ] **Step 5: Commit the acquisition policy**

```powershell
git add -- src/main/java/dev/mappywall/core/BoatAcquisitionPolicy.java src/test/java/dev/mappywall/core/BoatAcquisitionPolicyTest.java
git commit -m "Model confirmed boat acquisition"
```

### Task 5: Integrate route evidence, completed distance, and deep-water boat traversal

**Files:**
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Create: `src/test/java/dev/mappywall/client/MovementControllerWaterTransitTest.java`
- Modify: `src/test/java/dev/mappywall/client/MovementControllerDismountTest.java`

**Interfaces:**
- Consumes: `WaterTransitPolicy`, `WaterRouteEvidenceAdapter`, `pathSegments.previewStepSnapshot()`, and resolved surfaces.
- Produces: one compatible water-run anchor, exact-once completed-edge recording, surface-aware dismount, and surface-aware mounted waypoint completion.

- [ ] **Step 1: Add failing pure and source-contract integration tests**

Create `MovementControllerWaterTransitTest` with these complete tests and source-slice helper:

```java
package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class MovementControllerWaterTransitTest {
    @Test
    void mountedSubmergedSwimWaypointUsesResolvedSurfaceAndHorizontalTolerance() {
        assertTrue(MovementController.isCompatibleResolvedBoatSurface(
                OptionalInt.of(64), OptionalInt.of(64), OptionalInt.of(64)));
        assertFalse(MovementController.isCompatibleResolvedBoatSurface(
                OptionalInt.of(64), OptionalInt.of(65), OptionalInt.of(64)));
        assertFalse(MovementController.isCompatibleResolvedBoatSurface(
                OptionalInt.of(64), OptionalInt.of(64), OptionalInt.empty()));
        assertTrue(MovementController.isResolvedBoatSwimWaypointComplete(0.42, true, true));
        assertFalse(MovementController.isResolvedBoatSwimWaypointComplete(0.4201, true, true));
        assertFalse(MovementController.isResolvedBoatSwimWaypointComplete(0.42, false, true));
        assertFalse(MovementController.isResolvedBoatSwimWaypointComplete(0.42, true, false));

        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.SWIM,
                0.42,
                65.0,
                65.0,
                60,
                false,
                false,
                true
        ));
    }

    @Test
    void compatibleReplanRequiresAnAdjacentResolvedAnchorInTheSameRun() {
        BlockPos previous = new BlockPos(0, 60, 0);
        assertTrue(MovementController.canPreserveWaterRunAcrossReplan(
                previous,
                new BlockPos(1, 60, 0),
                OptionalInt.of(64),
                OptionalInt.of(64)
        ));
        assertFalse(MovementController.canPreserveWaterRunAcrossReplan(
                previous,
                new BlockPos(1, 60, 0),
                OptionalInt.of(64),
                OptionalInt.empty()
        ));
        assertFalse(MovementController.canPreserveWaterRunAcrossReplan(
                previous,
                new BlockPos(10, 60, 0),
                OptionalInt.of(64),
                OptionalInt.of(64)
        ));
        assertFalse(MovementController.canPreserveWaterRunAcrossReplan(
                previous,
                new BlockPos(1, 60, 0),
                OptionalInt.of(64),
                OptionalInt.of(65)
        ));

        assertTrue(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.of(64), 64, false));
        assertFalse(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.of(64), 65, false));
        assertTrue(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.empty(), 64, true));
        assertFalse(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.empty(), 64, false));
    }

    @Test
    void controllerUsesAcceptedPreviewAndPreservesWaterPrefixAcrossOrdinaryReplan()
            throws IOException {
        String source = Files.readString(Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "MovementController.java"));
        assertTrue(source.contains("nextWaypoint(client, player)"));
        assertTrue(source.contains("recordCompletedSwimStep(client, player, step)"));
        assertTrue(source.contains("pathSegments.previewStepSnapshot()"));
        assertTrue(source.contains("waterTransitPolicy.observe("));
        assertTrue(source.contains("waterRouteEvidenceAdapter.resolveBoatableSurface("));
        int nextStart = source.indexOf("private LocalPathPlanner.PathStep nextWaypoint(");
        int refresh = source.indexOf("refreshWaterEvidence(client, player)", nextStart);
        int reached = source.indexOf("isAtWaypoint(client, player, step)", nextStart);
        assertTrue(nextStart >= 0 && nextStart < refresh && refresh < reached);
        String swim = methodSource(
                source,
                "private MovementResult swimOrBoat(",
                "private MovementResult swimToward("
        );
        assertFalse(swim.contains("refreshWaterEvidence(client, player)"));
        assertTrue(swim.contains("currentWaterTravelDecision"));

        String replan = methodSource(
                source,
                "private void forceLocalReplan()",
                "private double squaredHorizontalDistance("
        );
        assertFalse(replan.contains("resetWaterTransit()"));
        assertFalse(replan.contains("waterTransitPolicy.reset()"));
        assertTrue(replan.contains("waterRunAnchor = null"));
        assertTrue(replan.contains("waterReplanContinuityPending"));
        assertTrue(replan.contains("waterReplanAnchor"));
        assertTrue(replan.contains(
                "currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none()"));

        String targetChange = methodSource(
                source,
                "private void handleNavigationTargetChange(",
                "private void advancePendingCapture("
        );
        String release = methodSource(
                source,
                "public void release(Minecraft client)",
                "public void hardReset("
        );
        String reset = methodSource(
                source,
                "private void resetProgress()",
                "private void resetBreakBudgetIfTargetChanged("
        );
        assertTrue(targetChange.contains("resetWaterTransit()"));
        assertTrue(release.contains("resetWaterTransit()"));
        assertTrue(reset.contains("resetWaterTransit()"));

        String configUpdate = methodSource(
                source,
                "public void setAggressiveConfig(",
                "public MovementResult tick("
        );
        assertTrue(configUpdate.contains("minimumBoatDistanceBlocks()"));
        assertTrue(configUpdate.contains("automationStyle == AutomationStyle.AGGRESSIVE"));
        assertTrue(configUpdate.contains("resetWaterTransit()"));

        String tick = methodSource(
                source,
                "public MovementResult tick(",
                "private void handleNavigationTargetChange("
        );
        assertTrue(tick.contains("updateAutomationStyle(save.project().automationStyle())"));
        String styleUpdate = methodSource(
                source,
                "private void updateAutomationStyle(",
                "private AutoNavigationConfig navigationConfig("
        );
        assertTrue(styleUpdate.contains("automationStyle != requestedStyle"));
        assertTrue(styleUpdate.contains("resetWaterTransit()"));
        assertTrue(styleUpdate.contains("forceLocalReplan()"));
    }

    private static String methodSource(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0, start);
        assertTrue(endIndex > startIndex, end);
        return source.substring(startIndex, endIndex);
    }
}
```

In `MovementControllerDismountTest.controllerSourceCentralizesVehicleBeginsAndLifecycleResetWiring()`, replace `surfaceWaterRoute` with `boatableSwimRoute` in the compact call assertion, change the source lookup to `LocalPathPlanner.PathStep waypoint = nextWaypoint(client, player)`, and add:

```java
assertTrue(compact(waypointDismount).contains(
        "isCompatibleResolvedBoatSurface(waterTransitPolicy.surfaceY(),"
                + "waypointSurfaceY,boatSurfaceY)"));
```

- [ ] **Step 2: Run the controller water/dismount tests and confirm RED**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.MovementControllerWaterTransitTest" --tests "dev.mappywall.client.MovementControllerDismountTest" --rerun-tasks
```

Expected: compile/assertion failures because none of the new integration exists.

- [ ] **Step 3: Add controller state and reset boundaries**

Add these fields:

```java
private final WaterTransitPolicy waterTransitPolicy = new WaterTransitPolicy();
private final WaterRouteEvidenceAdapter waterRouteEvidenceAdapter = new WaterRouteEvidenceAdapter();
private WaterTransitPolicy.TravelDecision currentWaterTravelDecision =
        WaterTransitPolicy.TravelDecision.SWIM;
private BlockPos waterRunAnchor;
private OptionalInt waterRunAnchorSurfaceY = OptionalInt.empty();
private BlockPos waterReplanAnchor;
private boolean waterReplanContinuityPending;
private WaterRouteEvidenceAdapter.Evidence currentWaterEvidence =
        WaterRouteEvidenceAdapter.Evidence.none();
```

Add this single reset boundary:

```java
private void resetWaterTransit() {
    waterTransitPolicy.reset();
    currentWaterTravelDecision = WaterTransitPolicy.TravelDecision.SWIM;
    waterRunAnchor = null;
    waterRunAnchorSurfaceY = OptionalInt.empty();
    waterReplanAnchor = null;
    waterReplanContinuityPending = false;
    currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
}
```

Call `resetWaterTransit()` once in `handleNavigationTargetChange()` after a new signature is accepted, once in `release()`, once in `resetProgress()`, and once in `clearPlanningForRecovery()`. Existing world/mode terminal paths already flow through `release()` or `resetProgress()`; do not duplicate their calls. Do not call it from `forceLocalReplan()`.

In `setAggressiveConfig(...)`, compare the old and new `minimumBoatDistanceBlocks()` before assignment. If it changed **and** `automationStyle == AutomationStyle.AGGRESSIVE`, call `resetWaterTransit()` before `forceLocalReplan()`; edits to breaking lists alone keep the compatible water prefix, and editing the inactive aggressive profile does not erase a normal-mode run. This makes raising 12 to 37 immediately revoke an active aggressive latch. A later normal-to-aggressive switch is independently reset by the style boundary below.

Replace the unconditional `automationStyle = save.project().automationStyle()` assignment in `tick(...)` with `updateAutomationStyle(save.project().automationStyle())`, and add this method immediately before `navigationConfig()`:

```java
private void updateAutomationStyle(AutomationStyle requestedStyle) {
    Objects.requireNonNull(requestedStyle, "requestedStyle");
    if (automationStyle != requestedStyle) {
        automationStyle = requestedStyle;
        resetWaterTransit();
        forceLocalReplan();
    }
}
```

Both directions reset the run: normal mode always uses its 12-block default, while aggressive mode may use the persisted custom threshold. Replanning also prevents a path captured under one mode's navigation config from continuing after the style changes.

- [ ] **Step 4: Record consumed water edges exactly once and analyze accepted future evidence**

Change `advancePathStep()` from `void` to `boolean`: store `pathSegments.advance()` in `advanced`, increment `currentStepOrdinal` only when true, retain every existing per-step reset, then `return advanced`. Existing BREAK/PLACE callers may ignore the return value.

Replace `nextWaypoint(LocalPlayer)` with this exact retirement loop:

```java
private LocalPathPlanner.PathStep nextWaypoint(Minecraft client, LocalPlayer player) {
    while (true) {
        LocalPathPlanner.PathStep step = pathSegments.currentStepOrPromote().orElse(null);
        if (step == null) {
            return null;
        }
        if (step.action() != LocalPathPlanner.StepAction.SWIM) {
            waterTransitPolicy.leaveWaterRun();
            currentWaterTravelDecision = WaterTransitPolicy.TravelDecision.SWIM;
            waterRunAnchor = null;
            waterRunAnchorSurfaceY = OptionalInt.empty();
            waterReplanAnchor = null;
            waterReplanContinuityPending = false;
            currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
        } else {
            currentWaterTravelDecision = refreshWaterEvidence(client, player);
        }
        if (step.action() == LocalPathPlanner.StepAction.BREAK
                || step.action() == LocalPathPlanner.StepAction.PLACE) {
            return step;
        }
        if (isAtWaypoint(client, player, step)) {
            boolean advanced = advancePathStep();
            if (advanced && step.action() == LocalPathPlanner.StepAction.SWIM) {
                recordCompletedSwimStep(client, player, step);
            }
            continue;
        }
        return step;
    }
}

private void recordCompletedSwimStep(
        Minecraft client,
        LocalPlayer player,
        LocalPathPlanner.PathStep step
) {
    WaterRouteEvidenceAdapter.ResolvedSurface resolved = waterRouteEvidenceAdapter
            .resolveBoatableSurface(client, step.pos())
            .orElse(null);
    if (resolved == null) {
        waterTransitPolicy.leaveWaterRun();
        currentWaterTravelDecision = WaterTransitPolicy.TravelDecision.SWIM;
        waterRunAnchor = null;
        waterRunAnchorSurfaceY = OptionalInt.empty();
        waterReplanAnchor = null;
        waterReplanContinuityPending = false;
        currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
        return;
    }
    BlockPos anchor = waterRunAnchor == null
            ? navigationFeetResolver.resolve(player)
            : waterRunAnchor;
    int dx = Math.abs(step.pos().getX() - anchor.getX());
    int dz = Math.abs(step.pos().getZ() - anchor.getZ());
    boolean knownLoadedNonWaterAnchor = client.level != null
            && client.level.hasChunkAt(anchor)
            && !client.level.getFluidState(anchor).is(FluidTags.WATER);
    if (dx <= 1
            && dz <= 1
            && dx + dz > 0
            && shouldCountCompletedWaterEdge(
                    waterRunAnchorSurfaceY,
                    resolved.waterPos().getY(),
                    knownLoadedNonWaterAnchor
            )) {
        waterTransitPolicy.recordCompletedEdge(
                resolved.waterPos().getY(), Math.hypot(dx, dz));
    }
    waterRunAnchor = step.pos();
    waterRunAnchorSurfaceY = OptionalInt.of(resolved.waterPos().getY());
}

static boolean shouldCountCompletedWaterEdge(
        OptionalInt anchorSurfaceY,
        int completedSurfaceY,
        boolean knownLoadedNonWaterAnchor
) {
    Objects.requireNonNull(anchorSurfaceY, "anchorSurfaceY");
    return anchorSurfaceY.isPresent()
            ? anchorSurfaceY.getAsInt() == completedSurfaceY
            : knownLoadedNonWaterAnchor;
}
```

`refreshWaterEvidence(...)` must run before the retirement check above. That primes the policy surface before an already-reached first `SWIM` step is recorded, so an exact 12-block run does not lose its entry edge. Implement it with replan continuity validation:

```java
private WaterTransitPolicy.TravelDecision refreshWaterEvidence(
        Minecraft client,
        LocalPlayer player
) {
    if (waterRunAnchor == null) {
        BlockPos candidate = navigationFeetResolver.resolve(player);
        OptionalInt candidateSurfaceY = waterRouteEvidenceAdapter
                .resolveBoatableSurface(client, candidate)
                .map(surface -> OptionalInt.of(surface.waterPos().getY()))
                .orElseGet(OptionalInt::empty);
        if (waterReplanContinuityPending
                && !canPreserveWaterRunAcrossReplan(
                        waterReplanAnchor,
                        candidate,
                        waterTransitPolicy.surfaceY(),
                        candidateSurfaceY
                )) {
            waterTransitPolicy.leaveWaterRun();
        }
        waterRunAnchor = candidate;
        waterRunAnchorSurfaceY = candidateSurfaceY;
        waterReplanAnchor = null;
        waterReplanContinuityPending = false;
    }

    double remainingProof = Math.max(
            1.0e-9,
            navigationConfig().minimumBoatDistanceBlocks()
                    - waterTransitPolicy.completedDistanceBlocks()
    );
    currentWaterEvidence = waterRouteEvidenceAdapter.collect(
            client,
            waterRunAnchor,
            pathSegments.previewStepSnapshot(),
            remainingProof
    );
    return waterTransitPolicy.observe(new WaterTransitPolicy.RunObservation(
            currentWaterEvidence.surfaceY(),
            currentWaterEvidence.futureConfirmedDistanceBlocks(),
            navigationConfig().minimumBoatDistanceBlocks(),
            player.getVehicle() instanceof AbstractBoat
    ));
}

static boolean canPreserveWaterRunAcrossReplan(
        BlockPos previousAnchor,
        BlockPos currentAnchor,
        OptionalInt previousSurfaceY,
        OptionalInt currentSurfaceY
) {
    if (previousAnchor == null || currentAnchor == null) {
        return false;
    }
    Objects.requireNonNull(previousSurfaceY, "previousSurfaceY");
    Objects.requireNonNull(currentSurfaceY, "currentSurfaceY");
    int dx = Math.abs(previousAnchor.getX() - currentAnchor.getX());
    int dz = Math.abs(previousAnchor.getZ() - currentAnchor.getZ());
    return dx <= 1
            && dz <= 1
            && previousSurfaceY.isPresent()
            && currentSurfaceY.isPresent()
            && previousSurfaceY.getAsInt() == currentSurfaceY.getAsInt();
}
```

`nextWaypoint(...)` is the sole caller of `refreshWaterEvidence(...)` and stores its return in `currentWaterTravelDecision` before testing waypoint completion. `swimOrBoat(...)` consumes that precomputed field; it must not rescan the same deep columns again in the same controller tick. Use `CONTINUE_RIDING` for the unchanged `driveBoatToward(...)`; use `SWIM` for the unchanged `swimToward(...)`; Task 6 fills `ACQUIRE_BOAT`. If the retirement loop consumes several already-reached `SWIM` cells, it refreshes after each anchor change and leaves the decision for the final returned waypoint. A planning gap has no current `SWIM` step, makes no observation, and preserves the completed prefix.

In `forceLocalReplan()`, preserve proof conditionally with this block immediately before nulling the live anchor:

```java
if (!waterReplanContinuityPending && waterTransitPolicy.surfaceY().isPresent()) {
    waterReplanAnchor = waterRunAnchor;
    waterReplanContinuityPending = true;
}
waterRunAnchor = null;
waterRunAnchorSurfaceY = OptionalInt.empty();
currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
```

Do not reset `waterTransitPolicy` here. On the next accepted `SWIM` step, `refreshWaterEvidence(...)` retains the prefix only when the rebased player anchor is loaded boatable water on the same surface and remains adjacent to the last confirmed cursor. A shore anchor, a distant same-height pool, a different surface, or missing old cursor starts a new run; the unfinished partial edge is never added.

- [ ] **Step 5: Reuse resolved surfaces for dismount and mounted completion**

Replace the existing `vehiclePresent`/`vehicleIsBoat`/exact-surface block with the target-surface and hull-surface resolution below:

```java
Entity vehicle = player.getVehicle();
boolean vehiclePresent = vehicle != null;
boolean vehicleIsBoat = vehicle instanceof AbstractBoat;
WaterRouteEvidenceAdapter.ResolvedSurface waypointSurface = swimWaypoint
        ? waterRouteEvidenceAdapter.resolveBoatableSurface(client, waypoint.pos()).orElse(null)
        : null;
OptionalInt waypointSurfaceY = waypointSurface == null
        ? OptionalInt.empty()
        : OptionalInt.of(waypointSurface.waterPos().getY());
OptionalInt boatSurfaceY = vehicle instanceof AbstractBoat boat
        ? waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat)
        : OptionalInt.empty();
boolean boatableSwimRoute = swimWaypoint
        && vehicleIsBoat
        && isCompatibleResolvedBoatSurface(
                waterTransitPolicy.surfaceY(), waypointSurfaceY, boatSurfaceY);
```

Pass that value to `shouldBeginVehicleDismountForWaypoint(...)`.

Change `isAtWaypoint` to accept `Minecraft client`. Immediately after calculating `horizontalDistance`, resolve the current SWIM cell and short-circuit mounted completion with:

```java
AbstractBoat boat = player.getVehicle() instanceof AbstractBoat currentBoat
        ? currentBoat
        : null;
OptionalInt waypointSurfaceY = step.action() == LocalPathPlanner.StepAction.SWIM
        ? waterRouteEvidenceAdapter.resolveBoatableSurface(client, pos)
                .map(surface -> OptionalInt.of(surface.waterPos().getY()))
                .orElseGet(OptionalInt::empty)
        : OptionalInt.empty();
OptionalInt boatSurfaceY = boat == null
        ? OptionalInt.empty()
        : waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat);
boolean compatibleBoatableSurface = isCompatibleResolvedBoatSurface(
        waterTransitPolicy.surfaceY(), waypointSurfaceY, boatSurfaceY);
if (isResolvedBoatSwimWaypointComplete(
        horizontalDistance, boat != null, compatibleBoatableSurface)) {
    return true;
}
```

Add the shared compatibility and completion helpers:

```java
static boolean isCompatibleResolvedBoatSurface(
        OptionalInt waterRunSurfaceY,
        OptionalInt waypointSurfaceY,
        OptionalInt boatSurfaceY
) {
    Objects.requireNonNull(waterRunSurfaceY, "waterRunSurfaceY");
    Objects.requireNonNull(waypointSurfaceY, "waypointSurfaceY");
    Objects.requireNonNull(boatSurfaceY, "boatSurfaceY");
    return waterRunSurfaceY.isPresent()
            && waypointSurfaceY.isPresent()
            && boatSurfaceY.isPresent()
            && waterRunSurfaceY.getAsInt() == waypointSurfaceY.getAsInt()
            && waypointSurfaceY.getAsInt() == boatSurfaceY.getAsInt();
}

static boolean isResolvedBoatSwimWaypointComplete(
        double horizontalDistance,
        boolean inBoat,
        boolean compatibleBoatableSurface
) {
    return inBoat
            && compatibleBoatableSurface
            && horizontalDistance <= SWIM_WAYPOINT_DISTANCE_BLOCKS;
}
```

Unmounted swimming continues through the existing `evaluateWaypointCompletion(...)` physical-Y rule.

- [ ] **Step 6: Run focused water, dismount, continuity, and feet tests**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.core.WaterTransitPolicyTest" --tests "dev.mappywall.client.WaterRouteEvidenceAdapterTest" --tests "dev.mappywall.client.MovementControllerWaterTransitTest" --tests "dev.mappywall.client.MovementControllerDismountTest" --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --tests "dev.mappywall.core.NavigationContinuityScenarioTest" --rerun-tasks
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit route integration**

```powershell
git add -- src/client/java/dev/mappywall/client/MovementController.java src/test/java/dev/mappywall/client/MovementControllerWaterTransitTest.java src/test/java/dev/mappywall/client/MovementControllerDismountTest.java
git commit -m "Track confirmed water routes in movement"
```

### Task 6: Replace cooldown-only placement with confirmed acquisition

**Files:**
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/test/java/dev/mappywall/client/MovementControllerWaterTransitTest.java`

**Interfaces:**
- Consumes: `BoatAcquisitionPolicy`, current `Evidence.surfaces()`, route-adjacent and reachable boat observations, inventory helpers, `InteractionResult`, loaded boat entity IDs, and dismount suppression.
- Produces: bounded non-blocking surface approach, selection, route-only placement, new-entity confirmation, and passenger-confirmed boarding.

- [ ] **Step 1: Add failing acquisition, deep-surface, and short-water contracts**

Append these complete methods to `MovementControllerWaterTransitTest` and import `BoatAcquisitionPolicy.Phase`, `List`, and `Set`:

```java
@Test
void controllerConfirmsPlacementAndRoutesOnlyEligibleWaterToAcquisition()
        throws IOException {
    String source = Files.readString(Path.of(
            "src", "client", "java", "dev", "mappywall", "client",
            "MovementController.java"));
    String compactSource = source.replaceAll("\\s+", "");
    assertTrue(source.contains("InteractionResult placementResult"));
    assertTrue(source.contains("placementResult.consumesAction()"));
    assertTrue(source.contains("captureLoadedBoatIds("));
    assertTrue(compactSource.contains(
            "isNewPlacementBoat(boat.getId(),boatPlacementBaseline"));
    assertTrue(source.contains("client.level.getEntity(selectedBoatId)"));
    assertTrue(source.contains("pathSegments.previewStepSnapshot()"));
    assertTrue(source.contains("findRouteAdjacentEligibleBoat("));
    assertTrue(source.contains("reachableBoatFromCandidate("));
    assertTrue(source.contains("acceptedWaterCorridorSurfaces("));
    assertTrue(source.contains("acceptedApproachSurfaceForBoat("));
    assertTrue(source.contains("boatAcquisitionPolicy.selectedBoatId()"));
    assertTrue(source.contains("swimTowardBoatSurface("));
    assertTrue(source.contains("madeBoatSurfaceVerticalProgress("));
    assertTrue(source.contains("trackStep(waypoint, continuingBoatSurfaceApproach)"));
    assertTrue(compactSource.contains("if(!suspendWaypointTimeout){activeStepTicks++;}"));
    assertTrue(compactSource.contains(
            "returnsuspendWaypointTimeout||activeStepTicks<=STUCK_TICKS_LIMIT*2;"));
    assertTrue(source.contains("!surfaceApproachActive"));
    assertFalse(source.contains("bestBoatWaterPos("));
    assertFalse(source.contains("isSurfaceWaterRoute("));
    assertFalse(source.contains("BOAT_COOLDOWN_TICKS"));
    assertFalse(source.contains("boatCooldown"));

    String swim = methodSource(
            source,
            "private MovementResult swimOrBoat(",
            "private MovementResult swimToward("
    );
    String compactSwim = swim.replaceAll("\\s+", "");
    assertTrue(compactSwim.contains("caseSWIM->"));
    assertTrue(compactSwim.contains("resetBoatAcquisition();"));
    assertTrue(compactSwim.contains("yieldswimToward("));
    assertTrue(compactSwim.contains("caseACQUIRE_BOAT->"));
    assertTrue(compactSwim.contains("acquireBoatOrSwim("));
    assertTrue(compactSource.contains(
            "selectOrMoveToHotbar(client,player,slot);boatAcquisitionPolicy.selectionRequested();"));
}

@Test
void placementConfirmationRequiresANewIdWithinThreeBlocks() {
    Set<Integer> baseline = Set.of(7, 11);
    assertFalse(MovementController.isNewPlacementBoat(7, baseline, 1.0));
    assertFalse(MovementController.isNewPlacementBoat(12, baseline, 9.0001));
    assertFalse(MovementController.isNewPlacementBoat(-1, baseline, 1.0));
    assertTrue(MovementController.isNewPlacementBoat(12, baseline, 9.0));
}

@Test
void onlyAnAcceptedCorridorColumnCanDeferInventoryPlacement() {
    List<BlockPos> accepted = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0)
    );
    assertTrue(MovementController.isAcceptedBoatCorridorColumn(
            new BlockPos(1, 64, 0), accepted));
    assertFalse(MovementController.isAcceptedBoatCorridorColumn(
            new BlockPos(1, 64, 1), accepted));
    assertFalse(MovementController.isAcceptedBoatCorridorColumn(
            new BlockPos(1, 65, 0), accepted));
}

@Test
void deepResolvedSurfaceIsApproachedWithoutTreatingFallbackAsAcquisition() {
    assertTrue(MovementController.shouldHoldBoatAcquisitionSurface(
            Phase.IDLE, true, true));
    assertTrue(MovementController.shouldHoldBoatAcquisitionSurface(
            Phase.AWAITING_ENTITY, false, true));
    assertFalse(MovementController.shouldHoldBoatAcquisitionSurface(
            Phase.IDLE, false, true));
    assertFalse(MovementController.shouldHoldBoatAcquisitionSurface(
            Phase.FALLBACK, true, true));
    assertFalse(MovementController.shouldHoldBoatAcquisitionSurface(
            Phase.AWAITING_ENTITY, true, false));
}

@Test
void verticalSurfaceProgressPreventsFalseHorizontalStall() {
    BlockPos surface = new BlockPos(0, 64, 0);
    assertTrue(MovementController.madeBoatSurfaceVerticalProgress(
            surface, surface, 11.92, 12.0));
    assertFalse(MovementController.madeBoatSurfaceVerticalProgress(
            surface, null, 12.0, Double.MAX_VALUE));
    assertFalse(MovementController.madeBoatSurfaceVerticalProgress(
            surface, surface, 12.0, 12.0));
}
```

- [ ] **Step 2: Run focused controller/policy tests and confirm RED**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.core.BoatAcquisitionPolicyTest" --tests "dev.mappywall.client.MovementControllerWaterTransitTest" --rerun-tasks
```

Expected: new source/pure assertions fail against the cooldown-only implementation.

- [ ] **Step 3: Add acquisition state and entity baselines**

Add these fields:

```java
private final BoatAcquisitionPolicy boatAcquisitionPolicy = new BoatAcquisitionPolicy();
private Set<Integer> boatPlacementBaseline = Set.of();
private BlockPos pendingBoatPlacementSurface;
private BlockPos boatSurfaceApproachTarget;
private BlockPos lastBoatSurfaceApproachTarget;
private double lastBoatSurfaceVerticalDistance = Double.MAX_VALUE;
```

Refactor Task 5's repeated water-run clearing into these exact helpers, and call `leaveWaterRun()` both when `nextWaypoint(...)` exposes a non-`SWIM` action and when completed-step surface resolution fails:

```java
private void leaveWaterRun() {
    waterTransitPolicy.leaveWaterRun();
    currentWaterTravelDecision = WaterTransitPolicy.TravelDecision.SWIM;
    waterRunAnchor = null;
    waterRunAnchorSurfaceY = OptionalInt.empty();
    waterReplanAnchor = null;
    waterReplanContinuityPending = false;
    currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
    resetBoatAcquisition();
}

private void resetWaterTransit() {
    waterTransitPolicy.reset();
    currentWaterTravelDecision = WaterTransitPolicy.TravelDecision.SWIM;
    waterRunAnchor = null;
    waterRunAnchorSurfaceY = OptionalInt.empty();
    waterReplanAnchor = null;
    waterReplanContinuityPending = false;
    currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none();
    resetBoatAcquisition();
}

private void resetBoatAcquisition() {
    boatAcquisitionPolicy.resetForWaterRun();
    boatPlacementBaseline = Set.of();
    pendingBoatPlacementSurface = null;
    boatSurfaceApproachTarget = null;
    lastBoatSurfaceApproachTarget = null;
    lastBoatSurfaceVerticalDistance = Double.MAX_VALUE;
}
```

Replace Task 5's inline non-`SWIM`, unresolved-surface, and failed replan-continuity clearing with `leaveWaterRun()`. Keep assigning the new candidate anchor after a failed continuity check, so the next pool begins fresh.

Remove `BOAT_COOLDOWN_TICKS`, the `boatCooldown` field, its decrement/reset statements, `tryPlaceBoat()`, `tryBoardNearbyBoat()`, `bestBoatWaterPos()`, `isBoatWater()`, and the now-unused `isSurfaceWaterRoute()`. Add imports for `ArrayList`, `HashSet`, `Optional`, `OptionalInt`, and `net.minecraft.world.InteractionResult`.

- [ ] **Step 4: Split route-boat discovery, reachability, and interaction**

Replace the old combined discovery/interaction method with the helpers below. Discovery is independent of current vertical reach so a swimmer on a submerged route can rise toward a pre-existing surface boat. The broad phase performs one bounded entity query around the accepted evidence bounds, then the narrow phase requires the boat's resolved hull column to equal an actual `currentWaterEvidence.surfaces()` column. A boat in an adjacent same-height pool, across a wall, or merely inside the broad AABB cannot defer inventory placement. Only a reachable, visible candidate is offered to the interaction state machine:

```java
private record RouteBoatCandidate(int entityId, BlockPos approachSurface) {}

static boolean isAcceptedBoatCorridorColumn(
        BlockPos boatColumn,
        List<BlockPos> acceptedSurfaces
) {
    Objects.requireNonNull(boatColumn, "boatColumn");
    Objects.requireNonNull(acceptedSurfaces, "acceptedSurfaces");
    return acceptedSurfaces.contains(boatColumn);
}

private List<BlockPos> acceptedWaterCorridorSurfaces() {
    ArrayList<BlockPos> accepted = new ArrayList<>();
    if (waterRunAnchor != null && waterRunAnchorSurfaceY.isPresent()) {
        accepted.add(new BlockPos(
                waterRunAnchor.getX(),
                waterRunAnchorSurfaceY.getAsInt(),
                waterRunAnchor.getZ()
        ));
    }
    for (WaterRouteEvidenceAdapter.ResolvedSurface surface : currentWaterEvidence.surfaces()) {
        if (!accepted.contains(surface.waterPos())) {
            accepted.add(surface.waterPos());
        }
    }
    return List.copyOf(accepted);
}

private Optional<RouteBoatCandidate> findRouteAdjacentEligibleBoat(
        Minecraft client,
        LocalPlayer player
) {
    List<BlockPos> surfaces = acceptedWaterCorridorSurfaces();
    if (client.level == null || surfaces.isEmpty()) {
        return Optional.empty();
    }
    AABB searchBox = new AABB(surfaces.getFirst());
    for (int index = 1; index < surfaces.size(); index++) {
        searchBox = searchBox.minmax(new AABB(surfaces.get(index)));
    }
    AbstractBoat nearest = client.level.getEntitiesOfClass(
                    AbstractBoat.class,
                    searchBox.inflate(1.0, 2.0, 1.0),
                    boat -> acceptedApproachSurfaceForBoat(client, boat).isPresent()
            ).stream()
            .min((left, right) -> Double.compare(
                    left.distanceToSqr(player), right.distanceToSqr(player)))
            .orElse(null);
    if (nearest == null) {
        return Optional.empty();
    }
    return Optional.of(new RouteBoatCandidate(
            nearest.getId(), acceptedApproachSurfaceForBoat(client, nearest).orElseThrow()));
}

private Optional<BlockPos> acceptedApproachSurfaceForBoat(
        Minecraft client,
        int boatEntityId
) {
    if (client.level == null) {
        return Optional.empty();
    }
    Entity entity = client.level.getEntity(boatEntityId);
    return entity instanceof AbstractBoat boat
            ? acceptedApproachSurfaceForBoat(client, boat)
            : Optional.empty();
}

private Optional<BlockPos> acceptedApproachSurfaceForBoat(
        Minecraft client,
        AbstractBoat boat
) {
    if (!boat.isAlive()
            || !boat.getPassengers().isEmpty()
            || !shouldBoardBoat(boat.getId(), dismountRecovery::suppressBoarding)) {
        return Optional.empty();
    }
    OptionalInt runSurfaceY = waterTransitPolicy.surfaceY();
    OptionalInt boatSurfaceY = waterRouteEvidenceAdapter.resolveBoatSurfaceY(client, boat);
    if (runSurfaceY.isEmpty()
            || boatSurfaceY.isEmpty()
            || runSurfaceY.getAsInt() != boatSurfaceY.getAsInt()) {
        return Optional.empty();
    }
    BlockPos boatColumn = BlockPos.containing(
            boat.getX(), runSurfaceY.getAsInt(), boat.getZ());
    List<BlockPos> acceptedSurfaces = acceptedWaterCorridorSurfaces();
    return isAcceptedBoatCorridorColumn(boatColumn, acceptedSurfaces)
            ? Optional.of(boatColumn)
            : Optional.empty();
}

private OptionalInt reachableBoatFromCandidate(
        Minecraft client,
        LocalPlayer player,
        Optional<RouteBoatCandidate> candidate
) {
    if (client.level == null || candidate.isEmpty()) {
        return OptionalInt.empty();
    }
    Entity entity = client.level.getEntity(candidate.orElseThrow().entityId());
    if (!(entity instanceof AbstractBoat boat)
            || !boat.isAlive()
            || !boat.getPassengers().isEmpty()
            || !shouldBoardBoat(boat.getId(), dismountRecovery::suppressBoarding)
            || player.getEyePosition().distanceToSqr(boat.position())
                    > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS
            || !player.hasLineOfSight(boat)) {
        return OptionalInt.empty();
    }
    return OptionalInt.of(boat.getId());
}

private boolean interactWithBoat(Minecraft client, LocalPlayer player, int selectedBoatId) {
    if (client.level == null || client.gameMode == null) {
        return false;
    }
    Entity entity = client.level.getEntity(selectedBoatId);
    if (!(entity instanceof AbstractBoat boat)
            || !boat.isAlive()
            || !boat.getPassengers().isEmpty()
            || !shouldBoardBoat(boat.getId(), dismountRecovery::suppressBoarding)
            || player.getEyePosition().distanceToSqr(boat.position())
                    > BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS
            || !player.hasLineOfSight(boat)) {
        return false;
    }
    InteractionResult result = client.gameMode.interact(
            player, boat, new EntityHitResult(boat), InteractionHand.MAIN_HAND);
    if (result.consumesAction()) {
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }
    return false;
}
```

In `MovementControllerDismountTest`, replace the formatting-sensitive assertion for `shouldBoardBoat(...)` with:

```java
assertTrue(compact(source).contains(
        "shouldBoardBoat(boat.getId(),dismountRecovery::suppressBoarding)"));
```

- [ ] **Step 5: Place only at reachable evidence surfaces and confirm a new ID**

Add the route-only surface selector and the loaded-ID snapshot:

```java
private Optional<BlockPos> reachableBoatPlacementSurface(LocalPlayer player) {
    Vec3 eye = player.getEyePosition();
    return currentWaterEvidence.surfaces().stream()
            .map(WaterRouteEvidenceAdapter.ResolvedSurface::waterPos)
            .filter(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos).add(0.0, 0.25, 0.0))
                    <= BOAT_PLACE_REACH_BLOCKS * BOAT_PLACE_REACH_BLOCKS)
            .findFirst();
}

private Set<Integer> captureLoadedBoatIds(Minecraft client) {
    if (client.level == null) {
        return Set.of();
    }
    HashSet<Integer> ids = new HashSet<>();
    for (Entity entity : client.level.entitiesForRendering()) {
        if (entity instanceof AbstractBoat boat && boat.isAlive()) {
            ids.add(boat.getId());
        }
    }
    return Set.copyOf(ids);
}
```

Replace `useBoatItemAtWater(...)` with an `Optional<InteractionResult>` result. `Optional.empty()` means normal mode is still turning toward the placement ray and no policy event should be reported yet; an actual `PASS`, `FAIL`, or consuming result is present:

```java
private Optional<InteractionResult> useBoatItemAtWater(
        Minecraft client,
        LocalPlayer player,
        BlockPos waterPos,
        AutomationStyle style
) {
    if (client.gameMode == null) {
        return Optional.of(InteractionResult.FAIL);
    }
    Vec3 hit = Vec3.atCenterOf(waterPos).add(0.0, 0.25, 0.0);
    if (style != AutomationStyle.AGGRESSIVE) {
        float yawError = faceMovement(
                player, hit.x - player.getX(), hit.z - player.getZ());
        face(
                player,
                hit.x - player.getX(),
                hit.y - player.getEyeY(),
                hit.z - player.getZ(),
                true
        );
        if (yawError > SPRINT_ALIGNMENT_DEGREES) {
            return Optional.empty();
        }
        InteractionResult result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        return Optional.of(result);
    }

    float oldYaw = player.getYRot();
    float oldPitch = player.getXRot();
    float[] look = lookAngles(player, hit);
    InteractionResult result;
    try {
        sendServerLook(player, look[0], look[1]);
        player.setYRot(look[0]);
        player.setXRot(look[1]);
        result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    } finally {
        player.setYRot(oldYaw);
        player.setXRot(oldPitch);
    }
    return Optional.of(result);
}
```

Use this pure confirmation boundary:

```java
static boolean isNewPlacementBoat(int candidateId, Set<Integer> baseline, double distanceSquared) {
    Objects.requireNonNull(baseline, "baseline");
    return candidateId >= 0
            && !baseline.contains(candidateId)
            && distanceSquared <= 9.0;
}
```

Find the confirmed entity with:

```java
private OptionalInt findNewPlacementBoat(Minecraft client) {
    if (client.level == null || pendingBoatPlacementSurface == null) {
        return OptionalInt.empty();
    }
    Vec3 center = Vec3.atCenterOf(pendingBoatPlacementSurface);
    AbstractBoat nearest = client.level.getEntitiesOfClass(
                    AbstractBoat.class,
                    new AABB(pendingBoatPlacementSurface).inflate(3.0),
                    boat -> boat.isAlive()
                            && boat.getPassengers().isEmpty()
                            && shouldBoardBoat(
                                    boat.getId(), dismountRecovery::suppressBoarding)
                            && isNewPlacementBoat(
                                    boat.getId(),
                                    boatPlacementBaseline,
                                    boat.position().distanceToSqr(center)
                            )
            ).stream()
            .min((left, right) -> Double.compare(
                    left.position().distanceToSqr(center),
                    right.position().distanceToSqr(center)))
            .orElse(null);
    return nearest == null ? OptionalInt.empty() : OptionalInt.of(nearest.getId());
}

private boolean selectedBoatIsPresent(Minecraft client) {
    if (client.level == null || boatAcquisitionPolicy.selectedBoatId().isEmpty()) {
        return false;
    }
    int selectedBoatId = boatAcquisitionPolicy.selectedBoatId().getAsInt();
    Entity entity = client.level.getEntity(selectedBoatId);
    return entity instanceof AbstractBoat boat
            && boat.isAlive()
            && boat.getPassengers().isEmpty();
}
```

- [ ] **Step 6: Service policy actions without blocking swimming**

Add the surface-hold policy and synthetic surface swim. The synthetic step changes only the temporary swim target; it does not enter `pathSegments`, complete the submerged waypoint, change speed, or add packets:

```java
static boolean shouldHoldBoatAcquisitionSurface(
        BoatAcquisitionPolicy.Phase phase,
        boolean acquisitionResourceAvailable,
        boolean resolvedSurfaceAvailable
) {
    Objects.requireNonNull(phase, "phase");
    return resolvedSurfaceAvailable
            && phase != BoatAcquisitionPolicy.Phase.FALLBACK
            && (phase != BoatAcquisitionPolicy.Phase.IDLE || acquisitionResourceAvailable);
}

private MovementResult swimTowardBoatSurface(
        Minecraft client,
        LocalPlayer player,
        BlockPos surface
) {
    boatSurfaceApproachTarget = surface.immutable();
    return swimToward(
            client,
            player,
            new LocalPathPlanner.PathStep(
                    surface, LocalPathPlanner.StepAction.SWIM, null)
    );
}

static boolean madeBoatSurfaceVerticalProgress(
        BlockPos currentTarget,
        BlockPos previousTarget,
        double currentDistance,
        double previousDistance
) {
    return currentTarget != null
            && Objects.equals(currentTarget, previousTarget)
            && currentDistance < previousDistance - 0.01;
}
```

In the `ACQUIRE_BOAT` branch, call `acquireBoatOrSwim(...)`. Use this body so every transaction is a side action, deep routes rise toward their resolved surface, and placement/entity/passenger waits remain within interaction range:

```java
private MovementResult acquireBoatOrSwim(
        Minecraft client,
        LocalPlayer player,
        LocalPathPlanner.PathStep waypoint,
        AutomationStyle style
) {
    Optional<BlockPos> resolvedSurface = currentWaterEvidence.surfaces().stream()
            .map(WaterRouteEvidenceAdapter.ResolvedSurface::waterPos)
            .findFirst();
    Optional<BlockPos> placementSurface = reachableBoatPlacementSurface(player);
    boolean transactionAvailable = client.gameMode != null
            && client.gui.screen() == null
            && canSwapPlayerInventory(player);
    boolean heldBoat = player.getMainHandItem().getItem() instanceof BoatItem;
    boolean carriedBoat = findBoat(player) >= 0;
    Optional<RouteBoatCandidate> routeBoat = findRouteAdjacentEligibleBoat(client, player);
    OptionalInt nearbyBoat = transactionAvailable
            ? reachableBoatFromCandidate(client, player, routeBoat)
            : OptionalInt.empty();
    Optional<BlockPos> routeBoatSurface = routeBoat.map(RouteBoatCandidate::approachSurface);
    OptionalInt newBoat = findNewPlacementBoat(client);

    BoatAcquisitionPolicy.Decision decision = boatAcquisitionPolicy.tick(
            new BoatAcquisitionPolicy.Observation(
                    true,
                    transactionAvailable,
                    player.getVehicle() instanceof AbstractBoat,
                    heldBoat,
                    carriedBoat,
                    placementSurface.isPresent(),
                    routeBoat.isPresent(),
                    nearbyBoat,
                    newBoat,
                    selectedBoatIsPresent(client)
            )
    );

    if (boatAcquisitionPolicy.phase() == BoatAcquisitionPolicy.Phase.FALLBACK) {
        boatPlacementBaseline = Set.of();
        pendingBoatPlacementSurface = null;
    }

    switch (decision.action()) {
        case SELECT_CARRIED_BOAT -> {
            int slot = findBoat(player);
            if (slot >= 0) {
                selectOrMoveToHotbar(client, player, slot);
                boatAcquisitionPolicy.selectionRequested();
            }
        }
        case PLACE_HELD_BOAT -> {
            if (placementSurface.isPresent()) {
                Set<Integer> baseline = captureLoadedBoatIds(client);
                Optional<InteractionResult> attempt = useBoatItemAtWater(
                        client, player, placementSurface.orElseThrow(), style);
                if (attempt.isPresent()) {
                    InteractionResult placementResult = attempt.orElseThrow();
                    boolean accepted = placementResult.consumesAction();
                    boatAcquisitionPolicy.placementResult(accepted);
                    if (accepted) {
                        boatPlacementBaseline = baseline;
                        pendingBoatPlacementSurface = placementSurface.orElseThrow();
                    } else {
                        boatPlacementBaseline = Set.of();
                        pendingBoatPlacementSurface = null;
                    }
                }
            }
        }
        case BOARD_SELECTED_BOAT -> {
            int selectedBoatId = decision.boatEntityId().orElseThrow();
            boolean accepted = interactWithBoat(client, player, selectedBoatId);
            boatAcquisitionPolicy.boardingResult(selectedBoatId, accepted);
        }
        case NONE -> {
        }
    }

    if (player.getVehicle() instanceof AbstractBoat boat) {
        return driveBoatToward(client, player, boat, waypoint, style);
    }
    Optional<BlockPos> selectedBoatSurface = boatAcquisitionPolicy.selectedBoatId().isPresent()
            ? acceptedApproachSurfaceForBoat(
                    client, boatAcquisitionPolicy.selectedBoatId().getAsInt())
            : Optional.empty();
    Optional<BlockPos> holdSurface = selectedBoatSurface.isPresent()
            ? selectedBoatSurface
            : pendingBoatPlacementSurface != null
                    ? Optional.of(pendingBoatPlacementSurface)
                    : routeBoatSurface.isPresent() ? routeBoatSurface : resolvedSurface;
    boolean acquisitionResourceAvailable = heldBoat
            || carriedBoat
            || routeBoat.isPresent()
            || nearbyBoat.isPresent()
            || newBoat.isPresent();
    if (shouldHoldBoatAcquisitionSurface(
            boatAcquisitionPolicy.phase(),
            acquisitionResourceAvailable,
            holdSurface.isPresent()
    )) {
        return swimTowardBoatSurface(client, player, holdSurface.orElseThrow());
    }
    return swimToward(client, player, waypoint);
}
```

The selection action deliberately reports `selectionRequested()` after invoking `selectOrMoveToHotbar(...)` whenever `slot >= 0`. At this point `transactionAvailable` has already proved a game mode, closed GUI, and player inventory menu. The existing helper returns `false` after successfully dispatching a non-hotbar `ContainerInput.SWAP` because the held stack is not synchronously confirmed; that return value must not cause another swap packet. `AWAITING_HELD_ITEM` performs the authoritative held-item confirmation.

Complete `swimOrBoat` with this switch over the decision already computed by `nextWaypoint(...)`; do not call `refreshWaterEvidence(...)` here:

```java
return switch (currentWaterTravelDecision) {
    case SWIM -> {
        resetBoatAcquisition();
        yield swimToward(client, player, waypoint);
    }
    case ACQUIRE_BOAT -> acquireBoatOrSwim(client, player, waypoint, style);
    case CONTINUE_RIDING -> {
        resetBoatAcquisition();
        AbstractBoat boat = player.getVehicle() instanceof AbstractBoat currentBoat
                ? currentBoat
                : null;
        yield boat == null
                ? swimToward(client, player, waypoint)
                : driveBoatToward(client, player, boat, waypoint, style);
    }
};
```

At the start of `executeStep(...)`, replace the existing `if (!trackStep(waypoint)) {` opening line with this exact prefix; its existing body and closing brace stay in place:

```java
boolean continuingBoatSurfaceApproach = waypoint.action() == LocalPathPlanner.StepAction.SWIM
        && boatSurfaceApproachTarget != null;
boatSurfaceApproachTarget = null;
if (!trackStep(waypoint, continuingBoatSurfaceApproach)) {
```

The existing timeout/recovery body remains directly below that opening brace. Apply this focused diff to the only `trackStep(...)` method:

```diff
-private boolean trackStep(LocalPathPlanner.PathStep step) {
+private boolean trackStep(
+        LocalPathPlanner.PathStep step,
+        boolean suspendWaypointTimeout
+) {
@@
-    activeStepTicks++;
+    if (!suspendWaypointTimeout) {
+        activeStepTicks++;
+    }
@@
-    return activeStepTicks <= STUCK_TICKS_LIMIT * 2;
+    return suspendWaypointTimeout || activeStepTicks <= STUCK_TICKS_LIMIT * 2;
```

Keep its step-signature reset before the conditional increment. BREAK and PLACE always pass false because the caller flag is restricted to `SWIM`. This prevents a legitimate long vertical approach from exhausting the unrelated submerged waypoint's absolute timer; collision and `stuckTicks` recovery below remain active if ascent actually stops.

`swimTowardBoatSurface(...)` sets the per-tick target again only when acquisition still needs the surface. In `updateProgress(...)`, insert the following block immediately after the existing `madeProgress` declaration and before its `if (madeProgress)` branch:

```java
double boatSurfaceVerticalDistance = boatSurfaceApproachTarget == null
        ? Double.MAX_VALUE
        : Math.abs(boatSurfaceApproachTarget.getY() + 0.5 - player.getY());
boolean boatSurfaceProgress = madeBoatSurfaceVerticalProgress(
        boatSurfaceApproachTarget,
        lastBoatSurfaceApproachTarget,
        boatSurfaceVerticalDistance,
        lastBoatSurfaceVerticalDistance
);
boolean surfaceApproachActive = boatSurfaceApproachTarget != null;
madeProgress = madeProgress || boatSurfaceProgress;
lastBoatSurfaceApproachTarget = boatSurfaceApproachTarget;
lastBoatSurfaceVerticalDistance = boatSurfaceVerticalDistance;
```

Replace the movement-sample branch and replan condition with:

```java
if (movementAction && !surfaceApproachActive) {
    recordMovementSample(playerPos);
} else {
    movementSamples.clear();
}

if (horizontalCollisionTicks >= COLLISION_REPLAN_TICKS
        || stuckTicks >= STUCK_TICKS_LIMIT
        || (!surfaceApproachActive
                && movementAction
                && isTrappedInRecentArea(LOCAL_STALL_TICKS, LOCAL_STALL_AREA_BLOCKS))
        || (!surfaceApproachActive
                && movementAction
                && isTrappedInRecentArea(LOOP_STALL_TICKS, LOOP_STALL_AREA_BLOCKS))) {
    if (movementAction) {
        movementRecoveryFailures++;
    }
    forceLocalReplan();
}
```

`stuckTicks` and collision checks remain active, so a failed ascent still replans, while a 12-block-deep successful ascent no longer trips the 90-tick horizontal-stall detector. A GUI makes `transactionAvailable=false`, pauses policy timers, and continues the same safe surface swim. `PLACEMENT_BACKOFF` preserves the old 40-tick minimum between rejected use packets without recreating the old false-success cooldown.

- [ ] **Step 7: Run acquisition, dismount, and movement regressions**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.core.BoatAcquisitionPolicyTest" --tests "dev.mappywall.core.BoatDismountRecoveryTest" --tests "dev.mappywall.client.MovementControllerWaterTransitTest" --tests "dev.mappywall.client.MovementControllerDismountTest" --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --rerun-tasks
```

Expected: all tests pass; source contracts show no cooldown-only placement path.

- [ ] **Step 8: Commit confirmed acquisition**

```powershell
git add -- src/client/java/dev/mappywall/client/MovementController.java src/test/java/dev/mappywall/client/MovementControllerWaterTransitTest.java
git commit -m "Confirm boat placement and boarding"
```

### Task 7: Expose the threshold in navigation settings

**Files:**
- Create: `src/client/java/dev/mappywall/client/NavigationSettingsInput.java`
- Create: `src/test/java/dev/mappywall/client/NavigationSettingsInputTest.java`
- Modify: `src/client/java/dev/mappywall/client/NavigationSettingsScreen.java`
- Modify: `src/client/java/dev/mappywall/client/MappyWallRuntime.java`
- Modify: `src/client/resources/assets/mappywall/lang/en_us.json`
- Modify: `src/client/resources/assets/mappywall/lang/zh_cn.json`

**Interfaces:**
- Consumes: config/store APIs from Task 2.
- Produces: `NavigationSettingsInput.parseMinimumBoatDistance(String)`, an editable threshold field, immediate controller refresh, and bilingual copy.

- [ ] **Step 1: Write a failing parser test**

```java
package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NavigationSettingsInputTest {
    @Test
    void acceptsOnlyTrimmedIntegersFromOneThroughOneHundredTwentyEight() {
        assertEquals(12, NavigationSettingsInput.parseMinimumBoatDistance(" 12 ").orElseThrow());
        assertEquals(1, NavigationSettingsInput.parseMinimumBoatDistance("1").orElseThrow());
        assertEquals(128, NavigationSettingsInput.parseMinimumBoatDistance("128").orElseThrow());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("0").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("129").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("12.5").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("boat").isEmpty());
    }

    @Test
    void validatedFieldFlowsThroughRuntimeStoreAndImmediateControllerRefresh()
            throws IOException {
        String screen = Files.readString(Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "NavigationSettingsScreen.java"));
        String runtime = Files.readString(Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "MappyWallRuntime.java"));
        String compactScreen = screen.replaceAll("\\s+", "");
        String compactRuntime = runtime.replaceAll("\\s+", "");
        assertTrue(compactScreen.contains("NavigationSettingsInput.parseMinimumBoatDistance("));
        assertTrue(compactScreen.contains("runtime.updateAggressiveNavigationConfig("));
        assertTrue(compactScreen.contains("parsedDistance.getAsInt()"));
        assertTrue(compactRuntime.contains("navigationConfigStore.updateNavigationSettings("));
        assertTrue(compactRuntime.contains(
                "movementController.setAggressiveConfig(navigationConfigStore.aggressiveConfig())"));
        assertTrue(compactRuntime.contains("navigationConfigStore.resetNavigationDefaults()"));
    }
}
```

- [ ] **Step 2: Run the parser test and confirm RED**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationSettingsInputTest" --rerun-tasks
```

Expected: compilation fails because `NavigationSettingsInput` does not exist.

- [ ] **Step 3: Implement the pure parser**

```java
package dev.mappywall.client;

import dev.mappywall.core.WaterTransitPolicy;
import java.util.OptionalInt;

final class NavigationSettingsInput {
    private NavigationSettingsInput() {}

    static OptionalInt parseMinimumBoatDistance(String value) {
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= WaterTransitPolicy.MINIMUM_BOAT_DISTANCE_BLOCKS
                            && parsed <= WaterTransitPolicy.MAXIMUM_BOAT_DISTANCE_BLOCKS
                    ? OptionalInt.of(parsed)
                    : OptionalInt.empty();
        } catch (NumberFormatException invalid) {
            return OptionalInt.empty();
        }
    }
}
```

- [ ] **Step 4: Add the field and atomic save/reset wiring**

Add these screen fields and populate the integer in `load(...)`:

```java
private int minimumBoatDistanceBlocks;
private EditBox boatDistanceField;

private void load(AutoNavigationConfig config) {
    breakingEnabled = config.blockBreakingEnabled();
    listMode = config.breakListMode();
    blockIds = config.breakBlocks();
    minimumBoatDistanceBlocks = config.minimumBoatDistanceBlocks();
}
```

Use `int y = Math.max(28, this.height / 2 - 105);` in both `init()` and `extractRenderState(...)`. After adding `blockListField`, create the threshold field and move the three bottom buttons from `y + 72` to `y + 96`:

```java
boatDistanceField = new EditBox(
        this.font,
        left + 252,
        y + 64,
        68,
        20,
        Component.translatable("screen.mappywall.navigation.boat_distance")
);
boatDistanceField.setMaxLength(3);
boatDistanceField.setValue(Integer.toString(minimumBoatDistanceBlocks));
boatDistanceField.setTooltip(Tooltip.create(Component.translatable(
        "screen.mappywall.navigation.boat_distance_tooltip")));
addRenderableWidget(boatDistanceField);
```

Render its label at `left, y + 69`, move `priority_note` to `y + 126`, and move `status` to `y + 140`:

```java
graphics.text(
        this.font,
        Component.translatable("screen.mappywall.navigation.boat_distance"),
        this.width / 2 - 160,
        y + 69,
        0xFFFFFFFF,
        true
);
```

Replace the Save callback body with:

```java
OptionalInt parsedDistance = NavigationSettingsInput.parseMinimumBoatDistance(
        boatDistanceField.getValue());
if (parsedDistance.isEmpty()) {
    status = Component.translatable("screen.mappywall.navigation.boat_distance_invalid")
            .withStyle(ChatFormatting.RED);
    return;
}
Set<String> parsed = parseIds(blockListField.getValue());
if (runtime.updateAggressiveNavigationConfig(
        breakingEnabled, listMode, parsed, parsedDistance.getAsInt())) {
    blockIds = parsed;
    minimumBoatDistanceBlocks = parsedDistance.getAsInt();
    status = Component.translatable("screen.mappywall.navigation.saved")
            .withStyle(ChatFormatting.GREEN);
} else {
    status = Component.translatable("screen.mappywall.navigation.save_failed")
            .withStyle(ChatFormatting.RED);
}
```

Rename the Reset callback to `runtime.resetAggressiveNavigationConfig()`. Its existing `load(...)`, `clearWidgets()`, and `init()` sequence will repopulate both fields.

Replace the two runtime methods with:

```java
boolean updateAggressiveNavigationConfig(
        boolean enabled,
        AutoNavigationConfig.ListMode listMode,
        Set<String> blockIds,
        int minimumBoatDistanceBlocks
) {
    try {
        navigationConfigStore.updateNavigationSettings(
                enabled, listMode, blockIds, minimumBoatDistanceBlocks);
        movementController.setAggressiveConfig(navigationConfigStore.aggressiveConfig());
        return true;
    } catch (IOException exception) {
        return false;
    }
}

boolean resetAggressiveNavigationConfig() {
    try {
        navigationConfigStore.resetNavigationDefaults();
        movementController.setAggressiveConfig(navigationConfigStore.aggressiveConfig());
        return true;
    } catch (IOException exception) {
        return false;
    }
}
```

Import `OptionalInt` in the screen. Minecraft 26.2 `EditBox` has no `setFilter`; validation occurs only in the Save callback.

- [ ] **Step 5: Add exact bilingual resource keys and generalize screen copy**

Add:

```json
"screen.mappywall.navigation.boat_distance": "Minimum boat crossing (blocks)",
"screen.mappywall.navigation.boat_distance_tooltip": "Board or place a boat only when the confirmed continuous water route reaches this distance (1-128, default 12).",
"screen.mappywall.navigation.boat_distance_invalid": "Enter a whole number from 1 through 128."
```

and:

```json
"screen.mappywall.navigation.boat_distance": "最短乘船水程（方块）",
"screen.mappywall.navigation.boat_distance_tooltip": "仅当已确认的连续水路达到该距离时上船或放船（1–128，默认 12）。",
"screen.mappywall.navigation.boat_distance_invalid": "请输入 1 到 128 的整数。"
```

Use these exact replacements for the existing generalized keys:

```json
"screen.mappywall.navigation.open": "Navigation settings",
"screen.mappywall.navigation.title": "Aggressive navigation",
"screen.mappywall.navigation.saved": "Navigation settings saved and applied",
"screen.mappywall.navigation.reset_done": "Restored default navigation settings"
```

```json
"screen.mappywall.navigation.open": "寻路设置",
"screen.mappywall.navigation.title": "激进模式寻路设置",
"screen.mappywall.navigation.saved": "寻路设置已保存并立即生效",
"screen.mappywall.navigation.reset_done": "已恢复默认寻路设置"
```

- [ ] **Step 6: Validate parser, config, JSON, and screen compilation**

Run separately:

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationSettingsInputTest" --tests "dev.mappywall.client.NavigationConfigStoreTest" --rerun-tasks
```

```powershell
$null = Get-Content src/client/resources/assets/mappywall/lang/zh_cn.json -Raw | ConvertFrom-Json
$null = Get-Content src/client/resources/assets/mappywall/lang/en_us.json -Raw | ConvertFrom-Json
```

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat classes --rerun-tasks
```

Expected: tests pass, both JSON commands are silent, and client classes compile.

- [ ] **Step 7: Commit settings UI support**

```powershell
git add -- src/client/java/dev/mappywall/client/NavigationSettingsInput.java src/client/java/dev/mappywall/client/NavigationSettingsScreen.java src/client/java/dev/mappywall/client/MappyWallRuntime.java src/client/resources/assets/mappywall/lang/en_us.json src/client/resources/assets/mappywall/lang/zh_cn.json src/test/java/dev/mappywall/client/NavigationSettingsInputTest.java
git commit -m "Expose minimum boat crossing setting"
```

### Task 8: Document, verify, and manually accept water transit

**Files:**
- Modify: `docs/design.md`

**Interfaces:**
- Consumes: all completed water-transit tasks.
- Produces: current design notes, complete automated evidence, and a manual acceptance checklist.

- [ ] **Step 1: Add the finalized water-transit invariant to `docs/design.md`**

Insert these exact bullets in the automatic-navigation section:

```markdown
- Boat acquisition is a long-water optimization, not the default for every `SWIM` step. A nearby empty boat and a carried boat are both ignored until the accepted continuous boatable route reaches the configured minimum (default 12 blocks, range 1–128).
- Confirmed water distance is the completed compatible prefix plus the active segment and an accepted `CONTINUOUS` preview. Pending, stale, hidden-retreat, unloaded, obstructed, and guessed cells do not count; the completed prefix survives only ordinary same-target replans.
- Submerged `SWIM` cells are resolved on the client thread to the loaded top of their water column. Mounted completion and dismount decisions use that resolved surface, while unmounted swimmers retain physical-Y completion checks.
- Boat placement and boarding are non-blocking side actions with fixed retry bounds. A compatible route-adjacent existing boat is approached before inventory placement; a consuming item interaction is followed by confirmation of a new route-adjacent boat entity ID, and boarding is complete only after passenger state changes. Rejection, timeout, an open GUI, or missing inventory never stops swimming.
```

- [ ] **Step 2: Run whitespace and focused tests**

```powershell
git diff --check
```

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.core.WaterTransitPolicyTest" --tests "dev.mappywall.core.BoatAcquisitionPolicyTest" --tests "dev.mappywall.core.BoatDismountRecoveryTest" --tests "dev.mappywall.core.PathSegmentCoordinatorTest" --tests "dev.mappywall.client.WaterRouteEvidenceAdapterTest" --tests "dev.mappywall.client.NavigationConfigStoreTest" --tests "dev.mappywall.client.NavigationSettingsInputTest" --tests "dev.mappywall.client.MovementControllerWaterTransitTest" --tests "dev.mappywall.client.MovementControllerDismountTest" --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --tests "dev.mappywall.core.NavigationContinuityScenarioTest" --rerun-tasks
```

Expected: diff check is silent and every selected test passes.

- [ ] **Step 3: Run the full suite and clean build**

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --rerun-tasks
```

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat clean build --rerun-tasks
```

Expected: both commands end in `BUILD SUCCESSFUL`; the jar is `build/libs/mappywall-0.1.31.jar`.

- [ ] **Step 4: Inspect the jar boundary**

```powershell
jar tf build/libs/mappywall-0.1.31.jar | Select-String "fabric.mod.json|WaterTransitPolicy|BoatAcquisitionPolicy|WaterRouteEvidenceAdapter"
```

Expected: the client mod metadata and new classes are present; no server initializer or custom server packet class is introduced.

- [ ] **Step 5: Commit documentation**

```powershell
git add -- docs/design.md
git commit -m "Document confirmed water transit"
```

- [ ] **Step 6: Perform manual acceptance before completion**

In aggressive mode with a regular boat available:

1. Cross 3- and 11-block water runs: remain swimming and ignore nearby empty boats.
2. Cross exactly 12 and more than 12 blocks: prefer an eligible existing empty boat, otherwise place and board one carried boat.
3. Start with only 8 confirmed blocks, swim 4, then let rolling lookahead add 4 more: acquisition begins once completed plus future evidence reaches 12.
4. Enter a deep lake with no carried boat but an empty compatible boat above the accepted corridor: rise continuously toward that boat, do not place another boat, and board it once reachable.
5. Traverse a deep lake whose path cells are below the surface: remain mounted, complete waypoints, and dismount only at the real boat-to-land seam.
6. Test an unloaded frontier, covered water, changing water level, open inventory, rejected placement, and delayed entity spawn: swimming continues and retries remain bounded.
7. Set aggressive distance to 37, latch a 12-block run in normal style, then switch styles in both directions: each switch replans and evaluates only the destination style's threshold.
8. Reach shore and confirm the existing server-confirmed dismount recovery neither reboards the original boat nor enters a hop/replan loop.

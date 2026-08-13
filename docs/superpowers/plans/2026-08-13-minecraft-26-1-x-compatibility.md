# Minecraft 26.1.x Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one MappyWall `0.1.33+mc26.1-26.1.2` release candidate JAR against Minecraft 26.1 while proving the same source builds against 26.1, 26.1.1, and 26.1.2.

**Architecture:** Keep one production source tree and replace the known 26.2-only client seams with direct 26.1.x APIs or version-neutral coordinate math. Gradle owns an immutable target-to-dependency map selected by `minecraft_target`; the default and release artifact use the oldest target, while later targets are diagnostics. A Gradle verification task inspects the remapped JAR, and the user's real-client smoke tests make the final single-JAR decision.

**Tech Stack:** Java 25, Gradle 9.6.0 wrapper, Fabric Loom 1.17.13, Fabric Loader 0.19.3, Fabric API, official unobfuscated Minecraft 26.1.x names, JUnit 5.14, Gson 2.13.2.

## Global Constraints

- Support exactly Minecraft `26.1`, `26.1.1`, and `26.1.2`; metadata must declare `>=26.1 <=26.1.2` and reject 26.2.
- Use Fabric API `0.145.1+26.1`, `0.145.4+26.1.1`, and `0.155.2+26.1.2` for their matching targets.
- Use Java `25`, Fabric Loader `0.19.3`, and Fabric Loom `1.17.13` for every target.
- The shared release candidate is compiled against Minecraft `26.1` and named `mappywall-0.1.33+mc26.1-26.1.2.jar`.
- Preserve client-only and vanilla-server compatibility; add no server initializer, custom protocol, custom packet, block, item, or entity.
- Preserve persistence formats, navigation policies, movement constants, user-facing automation toggles, and all client-thread world/entity access.
- Do not use reflection, runtime bytecode probing, optional class loading, or conditional Mixins to force a single JAR.
- Treat dependency-download failures as environment failures, not evidence that multiple JARs are required.
- If real compatibility evidence forces the fallback, the three artifacts are exactly `mappywall-0.1.33+mc26.1.jar`, `mappywall-0.1.33+mc26.1.1.jar`, and `mappywall-0.1.33+mc26.1.2.jar`; implement that fallback only through a separately approved plan.
- Do not claim any real-client or navigation scenario passed until the user reports actually running it.
- Preserve the user's untracked `.codex/` directory and all unrelated worktree changes.

## File Structure

- `src/test/java/dev/mappywall/client/MinecraftCompatibilityContractTest.java`: real block-center behavior and processed release-metadata contract for this migration.
- `gradle.properties`: default target, loader/toolchain values, and release version only; per-target game/API coordinates move out in Task 2.
- `build.gradle`: dependency target map, target validation, status output, and remapped-JAR verification.
- `src/main/resources/fabric.mod.json`: exact three-release Minecraft dependency range.
- `src/client/java/dev/mappywall/client/MappyWallRuntime.java`: 26.1.x screen transition calls.
- `src/client/java/dev/mappywall/client/MapOpenController.java`: 26.1.x current-screen state access.
- `src/client/java/dev/mappywall/client/NavigationSettingsScreen.java`: 26.1.x return-to-parent screen call.
- `src/client/java/dev/mappywall/client/MovementController.java`: version-neutral block-center calculation used by movement and interaction code.
- `src/client/java/dev/mappywall/client/WorldTargetRenderer.java`: Fabric 26.1.x level-render context path.
- `src/test/java/dev/mappywall/client/MovementControllerWaterTransitTest.java`: preserves the existing boat GUI-deferral source contract using the 26.1.x current-screen expression.
- `AGENTS.md`: authoritative repository target and matrix commands.
- `docs/build-notes.md`: developer build target table, matrix commands, and single-JAR/manual-test boundary.
- `docs/design.md`: current supported versions and renderer API statement.

---

### Task 1: Restore a green Minecraft 26.1 release build

**Files:**
- Create: `src/test/java/dev/mappywall/client/MinecraftCompatibilityContractTest.java`
- Modify: `gradle.properties:4-11`
- Modify: `src/main/resources/fabric.mod.json:29-34`
- Modify: `src/client/java/dev/mappywall/client/MappyWallRuntime.java:105-118`
- Modify: `src/client/java/dev/mappywall/client/NavigationSettingsScreen.java:150-153`
- Modify: `src/client/java/dev/mappywall/client/MovementController.java:1340-1346,1382-1389,1430-1437,1846-1867,2077-2085,3216-3230`
- Modify: `src/client/java/dev/mappywall/client/WorldTargetRenderer.java:33-134`

**Interfaces:**
- Consumes: the current 26.2 implementation at design base `ab86153` and the historical 26.1.2 renderer at commit `f4267fd`.
- Produces: a default Minecraft 26.1 build, metadata range `>=26.1 <=26.1.2`, version `0.1.33+mc26.1-26.1.2`, and `MovementController.blockCenter(BlockPos): Vec3`.

- [ ] **Step 1: Add the failing behavior and processed-metadata contract**

Create `MinecraftCompatibilityContractTest.java`. The geometry test exercises
the wished-for production helper directly. The metadata test reads the
processed classpath resource produced by Gradle, not the source file.

```java
package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class MinecraftCompatibilityContractTest {
    @Test
    void blockCenterPreservesHalfBlockOffsetsForNegativeCoordinates() {
        Vec3 center = MovementController.blockCenter(new BlockPos(-2, 63, 4));

        assertEquals(-1.5, center.x);
        assertEquals(63.5, center.y);
        assertEquals(4.5, center.z);
    }

    @Test
    void processedMetadataBoundsTheSharedClientRelease() throws IOException {
        try (InputStream input = MinecraftCompatibilityContractTest.class
                .getClassLoader()
                .getResourceAsStream("fabric.mod.json")) {
            assertNotNull(input);
            JsonObject metadata = JsonParser.parseReader(new InputStreamReader(
                    input, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject depends = metadata.getAsJsonObject("depends");

            assertEquals("0.1.33+mc26.1-26.1.2", metadata.get("version").getAsString());
            assertEquals(">=26.1 <=26.1.2", depends.get("minecraft").getAsString());
            assertEquals(">=0.19.3", depends.get("fabricloader").getAsString());
            assertEquals(">=25", depends.get("java").getAsString());
            assertEquals("client", metadata.get("environment").getAsString());
            assertTrue(metadata.getAsJsonObject("entrypoints").has("client"));
            assertFalse(metadata.getAsJsonObject("entrypoints").has("main"));
            assertFalse(metadata.getAsJsonObject("entrypoints").has("server"));
        }
    }
}
```

- [ ] **Step 2: Run the contract and verify RED**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  --tests dev.mappywall.client.MinecraftCompatibilityContractTest \
  --no-daemon --rerun-tasks
```

Expected: FAIL at test compilation because the wished-for
`MovementController.blockCenter(BlockPos)` behavior does not exist yet. This
is the missing production API under test, not a spelling or fixture error.

Also run the real 26.1 client compilation before changing production code:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew compileClientJava \
  -Pminecraft_version=26.1 -Pfabric_version=0.145.1+26.1 \
  --no-daemon --rerun-tasks
```

Expected RED evidence: compilation fails on the known 26.2-only screen,
block-center, or level-render APIs. Preserve the exact compiler diagnostics in
the Task 1 report.

- [ ] **Step 3: Move the default dependency and release metadata to 26.1**

Replace the version block in `gradle.properties` with:

```properties
minecraft_version=26.1
loader_version=0.19.3
fabric_version=0.145.1+26.1

mod_version=0.1.33+mc26.1-26.1.2
maven_group=dev.mappywall
archives_base_name=mappywall
java_version=25
```

Change only the Minecraft dependency in `fabric.mod.json`:

```json
"minecraft": ">=26.1 <=26.1.2"
```

- [ ] **Step 4: Replace the 26.2 screen methods**

Use the 26.1.x `Minecraft.setScreen(Screen)` API without changing the callers or screen lifecycle:

```java
public void openConfigScreen(Minecraft client) {
    if (hasUsableWorld(client)) {
        auditCrossProjectMapIds(client, true);
    }
    client.setScreen(new MapWallTasksScreen(this));
}

public void openNewProjectScreen(Minecraft client) {
    client.setScreen(new MapWallConfigScreen(this));
}

public void openNavigationSettingsScreen(Minecraft client, Screen parent) {
    client.setScreen(new NavigationSettingsScreen(this, parent));
}
```

Use the same API when closing navigation settings:

```java
@Override
public void onClose() {
    Minecraft.getInstance().setScreen(parent);
}
```

Real 26.1 compilation additionally reports that `Gui.screen()` does not exist.
For the existing GUI-open guards in `MappyWallRuntime`, `MapOpenController`,
and `MovementController`, use the 26.1.x `Minecraft.screen` field while
preserving every null comparison and surrounding condition exactly. Update the
existing boat GUI-deferral source contract to assert the equivalent
`client.screen != null` expression.

- [ ] **Step 5: Replace block-center helpers with version-neutral math**

Add this package-visible helper near the other static geometry helpers in `MovementController`:

```java
static Vec3 blockCenter(BlockPos pos) {
    return new Vec3(
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5
    );
}
```

Replace every `Vec3.atCenterOf(pos)` call in `MovementController` with `blockCenter(pos)`, preserving each existing `.add(...)`, distance comparison, hit result, and server-look call. The seven direct expressions must become:

```java
blockCenter(pos).add(0.0, 0.25, 0.0)
Vec3 center = blockCenter(pendingBoatPlacementSurface);
boat.position().distanceToSqr(blockCenter(pendingBoatPlacementSurface))
player.getEyePosition().distanceToSqr(blockCenter(block))
sendServerLookAt(player, blockCenter(block));
Vec3 hit = blockCenter(waterPos).add(0.0, 0.25, 0.0);
new BlockHitResult(blockCenter(below).add(0.0, 0.5, 0.0), Direction.UP, below, false)
```

The horizontal-neighbor placement expression must use the same helper before its face offset:

```java
Vec3 hit = blockCenter(neighbor).add(
        face.getStepX() * 0.5,
        face.getStepY() * 0.5,
        face.getStepZ() * 0.5
);
```

- [ ] **Step 6: Restore the Fabric 26.1.x rendering path**

Replace the renderer methods from `renderTarget` through `line` with the 26.1.x render-context flow. Keep colors, offsets, path order, and line width unchanged:

```java
private static void renderTarget(
        LevelRenderContext context,
        Minecraft client,
        MappyWallRuntime.RenderTarget target
) {
    PoseStack matrices = context.poseStack();
    VertexConsumer vertices = context.bufferSource().getBuffer(RenderTypes.linesTranslucent());
    Vec3 camera = context.gameRenderer().getMainCamera().position();

    double playerY = client.player.getY();
    double centerX = target.targetX() + 0.5;
    double centerZ = target.targetZ() + 0.5;

    renderWaypointBeam(matrices, vertices, camera, playerY, centerX, centerZ);
    if (target.showPath()) {
        renderPath(matrices, vertices, camera, client, target);
    }
}

private static void renderWaypointBeam(
        PoseStack matrices,
        VertexConsumer vertices,
        Vec3 camera,
        double playerY,
        double targetX,
        double targetZ
) {
    double dx = targetX - camera.x;
    double dz = targetZ - camera.z;
    double distance = Math.sqrt(dx * dx + dz * dz);
    double markerX = targetX;
    double markerZ = targetZ;
    if (distance > FAR_MARKER_DISTANCE) {
        markerX = camera.x + dx / distance * FAR_MARKER_DISTANCE;
        markerZ = camera.z + dz / distance * FAR_MARKER_DISTANCE;
    }

    double bottomY = playerY + BEAM_BOTTOM_OFFSET;
    double topY = playerY + BEAM_TOP_OFFSET;
    double centerY = playerY + 8.0;
    line(matrices, vertices, camera, markerX, bottomY, markerZ,
            markerX, topY, markerZ, 64, 224, 255, 255);
    line(matrices, vertices, camera, markerX - 3.0, centerY, markerZ,
            markerX + 3.0, centerY, markerZ, 64, 224, 255, 255);
    line(matrices, vertices, camera, markerX, centerY, markerZ - 3.0,
            markerX, centerY, markerZ + 3.0, 64, 224, 255, 255);
}

private static void renderPath(
        PoseStack matrices,
        VertexConsumer vertices,
        Vec3 camera,
        Minecraft client,
        MappyWallRuntime.RenderTarget target
) {
    double previousX = client.player.getX();
    double previousY = client.player.getY() + 0.25;
    double previousZ = client.player.getZ();
    for (BlockPos pos : target.path()) {
        double nextX = pos.getX() + 0.5;
        double nextY = pos.getY() + 0.25;
        double nextZ = pos.getZ() + 0.5;
        line(matrices, vertices, camera, previousX, previousY, previousZ,
                nextX, nextY, nextZ, 255, 216, 72, 255);
        previousX = nextX;
        previousY = nextY;
        previousZ = nextZ;
    }
}

private static void line(
        PoseStack matrices,
        VertexConsumer vertices,
        Vec3 camera,
        double startX,
        double startY,
        double startZ,
        double endX,
        double endY,
        double endZ,
        int red,
        int green,
        int blue,
        int alpha
) {
    double normalX = endX - startX;
    double normalY = endY - startY;
    double normalZ = endZ - startZ;
    double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
    if (length <= 0.0001) {
        return;
    }

    float nx = (float) (normalX / length);
    float ny = (float) (normalY / length);
    float nz = (float) (normalZ / length);
    PoseStack.Pose pose = matrices.last();
    vertices.addVertex(pose,
                    (float) (startX - camera.x),
                    (float) (startY - camera.y),
                    (float) (startZ - camera.z))
            .setColor(red, green, blue, alpha)
            .setLineWidth(2.5F)
            .setNormal(pose, nx, ny, nz);
    vertices.addVertex(pose,
                    (float) (endX - camera.x),
                    (float) (endY - camera.y),
                    (float) (endZ - camera.z))
            .setColor(red, green, blue, alpha)
            .setLineWidth(2.5F)
            .setNormal(pose, nx, ny, nz);
}
```

- [ ] **Step 7: Run the focused contract against 26.1 and verify GREEN**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  --tests dev.mappywall.client.MinecraftCompatibilityContractTest \
  --no-daemon --rerun-tasks
```

Expected: PASS and the client source compiles against Minecraft 26.1. The
observed additional compiler diagnostic was 14 missing `Gui.screen()` symbols;
Step 4 records the verified `Minecraft.screen` replacement. If compilation
exposes another missing 26.1 API, stop this task, invoke
`superpowers:systematic-debugging`, record the exact compiler symbol, and amend
this plan before changing another production seam.

- [ ] **Step 8: Run the complete 26.1 regression suite**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  --no-daemon --rerun-tasks
```

Expected: PASS with all existing navigation, persistence, client-policy, and
integration-contract behavior unchanged apart from the new compatibility
contract and the existing source contract's equivalent 26.1.x screen
expression.

- [ ] **Step 9: Inspect and commit Task 1**

Run `git diff --check`, inspect `git diff`, then commit only the files listed by this task:

```bash
git add -- gradle.properties src/main/resources/fabric.mod.json \
  src/client/java/dev/mappywall/client/MapOpenController.java \
  src/client/java/dev/mappywall/client/MappyWallRuntime.java \
  src/client/java/dev/mappywall/client/NavigationSettingsScreen.java \
  src/client/java/dev/mappywall/client/MovementController.java \
  src/client/java/dev/mappywall/client/WorldTargetRenderer.java \
  src/test/java/dev/mappywall/client/MovementControllerWaterTransitTest.java \
  src/test/java/dev/mappywall/client/MinecraftCompatibilityContractTest.java
git commit -m "Target Minecraft 26.1 compatibility"
```

- [ ] **Step 10: Independently review Task 1**

Give a fresh reviewer `git diff HEAD^..HEAD`, the Task 1 brief, the RED/GREEN output, and the global constraints. Require confirmation that only compatibility seams changed, `blockCenter` preserves exact center coordinates, renderer behavior is unchanged, metadata excludes 26.2, and no movement constant or client-only boundary changed. Fix each valid finding and repeat the focused/full tests before proceeding.

---

### Task 2: Add the guarded three-version build matrix

**Files:**
- Modify: `build.gradle:1-92`
- Modify: `gradle.properties:4-11`

**Interfaces:**
- Consumes: Task 1's green default Minecraft 26.1 build and exact dependency coordinates from the approved design.
- Produces: Gradle property `minecraft_target`, immutable map `supportedMinecraftTargets`, resolved maps with keys `minecraftVersion` and `fabricApiVersion`, and fail-fast validation for unsupported targets.

- [ ] **Step 1: Verify target selection and rejection are missing (RED)**

Run these real Gradle behavior checks before changing `build.gradle`:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew printFabricStatus \
  -PenableFabric=false -Pminecraft_target=26.1.1 --no-daemon
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew help \
  -PenableFabric=false -Pminecraft_target=26.2 --no-daemon
```

Expected RED evidence: the first command incorrectly reports the fixed 26.1
properties, and the second incorrectly succeeds instead of rejecting 26.2.

- [ ] **Step 2: Implement the immutable target map and validation**

Insert this after the plugin block and before assigning `version`:

```groovy
def supportedMinecraftTargets = [
        "26.1"  : [
                minecraftVersion: "26.1",
                fabricApiVersion: "0.145.1+26.1"
        ].asImmutable(),
        "26.1.1": [
                minecraftVersion: "26.1.1",
                fabricApiVersion: "0.145.4+26.1.1"
        ].asImmutable(),
        "26.1.2": [
                minecraftVersion: "26.1.2",
                fabricApiVersion: "0.155.2+26.1.2"
        ].asImmutable()
].asImmutable()

def minecraftTarget = providers.gradleProperty("minecraft_target")
        .orElse("26.1")
        .get()
def minecraftCoordinates = supportedMinecraftTargets[minecraftTarget]
if (minecraftCoordinates == null) {
    throw new GradleException(
            "Unsupported minecraft_target '${minecraftTarget}'. "
                    + "Supported targets: 26.1, 26.1.1, 26.1.2."
    )
}
```

Replace the Minecraft and Fabric API dependency coordinates with:

```groovy
minecraft "com.mojang:minecraft:${minecraftCoordinates.minecraftVersion}"
implementation "net.fabricmc:fabric-loader:${project.loader_version}"
implementation "net.fabricmc.fabric-api:fabric-api:${minecraftCoordinates.fabricApiVersion}"
```

Replace `printFabricStatus`'s first output line with:

```groovy
println("Fabric build target: ${minecraftTarget}; Minecraft "
        + "${minecraftCoordinates.minecraftVersion}, official mappings, Loader "
        + "${project.loader_version}, Fabric API "
        + "${minecraftCoordinates.fabricApiVersion}.")
```

Replace the dependency properties in `gradle.properties` with only the stable selector:

```properties
minecraft_target=26.1
loader_version=0.19.3
```

- [ ] **Step 3: Verify target selection and rejection without downloading game artifacts**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew printFabricStatus \
  -PenableFabric=false -Pminecraft_target=26.1 --no-daemon
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew printFabricStatus \
  -PenableFabric=false -Pminecraft_target=26.1.1 --no-daemon
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew printFabricStatus \
  -PenableFabric=false -Pminecraft_target=26.1.2 --no-daemon
```

Expected: each command prints the selected target with its exact Minecraft and Fabric API coordinate.

Then run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew help \
  -PenableFabric=false -Pminecraft_target=26.2 --no-daemon
```

Expected: FAIL during configuration with `Unsupported minecraft_target '26.2'. Supported targets: 26.1, 26.1.1, 26.1.2.`

- [ ] **Step 4: Verify the behavior contract and default target build remain green**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  --tests dev.mappywall.client.MinecraftCompatibilityContractTest \
  --no-daemon --rerun-tasks
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  --no-daemon --rerun-tasks
```

Expected: both commands PASS against the default `minecraft_target=26.1`.

- [ ] **Step 5: Inspect and commit Task 2**

Run `git diff --check`, inspect `git diff`, then commit:

```bash
git add -- build.gradle gradle.properties
git commit -m "Add Minecraft 26.1.x build matrix"
```

- [ ] **Step 6: Independently review Task 2**

Give a fresh reviewer `git diff HEAD^..HEAD`, Task 2, all four selector command outputs, focused/full test output, and the global constraints. Require confirmation that each target pins the correct API, the default remains 26.1, invalid targets fail before dependency resolution, and there is no free-form `minecraft_version`/`fabric_version` override that can create mismatched coordinates.

---

### Task 3: Verify the shared remapped JAR as a release artifact

**Files:**
- Modify: `build.gradle`

**Interfaces:**
- Consumes: Task 2's `minecraftTarget`, `minecraftCoordinates`, Fabric-enabled Loom `remapJar` task, and exact shared filename.
- Produces: Gradle verification task `verifyReleaseJar`, which inspects the remapped JAR selected by `tasks.named("remapJar")`.

- [ ] **Step 1: Verify the release-check task is absent**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew verifyReleaseJar \
  -Pminecraft_target=26.1 --no-daemon
```

Expected: FAIL with `Task 'verifyReleaseJar' not found in root project 'mappywall'.`

- [ ] **Step 2: Add the artifact verification task**

Append this Fabric-only task after `printFabricStatus`. It verifies the actual remapped archive rather than the source resource:

```groovy
if (fabricEnabled) {
    tasks.register("verifyReleaseJar") {
        group = "verification"
        description = "Verifies the shared Minecraft 26.1.x release JAR."
        dependsOn tasks.named("remapJar")

        doLast {
            File jarFile = tasks.named("remapJar").get().archiveFile.get().asFile
            String expectedName = "mappywall-0.1.33+mc26.1-26.1.2.jar"
            if (jarFile.name != expectedName) {
                throw new GradleException(
                        "Expected release JAR ${expectedName}, found ${jarFile.name}."
                )
            }

            java.util.zip.ZipFile archive = new java.util.zip.ZipFile(jarFile)
            try {
                Set<String> entries = java.util.Collections.list(archive.entries())
                        .collect { it.name }
                        .toSet()
                Set<String> required = [
                        "fabric.mod.json",
                        "mappywall.client.mixins.json",
                        "dev/mappywall/core/WaterTransitPolicy.class",
                        "dev/mappywall/client/MappyWallClient.class",
                        "dev/mappywall/client/mixin/MultiPlayerGameModeMixin.class"
                ] as Set
                Set<String> missing = required - entries
                if (!missing.isEmpty()) {
                    throw new GradleException("Missing release JAR entries: ${missing.sort()}.")
                }
                if (entries.any { it.startsWith("net/fabricmc/fabric/api/") }) {
                    throw new GradleException("Fabric API classes must not be bundled.")
                }
                if (entries.any {
                    it.startsWith("META-INF/jars/") && it.contains("fabric-api")
                }) {
                    throw new GradleException("Fabric API JARs must not be nested.")
                }

                def metadataEntry = archive.getEntry("fabric.mod.json")
                def metadata = new groovy.json.JsonSlurper().parse(
                        archive.getInputStream(metadataEntry)
                )
                if (metadata.version != "0.1.33+mc26.1-26.1.2") {
                    throw new GradleException("Unexpected mod version ${metadata.version}.")
                }
                if (metadata.environment != "client") {
                    throw new GradleException("Release metadata is not client-only.")
                }
                if ((metadata.entrypoints.keySet() as Set) != (["client"] as Set)) {
                    throw new GradleException(
                            "Release metadata has non-client entrypoints: "
                                    + "${metadata.entrypoints.keySet()}."
                    )
                }
                if (metadata.mixins.size() != 1
                        || metadata.mixins[0].config != "mappywall.client.mixins.json"
                        || metadata.mixins[0].environment != "client") {
                    throw new GradleException(
                            "Unexpected client Mixin metadata ${metadata.mixins}."
                    )
                }
                if (metadata.depends.minecraft != ">=26.1 <=26.1.2") {
                    throw new GradleException(
                            "Unexpected Minecraft range ${metadata.depends.minecraft}."
                    )
                }
            } finally {
                archive.close()
            }
        }
    }
}
```

- [ ] **Step 3: Run the artifact task against the release target and verify GREEN**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew clean verifyReleaseJar \
  -Pminecraft_target=26.1 --no-daemon --rerun-tasks
```

Expected: PASS and `build/libs/mappywall-0.1.33+mc26.1-26.1.2.jar` exists.

- [ ] **Step 4: Verify the diagnostic-target guard is missing (RED)**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew verifyReleaseJar \
  -Pminecraft_target=26.1.1 --no-daemon --rerun-tasks
```

Expected RED evidence: the command incorrectly succeeds, proving the task can
currently bless bytecode compiled against a diagnostic target.

- [ ] **Step 5: Reject diagnostic targets as release sources**

Add this guard as the first statement inside `doLast`:

```groovy
if (minecraftTarget != "26.1") {
    throw new GradleException(
            "The shared release JAR must be built with minecraft_target=26.1, "
                    + "not ${minecraftTarget}."
    )
}
```

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew verifyReleaseJar \
  -Pminecraft_target=26.1.1 --no-daemon
```

Expected: FAIL with `The shared release JAR must be built with minecraft_target=26.1, not 26.1.1.`

Re-run the 26.1 verification command from Step 3 and expect PASS.

- [ ] **Step 6: Inspect and commit Task 3**

Run `git diff --check`, inspect `git diff`, then commit:

```bash
git add -- build.gradle
git commit -m "Verify shared compatibility artifact"
```

- [ ] **Step 7: Independently review Task 3**

Give a fresh reviewer `git diff HEAD^..HEAD`, Task 3, the expected diagnostic-target rejection, the successful 26.1 verification output, and the global constraints. Require confirmation that the task reads the remapped JAR, checks the exact filename/version/range/client entrypoint, detects bundled Fabric API classes, and cannot bless a JAR compiled against 26.1.1 or 26.1.2.

---

### Task 4: Document and execute the compatibility release matrix

**Files:**
- Modify: `AGENTS.md:3-7,34-45`
- Modify: `docs/build-notes.md:3-50`
- Modify: `docs/design.md:3-5,65`

**Interfaces:**
- Consumes: Task 2's `minecraft_target` values and Task 3's exact release JAR/task.
- Produces: authoritative build instructions, three-version automated evidence, the final shared candidate SHA-256, and a manual checklist explicitly owned by the user.

- [ ] **Step 1: Update the authoritative repository target**

Replace the opening target paragraph in `AGENTS.md` with:

```markdown
MappyWall is an implemented Fabric client mod supporting Minecraft `26.1`,
`26.1.1`, and `26.1.2` from one shared 26.1-compiled release candidate. The
project uses Java `25`, Fabric Loader `0.19.3`, Fabric Loom `1.17.13`, and the
official unobfuscated Minecraft names. Each `minecraft_target` selects its
matching Fabric API from the guarded map in `build.gradle`.
```

Add these matrix commands after the default build commands:

````markdown
The default target is `26.1`. Compatibility diagnostics select the other
supported releases without overriding Minecraft and Fabric API independently:

```bash
bash ./gradlew test --no-daemon -Pminecraft_target=26.1
bash ./gradlew test --no-daemon -Pminecraft_target=26.1.1
bash ./gradlew test --no-daemon -Pminecraft_target=26.1.2
```
````

Keep the existing warning that `-PenableFabric=false test` is not a valid
fallback.

- [ ] **Step 2: Replace the build-notes target section and add the matrix**

Use this target table in `docs/build-notes.md`:

```markdown
## Current targets

The shared release candidate targets these exact versions:

| `minecraft_target` | Minecraft | Fabric API |
|---|---|---|
| `26.1` | `26.1` | `0.145.1+26.1` |
| `26.1.1` | `26.1.1` | `0.145.4+26.1.1` |
| `26.1.2` | `26.1.2` | `0.155.2+26.1.2` |

Every target uses Fabric Loader `0.19.3`, Fabric Loom `1.17.13`, Java `25`,
and official unobfuscated Minecraft names. The default `26.1` target produces
`mappywall-0.1.33+mc26.1-26.1.2.jar`; later targets are build diagnostics and
must not replace the release candidate.
```

Add this Linux matrix after the default build section while preserving the
Windows wrapper equivalents and proxy notes:

````markdown
## Compatibility matrix

Run the same source and tests against every supported target:

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
bash ./gradlew test --no-daemon --rerun-tasks -Pminecraft_target=26.1
bash ./gradlew clean build --no-daemon --rerun-tasks -Pminecraft_target=26.1
bash ./gradlew test --no-daemon --rerun-tasks -Pminecraft_target=26.1.1
bash ./gradlew clean build --no-daemon --rerun-tasks -Pminecraft_target=26.1.1
bash ./gradlew test --no-daemon --rerun-tasks -Pminecraft_target=26.1.2
bash ./gradlew clean build --no-daemon --rerun-tasks -Pminecraft_target=26.1.2
```

After diagnostics, rebuild and verify `minecraft_target=26.1` so the file in
`build/libs/` is the shared candidate compiled against the oldest version.
Final single-JAR acceptance depends on the user's real-client smoke tests in
all three versions; automated compilation is not a substitute.
````

- [ ] **Step 3: Update the current design version statements**

Replace the first target sentence in `docs/design.md` with:

```markdown
Current implementation supports Minecraft `26.1`, `26.1.1`, and `26.1.2`
from one shared candidate compiled against 26.1 with official unobfuscated
names and Java 25. Final single-JAR acceptance remains conditional on the
user's real-client smoke tests in all three versions.
```

Replace the renderer bullet with:

```markdown
- Target and path markers use the Minecraft 26.1.x level-render context and render after translucent world features.
```

Do not rewrite historical 26.2 implementation plans or the dated 26.2 Linux
handoff; they remain records of how the current navigation implementation was
produced.

- [ ] **Step 4: Run focused compatibility contracts for each target**

Run these as separate commands and require PASS from each:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test --tests \
  dev.mappywall.client.MinecraftCompatibilityContractTest \
  -Pminecraft_target=26.1 --no-daemon --rerun-tasks
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test --tests \
  dev.mappywall.client.MinecraftCompatibilityContractTest \
  -Pminecraft_target=26.1.1 --no-daemon --rerun-tasks
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test --tests \
  dev.mappywall.client.MinecraftCompatibilityContractTest \
  -Pminecraft_target=26.1.2 --no-daemon --rerun-tasks
```

- [ ] **Step 5: Run the full test suite for each target**

Run these as separate commands and require PASS from each:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  -Pminecraft_target=26.1.1 --no-daemon --rerun-tasks
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  -Pminecraft_target=26.1.2 --no-daemon --rerun-tasks
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew test \
  -Pminecraft_target=26.1 --no-daemon --rerun-tasks
```

- [ ] **Step 6: Run clean builds for each target, leaving 26.1 last**

Run these as separate commands and require PASS from each:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew clean build \
  -Pminecraft_target=26.1.1 --no-daemon --rerun-tasks
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew clean build \
  -Pminecraft_target=26.1.2 --no-daemon --rerun-tasks
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew clean build \
  -Pminecraft_target=26.1 --no-daemon --rerun-tasks
```

- [ ] **Step 7: Run final metadata, JSON, source-boundary, and diff checks**

Run:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash ./gradlew verifyReleaseJar \
  -Pminecraft_target=26.1 --no-daemon --rerun-tasks
python3 -m json.tool src/main/resources/fabric.mod.json >/dev/null
python3 -m json.tool src/client/resources/assets/mappywall/lang/zh_cn.json >/dev/null
python3 -m json.tool src/client/resources/assets/mappywall/lang/en_us.json >/dev/null
rg -n "ServerModInitializer|implements ModInitializer|CustomPayload" \
  src/main src/client || true
git diff --check ab86153..HEAD
git diff --check
sha256sum build/libs/mappywall-0.1.33+mc26.1-26.1.2.jar
```

Expected: artifact verification and all JSON/diff checks pass; the source-boundary search reports no server initializer or custom payload implementation. Record the exact SHA-256 printed for the user.

- [ ] **Step 8: Inspect and commit Task 4**

Inspect `git diff`, confirm dated historical plans/handoffs were not rewritten,
then commit:

```bash
git add -- AGENTS.md docs/build-notes.md docs/design.md
git commit -m "Document Minecraft 26.1.x release"
```

- [ ] **Step 9: Independently review the complete migration**

Give a fresh reviewer the approved design, this plan, `git diff ab86153..HEAD`,
all focused/full/build/artifact evidence, the SHA-256, and the global
constraints. Require a line-by-line requirements check. Fix every valid
finding, rerun the affected focused test, then rerun all commands from Steps
5-7 before reporting automated readiness.

- [ ] **Step 10: Hand the shared candidate to the user for real-client tests**

Report the exact path and SHA-256 of
`build/libs/mappywall-0.1.33+mc26.1-26.1.2.jar`. Ask the user to use that same
file with the matching Fabric API in each of Minecraft 26.1, 26.1.1, and
26.1.2, and record these five checks per version:

1. Loader reaches the main menu without MappyWall linkage or Mixin errors.
2. A world opens and MappyWall task/settings screens open, close, and reopen.
3. HUD and target/path rendering execute without a crash.
4. An existing project save reads and writes without a format change.
5. Manual mode and the explicit automation toggle remain available.

State that full navigation scenarios remain pending unless the user reports
them. Keep the one-JAR result only after all three smoke reports pass. If a
real version-specific incompatibility appears, capture its log and create a
new approved fallback plan for the three exact filenames in the design rather
than improvising unreviewed version forks.

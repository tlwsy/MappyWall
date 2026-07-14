# Safe and Seamless Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep automatic walking on reversible surface routes and eliminate ordinary pauses between bounded local path segments.

**Architecture:** Extend the immutable local-search snapshot with surface metadata and make planner outcomes explicit. Add a pure generic segment coordinator for active/pending/buffered state, then integrate an incremental client-thread snapshot capture and seam-anchored asynchronous lookahead into `MovementController`.

**Tech Stack:** Java 25, Fabric Loom 1.17.13, Minecraft 26.2 official mappings, JUnit 5.14, Gradle 9.6.

## Global Constraints

- Remain a client-only mod compatible with vanilla servers.
- Do not change sprint, swimming, boat, Elytra, breaking, or placement speeds.
- Never read Minecraft world state from the planner worker thread.
- Never execute an old-target waypoint after the navigation target changes.
- Never prefetch across an unresolved `BREAK` or `PLACE` suffix.
- Every production behavior starts with a failing regression test.

---

### Task 1: Make client navigation logic directly testable

**Files:**
- Modify: `build.gradle`
- Create: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`

**Interfaces:**
- Consumes: Loom's existing `client` source set.
- Produces: the standard `test` task can compile tests in package `dev.mappywall.client` against `sourceSets.client.output`.

- [ ] **Step 1: Add a compile-only smoke test before changing Gradle**

```java
package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

class LocalPathPlannerTest {
    @Test
    void clientPlannerIsAvailableToNavigationTests() {
        assertNotNull(new LocalPathPlanner());
    }
}
```

- [ ] **Step 2: Run the test and verify the actual Loom behavior**

Run: `gradlew.bat test --tests dev.mappywall.client.LocalPathPlannerTest --no-daemon`

Observed after `clean`: `compileClientJava` and `clientClasses` run before `compileTestJava`, and the smoke test passes. Loom 1.17.13 already exposes the split client output to tests in this project.

- [ ] **Step 3: Avoid redundant classpath configuration**

Leave `build.gradle` unchanged because the required dependency already exists. This task establishes a test contract rather than adding production behavior, so the global behavior-level RED requirement does not apply.

- [ ] **Step 4: Run the smoke test and verify GREEN**

Run: `gradlew.bat test --tests dev.mappywall.client.LocalPathPlannerTest --no-daemon`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the test infrastructure**

```text
git add build.gradle src/test/java/dev/mappywall/client/LocalPathPlannerTest.java
git commit -m "Test client navigation logic"
```

### Task 2: Reject unsafe cave frontiers and irreversible partial drops

**Files:**
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`
- Expand: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`

**Interfaces:**
- Produces: `PathPlan(start, steps, plannedEnd, outcome)`.
- Produces: `PathOutcome` values `REACHED_TARGET`, `SAFE_FRONTIER`, `UNLOADED_FRONTIER`, `NODE_LIMIT`, `NO_PATH`.
- Produces: `NavigationSnapshot` with `surfaceAware` and `surfaceHeights` column metadata.
- Test fixture: nested `TestTerrain` exposes `flatSurface(int y)`, `roof(int minX, int maxX, int y)`, `wall(int x, int minZ, int maxZ, int y)`, `clear(int x, int y, int z)`, `unloadColumn(int x, int z)`, and `snapshot(BlockPos start)`; `routeTo(int x, int z)` builds a scale-0 `RouteStep` target.

- [ ] **Step 1: Write failing synthetic-terrain tests**

Add these behaviors using package-visible `NavigationSnapshot` and `Cell` fixtures:

```java
@Test
void prefersReversibleSurfaceDetourOverCloserCaveFrontier() {
    TestTerrain terrain = new TestTerrain().flatSurface(64).roof(2, 27, 67).wall(27, -1, 1, 64);
    PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(80, 0), config());
    assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
    assertTrue(plan.steps().stream().anyMatch(step -> Math.abs(step.pos().getZ()) >= 2));
    assertTrue(plan.steps().stream().noneMatch(step -> terrain.coveredDepth(step.pos()) > 3));
}

@Test
void doesNotCommitThreeBlockDropWhenLocalPlanIsPartial() {
    TestTerrain terrain = TestTerrain.threeBlockDropTowardTargetWithNoReturn();
    PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(80, 0), config());
    assertTrue(plan.steps().stream().noneMatch(step -> step.action() == StepAction.DROP));
    assertNotEquals(new BlockPos(8, 62, 0), plan.plannedEnd());
}

@Test
void acceptsCoveredPassageWhenSamePlanProvesSurfaceExit() {
    TestTerrain terrain = TestTerrain.shortTunnelWithSurfaceExit();
    PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(24, 0), config());
    assertEquals(PathOutcome.REACHED_TARGET, plan.outcome());
    assertTrue(plan.steps().stream().anyMatch(step -> terrain.coveredDepth(step.pos()) > 3));
    assertEquals(0, terrain.coveredDepth(plan.plannedEnd()));
}

@Test
void undergroundRecoveryMayInitiallyIncreaseTargetDistance() {
    TestTerrain terrain = TestTerrain.caveWithExitBehindStart();
    PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 48, 0)), routeTo(80, 0), config());
    assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
    assertTrue(plan.steps().getFirst().pos().getX() < 0);
    assertTrue(terrain.coveredDepth(plan.plannedEnd()) < terrain.coveredDepth(plan.plannedStart()));
}

@Test
void reportsUnloadedFrontierSeparately() {
    TestTerrain terrain = new TestTerrain().flatSurface(64).unloadColumn(12, 0);
    PathPlan plan = planner.plan(terrain.snapshot(new BlockPos(0, 65, 0)), routeTo(80, 0), config());
    assertEquals(PathOutcome.UNLOADED_FRONTIER, plan.outcome());
    assertFalse(plan.steps().isEmpty());
}
```

- [ ] **Step 2: Run the five tests and verify RED**

Run: `gradlew.bat test --tests "dev.mappywall.client.LocalPathPlannerTest" --no-daemon`

Expected: failures show missing `PathOutcome`, surface metadata, and risk-aware endpoint behavior.

- [ ] **Step 3: Extend snapshot and node state minimally**

Add surface height lookup by `(x,z)`, `coveredDepth(BlockPos)`, `surfaceLike(BlockPos)`, and search-node fields for `undergroundExposure`, `maxCoveredDepth`, and `dropRecoveryY`. Include `dropRecoveryY` in `SearchKey` so safe and indebted arrivals at the same position are not conflated.

- [ ] **Step 4: Replace incomplete-plan endpoint selection**

Select partial endpoints only when they improve surface recovery and have no drop debt. Rank candidates lexicographically by surface risk, cumulative cost, then target heuristic. Require a surface-like target in surface-aware dimensions. Return explicit outcomes instead of treating every non-empty partial path as success.

- [ ] **Step 5: Run planner tests and verify GREEN**

Run: `gradlew.bat test --tests "dev.mappywall.client.LocalPathPlannerTest" --no-daemon`

Expected: all cave, drop, exit, recovery, and unloaded-frontier tests pass.

- [ ] **Step 6: Commit planner safety**

```text
git add src/client/java/dev/mappywall/client/LocalPathPlanner.java src/test/java/dev/mappywall/client/LocalPathPlannerTest.java
git commit -m "Prefer safe surface path frontiers"
```

### Task 3: Add a deterministic double-buffer segment coordinator

**Files:**
- Create: `src/main/java/dev/mappywall/core/PathSegmentCoordinator.java`
- Create: `src/test/java/dev/mappywall/core/PathSegmentCoordinatorTest.java`

**Interfaces:**
- Produces: `Anchor(int x, int y, int z)`.
- Produces: `Segment<T>(Anchor start, Anchor end, List<T> steps, boolean terminal, boolean prefetchable)`.
- Produces: `LookaheadRequest(long id, long generation, String targetKey, Anchor seam)`.
- Produces methods `resetTarget`, `installInitial`, `beginLookahead`, `acceptLookahead`, `failLookahead`, `currentStep`, `advance`, `promoteBuffered`, `remainingSteps`, and `clear`.

- [ ] **Step 1: Write coordinator RED tests**

```java
@Test
void keepsExecutingActiveSegmentWhileLookaheadIsPending() {
    coordinator.resetTarget("target-a");
    coordinator.installInitial(segment(anchor(0), anchor(20), range(1, 20), false, true));
    for (int index = 0; index < 7; index++) coordinator.advance();
    assertTrue(coordinator.beginLookahead(true).isPresent());
    assertEquals(8, coordinator.currentStep().orElseThrow());
}

@Test
void swapsBufferedSegmentAtSeamWithoutEmptyStep() {
    coordinator.resetTarget("target-a");
    coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
    LookaheadRequest request = coordinator.beginLookahead(true).orElseThrow();
    assertTrue(coordinator.acceptLookahead(request, segment(anchor(2), anchor(4), List.of(3, 4), true, false)));
    coordinator.advance();
    coordinator.advance();
    assertTrue(coordinator.promoteBuffered());
    assertEquals(3, coordinator.currentStep().orElseThrow());
}

@Test
void rejectsLateCompletionFromOldGeneration() {
    coordinator.resetTarget("target-a");
    coordinator.installInitial(segment(anchor(0), anchor(2), List.of(1, 2), false, true));
    LookaheadRequest old = coordinator.beginLookahead(true).orElseThrow();
    coordinator.resetTarget("target-b");
    assertFalse(coordinator.acceptLookahead(old, segment(anchor(2), anchor(4), List.of(3, 4), false, true)));
}

@Test
void targetChangeInvalidatesBothSegments() {
    coordinator.resetTarget("target-a");
    coordinator.installInitial(segment(anchor(0), anchor(1), List.of(1), false, true));
    coordinator.acceptLookahead(coordinator.beginLookahead(true).orElseThrow(),
            segment(anchor(1), anchor(2), List.of(2), false, true));
    coordinator.resetTarget("target-b");
    assertTrue(coordinator.currentStep().isEmpty());
    assertFalse(coordinator.hasBuffered());
}

@Test
void doesNotRequestLookaheadForUnstableSuffix() {
    coordinator.resetTarget("target-a");
    coordinator.installInitial(segment(anchor(0), anchor(3), List.of(1, 2, 3), false, true));
    assertTrue(coordinator.beginLookahead(false).isEmpty());
}
```

- [ ] **Step 2: Run coordinator tests and verify RED**

Run: `gradlew.bat test --tests dev.mappywall.core.PathSegmentCoordinatorTest --no-daemon`

Expected: compilation fails because `PathSegmentCoordinator` does not exist.

- [ ] **Step 3: Implement the minimal generic state machine**

Use immutable `Segment` and `LookaheadRequest` records, copy all lists, increment generation on target reset, allow one pending and one buffered segment, and require an exact seam/start match before accepting a continuation.

- [ ] **Step 4: Run coordinator tests and verify GREEN**

Run: `gradlew.bat test --tests dev.mappywall.core.PathSegmentCoordinatorTest --no-daemon`

Expected: all coordinator tests pass.

- [ ] **Step 5: Commit the coordinator**

```text
git add src/main/java/dev/mappywall/core/PathSegmentCoordinator.java src/test/java/dev/mappywall/core/PathSegmentCoordinatorTest.java
git commit -m "Coordinate lookahead path segments"
```

### Task 4: Capture seam snapshots incrementally

**Files:**
- Create: `src/client/java/dev/mappywall/client/NavigationSnapshotCapture.java`
- Create: `src/test/java/dev/mappywall/client/NavigationSnapshotCaptureTest.java`
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`

**Interfaces:**
- Consumes: `Level`, seam `BlockPos`, and planner bounds.
- Produces: `advance(int maxColumns)` and `finish()` returning an immutable `NavigationSnapshot` only after all columns are copied.
- Production adapter reads heightmap and cells; a package-visible `WorldView` interface permits deterministic tests.

- [ ] **Step 1: Write batching RED tests**

```java
@Test
void copiesNoMoreThanTheRequestedColumnsPerTick() {
    CountingWorldView world = CountingWorldView.flat(64);
    NavigationSnapshotCapture capture = new NavigationSnapshotCapture(world, new BlockPos(0, 65, 0));
    assertFalse(capture.advance(7));
    assertEquals(7, world.columnsRead());
}

@Test
void finishedSnapshotUsesTheSeamAsItsStart() {
    NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
            CountingWorldView.flat(64), new BlockPos(24, 65, -8));
    while (!capture.advance(256)) { }
    assertEquals(new BlockPos(24, 65, -8), capture.finish().start());
}

@Test
void recordsSurfaceHeightAndUnloadedColumns() {
    CountingWorldView world = CountingWorldView.flat(70).withUnloadedColumn(2, 3);
    NavigationSnapshotCapture capture = new NavigationSnapshotCapture(world, new BlockPos(0, 71, 0));
    while (!capture.advance(512)) { }
    NavigationSnapshot snapshot = capture.finish();
    assertEquals(71, snapshot.surfaceHeight(0, 0));
    assertFalse(snapshot.isColumnLoaded(2, 3));
}
```

- [ ] **Step 2: Run capture tests and verify RED**

Run: `gradlew.bat test --tests dev.mappywall.client.NavigationSnapshotCaptureTest --no-daemon`

Expected: compilation fails because the capture component is absent.

- [ ] **Step 3: Implement bounded capture**

Move synchronous snapshot-copy details behind `WorldView`; process columns in deterministic X/Z order; read only on the client thread; use `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES` when the dimension has skylight; freeze maps and sets in `finish()`.

- [ ] **Step 4: Run capture tests and verify GREEN**

Run: `gradlew.bat test --tests dev.mappywall.client.NavigationSnapshotCaptureTest --no-daemon`

Expected: all capture tests pass.

- [ ] **Step 5: Commit incremental capture**

```text
git add src/client/java/dev/mappywall/client/NavigationSnapshotCapture.java src/client/java/dev/mappywall/client/LocalPathPlanner.java src/test/java/dev/mappywall/client/NavigationSnapshotCaptureTest.java
git commit -m "Capture lookahead terrain incrementally"
```

### Task 5: Integrate seam-anchored lookahead without changing movement speed

**Files:**
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/client/java/dev/mappywall/client/MappyWallRuntime.java` only if target handoff requires a non-reset transition.
- Create: `src/test/java/dev/mappywall/core/NavigationContinuityScenarioTest.java`

**Interfaces:**
- Consumes: planner `PathOutcome`, coordinator request/segment records, and incremental capture.
- Preserves: existing `moveToward`, `applyAggressiveGroundVelocity`, sprint constants, and action speeds unchanged.

- [ ] **Step 1: Write the long-route continuity RED test**

Use a fake delayed planner/coordinator driver for a route longer than 60 blocks. Assert that while lookahead is pending, active steps continue; when the seam is reached with a buffer, the next step is returned in the same logical tick; old-generation completion is ignored.

- [ ] **Step 2: Run continuity tests and verify RED**

Run: `gradlew.bat test --tests dev.mappywall.core.NavigationContinuityScenarioTest --no-daemon`

Expected: the coordinator driver cannot yet express the full no-gap lifecycle or the integration contract is absent.

- [ ] **Step 3: Replace single-segment lifecycle in `MovementController`**

Start lookahead at 14 remaining safe steps. Anchor capture at `plannedEnd`, keep executing the active segment during capture/search, validate the seam and first continuation transitions against the live world, and promote before returning `null` from `nextWaypoint`. Initial plans retain start-drift validation; lookahead plans validate target generation and seam instead.

- [ ] **Step 4: Preserve safety fallbacks**

Do not prefetch when the active suffix contains unresolved `BREAK` or `PLACE`. Continue to the safe endpoint when chunks are unavailable, then stop. A target change, pause, hard reset, or world change cancels capture/future and clears active and buffered segments.

- [ ] **Step 5: Run targeted and full tests**

Run:

```text
gradlew.bat test --tests "dev.mappywall.client.*" --tests "dev.mappywall.core.PathSegmentCoordinatorTest" --tests "dev.mappywall.core.NavigationContinuityScenarioTest" --no-daemon
gradlew.bat test --no-daemon
```

Expected: targeted navigation tests and the full suite pass.

- [ ] **Step 6: Commit controller integration**

```text
git add src/client/java/dev/mappywall/client/MovementController.java src/client/java/dev/mappywall/client/MappyWallRuntime.java src/test/java/dev/mappywall/core/NavigationContinuityScenarioTest.java
git commit -m "Prefetch local paths without stopping"
```

### Task 6: Document and verify the release

**Files:**
- Modify: `docs/design.md`
- Modify: `gradle.properties`

**Interfaces:**
- Produces: mod version `0.1.30` and updated design notes.

- [ ] **Step 1: Document surface-safe frontiers and double buffering**

State that partial plans cannot commit unrecovered drops, cave routes need a verified exit, and lookahead is seam-anchored with live validation.

- [ ] **Step 2: Bump version to `0.1.30`**

Change only `mod_version=0.1.30`.

- [ ] **Step 3: Run final verification**

Run:

```text
gradlew.bat clean build --no-daemon
git diff --check
```

Inspect test XML for zero failures, parse both language JSON files and mixin/Fabric JSON, and verify the built jar contains `MovementController`, `LocalPathPlanner`, `NavigationSnapshotCapture`, and `PathSegmentCoordinator`.

- [ ] **Step 4: Request independent review**

Review must focus on cave/dead-end escape, irreversible drops, stale asynchronous results, seam adjacency, target changes, world-modifying suffixes, and whether any sprint/speed constant changed.

- [ ] **Step 5: Commit release metadata**

```text
git add docs/design.md gradle.properties docs/superpowers
git commit -m "Document safe seamless navigation"
```

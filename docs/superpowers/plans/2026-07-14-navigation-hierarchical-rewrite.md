# Hierarchical Navigation Rewrite: Engineering Design and Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` or `superpowers:executing-plans`.
> Every behavior change follows RED → GREEN → REFACTOR and each task has an
> independent review gate.

**Branch:** `26.2/navigation-hierarchical-rewrite`

**Goal:** Replace repeated short-range point-to-point searches with a
hierarchical navigator that maintains a long-lived surface corridor while a
small local planner safely executes the next portion of that corridor.

**Architecture:** A pure coarse corridor planner works on immutable chunk-scale
terrain summaries. The existing block-level planner remains the authority for
collision, cave, hazard, jump, water, placement, breaking, and drop safety. A
rolling pipeline captures terrain ahead of the player, keeps one active local
segment and one or more corridor-backed continuations ready, and invalidates
only the affected horizon when chunks or targets change.

**Tech stack:** Java 25, Fabric Loom 1.17.13, Minecraft 26.2 official mappings,
JUnit 5.14, client-only Fabric APIs.

## 1. Why this option exists

The current bounded planner is being asked to solve two different problems:

1. decide the long-distance direction around cliffs, caves, water, and unloaded
   terrain; and
2. produce exact per-block WALK/JUMP/DROP/SWIM/BREAK/PLACE actions.

That coupling produced measurable regressions:

- an equal-risk frontier test deliberately chooses one step even when a
  farther safe endpoint is connected;
- the cliff regression waits until node `maxNodes - 1` and has measured
  `10.704s` inside the test method;
- a `NODE_LIMIT` in aggressive mode can trigger a second full search with
  modification states;
- a 57×57 snapshot contains 3,249 columns and takes at least 17 ticks at 192
  columns per tick;
- a five- or six-step segment can exhaust while a 50-tick cooldown remains,
  guaranteeing a stop before the next capture and search;
- an `UNLOADED_FRONTIER` cannot prefetch, so short chunk-boundary segments
  intentionally run dry.

This option removes the architectural cause: long-distance intent is no longer
discarded every time the local 28-block window ends.

## 2. Objectives

### 2.1 Functional objectives

- Maintain a stable coarse route for at least the next 8 loaded chunks when
  terrain information permits.
- Generate exact local actions only for a rolling block window around the
  player and the next coarse waypoint.
- Preserve current danger-block, lava, collision, support, diagonal, cave,
  water, drop-debt, placement, and breaking rules in the local executor.
- Replan the corridor only when its target changes or a terrain-summary
  revision intersects the unconsumed corridor.
- Continue consuming an accepted local segment while all future planning work
  runs in the background.
- Remain client-only and compatible with vanilla servers.

### 2.2 Performance objectives

- Coarse planning on a 17×17 chunk summary grid completes in less than 50 ms in
  deterministic unit benchmarks after JVM warm-up.
- A loaded, ordinary surface route of 128 blocks produces no input-neutral
  segment boundary in the deterministic pipeline scenario.
- Exact local planning normally returns at least 20 safe movement steps when a
  connected safe corridor is present.
- No planning stage performs two full searches merely because the first stage
  reached a node limit.
- Main-thread terrain capture is bounded and never reads Minecraft world state
  from a worker thread.

### 2.3 Non-goals

- This branch does not change sprint, walk, swim, boat, Elytra, jump,
  placement, breaking, eating, packet, or interaction speeds.
- It does not add server packets or require a server-side mod.
- It does not guarantee navigation through arbitrary parkour, redstone,
  portals, ladders, or player-built moving structures.
- It does not make breaking or placement the normal route choice.
- It does not cache world data across different server identities or
  dimensions.

## 3. Global constraints

- All `Level`, chunk, block-state, heightmap, entity, and player reads occur on
  the client thread.
- Worker inputs are immutable snapshots or immutable coarse summaries.
- A target-generation change invalidates corridor, local active, pending, and
  buffered work before another waypoint can execute.
- Live world validation remains mandatory before every exact action.
- Multi-block drops may not become incomplete local frontiers with unrecovered
  drop debt.
- A covered route must either reach its valid target or prove a safe exit in the
  same exact local segment.
- Existing normal/aggressive movement and packet methods remain byte-for-byte
  unchanged unless a separately approved safety defect requires otherwise.

## 4. System design

### 4.1 `TerrainSummaryWindow`

`TerrainSummaryWindow` is an immutable coarse view centered on a chunk. Each
cell represents one 16×16 chunk column and contains only planning metadata:

```java
public record TerrainSummaryCell(
        int chunkX,
        int chunkZ,
        int representativeFeetY,
        int minSurfaceY,
        int maxSurfaceY,
        int coveredFractionPermille,
        int waterFractionPermille,
        int hazardCount,
        boolean loaded,
        long revision
) {}

public record TerrainSummaryWindow(
        String dimensionKey,
        int centerChunkX,
        int centerChunkZ,
        int radiusChunks,
        Map<Long, TerrainSummaryCell> cells,
        long generation
) {}

public record CorridorAnchor(int blockX, int feetY, int blockZ) {}
```

The client-thread builder samples the motion-blocking heightmap and a bounded
set of support/body cells. It does not copy every block in every chunk. Exact
block copying remains the responsibility of `NavigationSnapshotCapture` near
the current player and local seam.

### 4.2 `SurfaceCorridorPlanner`

The pure planner returns chunk anchors rather than block actions:

```java
public final class SurfaceCorridorPlanner {
    public CorridorPlan plan(
            TerrainSummaryWindow terrain,
            CorridorAnchor start,
            CorridorGoal goal,
            CorridorPolicy policy
    );
}

public record CorridorPlan(
        List<CorridorAnchor> anchors,
        CorridorOutcome outcome,
        long terrainGeneration,
        int expandedNodes
) {}

public record CorridorGoal(int blockX, int blockZ, int acceptanceRadiusBlocks) {}

public record BlockedCorridorEdge(
        CorridorAnchor from,
        CorridorAnchor to,
        long terrainRevision,
        int expiresAtTick
) {}
```

Cost is lexicographic:

1. unloaded/unknown transition class;
2. hazard and covered-terrain risk;
3. irreversible elevation-change risk;
4. remaining target distance;
5. accumulated travel cost.

This prevents a cheap one-chunk endpoint from defeating farther progress while
still ensuring that a cave shortcut cannot beat a surface detour.

### 4.3 Exact local executor

`LocalPathPlanner` receives the next corridor anchor as its local target. Its
search bounds remain small enough for exact block collision and action checks.
The result contract remains:

```java
PathPlan plan(
        NavigationSnapshot snapshot,
        RouteStep finalTarget,
        BlockPos corridorTarget,
        AutoNavigationConfig config
);
```

The local result may reach the corridor anchor, reach the final map target, or
stop at a verified safe frontier. A safe frontier that cannot advance toward
the current corridor anchor reports a structured obstruction so the coarse
planner can invalidate that edge instead of repeating the same local search.

### 4.4 Rolling pipeline

The controller owns these generations:

```text
target generation
  └─ terrain-summary generation
       └─ corridor generation
            └─ local segment request ids
```

At most one client-thread capture batch and one worker planning job are active
per stage. Corridor planning and local planning may use separate single-thread
executors because both consume immutable data and do not share mutable planner
state.

The normal flow is:

1. Capture or refresh coarse summaries ahead of the player.
2. Produce a corridor of chunk anchors.
3. Capture an exact window around the next local seam.
4. Produce and live-validate a local segment.
5. Execute the active segment while steps 1–4 extend the horizon.
6. Promote a buffered segment in the same tick at its exact seam.

### 4.5 Cache invalidation

- Target or dimension changes invalidate everything.
- A block/chunk update increments the corresponding summary-cell revision.
- A changed cell invalidates only corridor suffixes that reference that cell.
- A live failure of an exact edge records a temporary obstruction keyed by
  dimension, from-position, to-position, and terrain revision.
- Obstructions expire when the referenced chunk revision changes or after 200
  ticks, whichever occurs first.
- World-modifying actions invalidate exact snapshots whose bounds contain the
  changed block; they do not invalidate unrelated coarse cells.

## 5. Failure handling and degradation

- If coarse data is unavailable, the controller keeps executing its current
  verified local segment and requests summaries as chunks load.
- If the corridor planner reaches its node limit, it returns the farthest
  verified corridor prefix; it does not launch a modification-capable copy of
  the same search.
- If exact local planning cannot follow a corridor edge, that edge is
  temporarily blocked and the coarse planner chooses an alternative.
- If no alternative exists, the controller stops at a verified safe point and
  reports no path once; it does not repeatedly alternate the same one-block
  edge.
- The feature flag can fall back to the option-2 local pipeline without a
  save-format migration.

## 6. Detailed TDD implementation plan

### Task 1: Introduce the feature flag and immutable corridor contracts

**Files:**

- Create: `src/main/java/dev/mappywall/core/navigation/CorridorAnchor.java`
- Create: `src/main/java/dev/mappywall/core/navigation/CorridorOutcome.java`
- Create: `src/main/java/dev/mappywall/core/navigation/CorridorPlan.java`
- Create: `src/main/java/dev/mappywall/core/navigation/CorridorGoal.java`
- Create: `src/main/java/dev/mappywall/core/navigation/TerrainSummaryCell.java`
- Create: `src/main/java/dev/mappywall/core/navigation/TerrainSummaryWindow.java`
- Create: `src/test/java/dev/mappywall/core/navigation/CorridorContractsTest.java`
- Modify: `src/client/java/dev/mappywall/client/NavigationConfigStore.java`

- [ ] Write `CorridorContractsTest` proving list/map defensive copies,
  generation preservation, and rejection of null anchors.
- [ ] Run:

```powershell
.\gradlew.bat test --tests "dev.mappywall.core.navigation.CorridorContractsTest" --no-daemon
```

  Expected RED: corridor contract types do not exist.
- [ ] Implement the records exactly as specified above and add persisted enum
  `NavigationArchitecture.LOCAL_PIPELINE` / `HIERARCHICAL` with
  `LOCAL_PIPELINE` as the backward-compatible default.
- [ ] Re-run the focused test; expected GREEN.
- [ ] Run the full test task and review serialization compatibility.

### Task 2: Capture bounded terrain summaries on the client thread

**Files:**

- Create: `src/client/java/dev/mappywall/client/TerrainSummaryCapture.java`
- Create: `src/test/java/dev/mappywall/client/TerrainSummaryCaptureTest.java`

Required API:

```java
public boolean advance(int maxChunks);
public TerrainSummaryWindow finish();
public int remainingChunks();
```

- [ ] Write tests proving no more than the supplied chunk budget is read,
  unloaded chunks remain explicit, generation/revision metadata is frozen, and
  `finish()` performs no additional world reads.
- [ ] Run the focused test and verify RED because the capture type is absent.
- [ ] Implement a package-visible `SummaryWorldView` test seam and a production
  `LevelSummaryWorldView` adapter. Sample heightmap/support/body/hazard data on
  the tick thread only.
- [ ] Re-run focused tests; expected GREEN.
- [ ] Add `summaryCaptureCompletesSeventeenBySeventeenWindowWithinBudget()` and
  assert completion in at most 19 batches at 16 chunks per tick.

### Task 3: Implement coarse surface-corridor planning

**Files:**

- Create: `src/main/java/dev/mappywall/core/navigation/SurfaceCorridorPlanner.java`
- Create: `src/main/java/dev/mappywall/core/navigation/CorridorPolicy.java`
- Create: `src/test/java/dev/mappywall/core/navigation/SurfaceCorridorPlannerTest.java`

- [ ] Write RED tests:
  `prefersSurfaceDetourOverCoveredShortcut`,
  `routesAroundThreeBlockCliffWhenGapExists`,
  `returnsFarthestVerifiedPrefixAtNodeLimit`,
  `doesNotChooseOneChunkEndpointOnlyBecauseItIsCheaper`, and
  `rejectsStaleTerrainGeneration`.
- [ ] Implement weighted A* over at most `(2 * radius + 1)^2` summary cells,
  stale-entry skipping, lexicographic risk/progress/cost comparison, and an
  explicit node-expansion counter.
- [ ] Re-run focused tests; expected GREEN.
- [ ] Add a deterministic 17×17 benchmark test with a warm-up loop and a
  non-preemptive two-second suite ceiling; record median planner duration in
  test output while making the node-count bound the non-flaky assertion.

### Task 4: Make exact planning corridor-aware

**Files:**

- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`
- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`
- Create: `src/main/java/dev/mappywall/core/navigation/BlockedCorridorEdge.java`

- [ ] Write RED tests proving a connected corridor produces at least 20 exact
  movement steps, local safety can reject the coarse edge, a nearby cave never
  replaces a surface corridor, and incomplete multi-block drops remain
  forbidden.
- [ ] Add the corridor-target overload while preserving the old public overload
  for `LOCAL_PIPELINE` fallback.
- [ ] Return obstruction metadata only for a live, loaded local failure; do not
  mark unloaded terrain as permanently blocked.
- [ ] Re-run planner tests and the full test task.

### Task 5: Add rolling horizon coordination

**Files:**

- Create: `src/main/java/dev/mappywall/core/navigation/NavigationHorizonCoordinator.java`
- Create: `src/test/java/dev/mappywall/core/navigation/NavigationHorizonCoordinatorTest.java`
- Modify: `src/main/java/dev/mappywall/core/PathSegmentCoordinator.java`

- [ ] Write RED state-machine tests for target invalidation, terrain revision
  invalidation, current-segment continuity, buffered promotion, stale corridor
  completion, and obstruction expiry.
- [ ] Implement immutable request records and exact generation checks.
- [ ] Keep `PathSegmentCoordinator` responsible only for exact active/buffered
  segments; the horizon coordinator owns summary/corridor lifecycle.
- [ ] Run focused coordinator tests and full core tests.

### Task 6: Integrate the hierarchical pipeline into the client controller

**Files:**

- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/client/java/dev/mappywall/client/MappyWallRuntime.java`
- Create: `src/test/java/dev/mappywall/core/navigation/HierarchicalContinuityScenarioTest.java`

- [ ] Write a RED deterministic scenario covering 160 blocks, delayed summary
  capture, delayed corridor planning, delayed exact planning, three local seam
  promotions, a chunk revision, and a target change. Assert the executed prefix
  is contiguous and no old-generation step appears.
- [ ] Add the feature-flag branch in the controller without changing movement or
  packet methods.
- [ ] Validate exact initial and seam prefixes against the live world before
  installation.
- [ ] Brake residual horizontal velocity only in the no-waypoint safety state;
  never modify cruise velocity constants.
- [ ] Run focused continuity tests, all client tests, and the full test task.

### Task 7: Add diagnostics, migration documentation, and release verification

**Files:**

- Modify: `docs/design.md`
- Modify: `gradle.properties`
- Modify: `src/client/resources/assets/mappywall/lang/en_us.json`
- Modify: `src/client/resources/assets/mappywall/lang/zh_cn.json`

- [ ] Add opt-in diagnostics for corridor generation, exact plan duration,
  expanded-node count, buffered horizon length, and invalidation reason. Do not
  spam chat during normal operation.
- [ ] Document the feature flag and immediate fallback to `LOCAL_PIPELINE`.
- [ ] Parse all JSON resources.
- [ ] Run:

```powershell
.\gradlew.bat clean build --no-daemon
git diff --check
```

- [ ] Inspect XML for zero failures/errors and inspect the release jar for every
  new navigation class.
- [ ] Perform an independent review focused on worker-thread isolation, stale
  generation rejection, cache invalidation, safety preservation, and unchanged
  movement/packet speeds.

## 7. Acceptance matrix

| Scenario | Required result |
| --- | --- |
| Flat loaded terrain, 160 blocks | Continuous exact steps; no seam stop |
| Surface detour versus cave shortcut | Surface corridor selected |
| Three-block cliff with a loaded gap | Corridor routes through the gap |
| Three-block cliff without a route | One safe stop; no repeated edge twitch |
| Short covered passage with exit | Allowed only when local plan proves exit |
| Slow chunk loading | Current verified segment completes; horizon resumes as summaries load |
| Target changes during worker jobs | All old corridor/local results rejected |
| Block changes on corridor | Only affected suffix invalidated |
| Aggressive free-look/inventory | Travel direction remains server-facing as before |
| Boat/swim/Elytra | Existing controllers and speeds remain unchanged |

## 8. Risks and mitigations

- **Summary hides a block-level obstacle.** The exact planner and live validator
  remain authoritative and feed the blocked edge back to the corridor planner.
- **Corridor oscillation after dynamic updates.** Revision-keyed edge memory and
  a 200-tick expiry prevent immediate selection of the same failed edge.
- **More state machines increase stale-result risk.** Every request carries all
  parent generations; acceptance requires an exact chain match.
- **Coarse capture adds client work.** It samples chunks, not every block, uses a
  strict per-tick budget, and is independently measurable.
- **Migration destabilizes existing users.** `LOCAL_PIPELINE` remains the
  default until hierarchical manual testing passes; the setting is reversible.

## 9. Migration, release, and rollback

No project save schema needs to change. The architecture preference belongs in
the navigation config and defaults to the current local pipeline when absent.

Release proceeds in two stages:

1. ship the feature behind `LOCAL_PIPELINE` default for manual comparison; then
2. change the default only after the acceptance matrix passes on vanilla-server
   joins, slow chunk loading, aggressive walking, swimming, boats, and pause /
   resume recovery.

Rollback is immediate: select `LOCAL_PIPELINE`, or revert the integration task
while leaving the pure summary/corridor classes unused. Because exact action
execution and project saves are unchanged, rollback does not require data
migration.

## 10. Decision summary

This option has the best long-term route stability and the cleanest separation
of long-distance intent from block-level safety. It also has the largest change
surface and therefore should not be the first emergency fix. Keep it ready for
use if the repaired option-2 pipeline still cannot deliver stable long routes
under real terrain and chunk-loading conditions.

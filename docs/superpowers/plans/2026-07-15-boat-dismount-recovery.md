# Boat Dismount Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the post-dismount jump/replan loop with a bounded server-confirmed transition from boat control to stable block/water navigation.

**Architecture:** A pure Java recovery policy owns request, detach, egress, settle, timeout, and former-boat suppression state. `MovementController` samples Minecraft state and executes policy actions before any ordinary path planning or stall accounting; a bounded local egress selector chooses only collision-checked stable destinations.

**Tech Stack:** Java 25, Fabric Loom 1.17.13, Minecraft 26.2 official mappings, JUnit 5.14.

## Global Constraints

- Never call client-side `player.stopRiding()` as the dismount implementation.
- Do not plan or consume ordinary WALK/JUMP/SWIM nodes while recovery is active.
- Do not increment ordinary stuck/collision recovery counters while waiting for server detach.
- Keep entity collision enabled; never teleport through or globally ignore the former boat.
- Request exactly one fresh global plan after stable clearance.
- Suppress automatic reboarding of the former boat for 60 ticks, while allowing other boats.
- Time out to the existing safe pause path rather than looping indefinitely.

---

### Task 1: Pure dismount recovery state machine

**Files:**
- Create: `src/main/java/dev/mappywall/core/BoatDismountRecovery.java`
- Create: `src/test/java/dev/mappywall/core/BoatDismountRecoveryTest.java`

**Interfaces:**
- Produces: `Phase`, `Action`, `Observation`, `begin(int boatEntityId, double x, double y, double z)`, `tick(Observation)`, `active()`, and `suppressBoarding(int)`.

- [ ] **Step 1: Write failing phase/action tests**

Use the immutable observation:

```java
public record Observation(
        boolean passenger,
        boolean ridingOriginalBoat,
        boolean originalBoatPresent,
        boolean touchingOriginalBoat,
        boolean stableBlockSupport,
        boolean safeWater,
        boolean serverPositionChanged,
        boolean egressAvailable,
        boolean egressProgress
) {}
```

Add tests named:

- `requestRetriesAtMostEveryTenTicksWhileStillPassenger()`;
- `detachedBoatContactMovesToEgressWithoutCompleting()`;
- `stalledEgressEmitsOnlyOneJumpPulse()`;
- `threeStableTicksCompleteExactlyOnce()`;
- `safeWaterCanSettleAfterBoatClearance()`;
- `formerBoatIsSuppressedForSixtyTicks()`;
- `timeoutFailsInsteadOfRestarting()`.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat test --tests "dev.mappywall.core.BoatDismountRecoveryTest" --no-daemon
```

Expected: compilation failure because the policy does not exist.

- [ ] **Step 3: Implement bounded deterministic transitions**

Use these policy constants and action surface:

```java
private static final int REQUEST_INTERVAL_TICKS = 10;
private static final int DETACHED_CONFIRM_TICKS = 3;
private static final int EGRESS_STALL_TICKS = 6;
private static final int SETTLE_TICKS = 3;
private static final int BOARDING_SUPPRESSION_TICKS = 60;
private static final int RECOVERY_TIMEOUT_TICKS = 100;

public enum Phase { IDLE, REQUESTING, CLEARING_BOAT, SETTLING }
public enum Action {
    NONE, REQUEST_DISMOUNT, HOLD, STEER_EGRESS,
    PULSE_JUMP, COMPLETE_REPLAN, FAILED
}
```

`begin(...)` captures the original boat and request position. While passenger, emit `REQUEST_DISMOUNT` only at interval ticks. Once detached, require either observed server movement or three detached ticks before egress. Contact plus an available candidate emits `STEER_EGRESS`; six no-progress ticks emit one `PULSE_JUMP`. Three clear stable block/water observations emit `COMPLETE_REPLAN` once and leave active recovery. Tick the former-boat suppression independently until 60 ticks expire. At 100 active ticks emit `FAILED` once.

- [ ] **Step 4: Run policy tests and verify GREEN**

Run the Step 2 command. Expected: all deterministic phase tests pass.

- [ ] **Step 5: Commit the policy unit**

```powershell
git add src/main/java/dev/mappywall/core/BoatDismountRecovery.java src/test/java/dev/mappywall/core/BoatDismountRecoveryTest.java
git commit -m "Model bounded boat dismount recovery"
```

### Task 2: Collision-checked egress selection

**Files:**
- Create: `src/client/java/dev/mappywall/client/BoatEgressSelector.java`
- Create: `src/test/java/dev/mappywall/client/BoatEgressSelectorTest.java`

**Interfaces:**
- Produces: `Optional<BlockPos> select(EgressProbe probe, BlockPos playerFeet, Vec3 boatCenter, BlockPos navigationTarget)`.
- Consumes: logical feet/support checks from `NavigationFeetResolver` integration.

- [ ] **Step 1: Write failing deterministic candidate-ranking tests**

Represent the eight adjacent candidate columns through a fake `EgressProbe` with methods for loaded state, body clearance, stable support/safe water, and destination AABB collision. Test that selection:

- rejects blocked body/support and occupied destination AABBs;
- prefers a candidate both away from boat center and toward the navigation target;
- permits safe water when no solid candidate exists;
- returns empty rather than choosing an unsafe jump.

- [ ] **Step 2: Run selector tests and verify RED**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.BoatEgressSelectorTest" --no-daemon
```

Expected: compilation failure because the selector does not exist.

- [ ] **Step 3: Implement bounded eight-neighbor selection**

Score only candidates that pass every probe:

```text
score = targetHorizontalDistance
      - 0.75 * distanceFromBoatCenter
      + (safeWater ? 2.0 : 0.0)
```

Choose the lowest score with deterministic X/Z tie-breaking. The live probe builds the full player AABB at the candidate and rejects block, entity, or border collision at that destination. It does not alter collision rules along actual movement.

- [ ] **Step 4: Run selector tests and verify GREEN**

Run the Step 2 command. Expected: all selector tests pass.

- [ ] **Step 5: Commit the selector unit**

```powershell
git add src/client/java/dev/mappywall/client/BoatEgressSelector.java src/test/java/dev/mappywall/client/BoatEgressSelectorTest.java
git commit -m "Select safe boat egress positions"
```

### Task 3: MovementController recovery integration

**Files:**
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/test/java/dev/mappywall/client/MovementControllerPlanningGapTest.java`

**Interfaces:**
- Consumes: `BoatDismountRecovery`, `BoatEgressSelector`, and `NavigationFeetResolver`.
- Produces: recovery actions are handled before snapshot capture, plan acceptance, waypoint execution, and `updateProgress()`.

- [ ] **Step 1: Add failing controller-adjacent lifecycle tests**

Extend the controller policy harness to assert:

- starting recovery clears current planning work once;
- `REQUEST_DISMOUNT`, `HOLD`, and `STEER_EGRESS` return before ordinary planning/stall logic;
- `COMPLETE_REPLAN` calls the fresh-plan transition once;
- `FAILED` maps to the existing stuck pause message;
- former-boat suppression excludes only its entity ID from nearby boarding candidates.

- [ ] **Step 2: Run the focused client test and verify RED**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.MovementControllerPlanningGapTest" --no-daemon
```

Expected: new lifecycle assertions fail because dismount is still an immediate local detach.

- [ ] **Step 3: Replace immediate detachment with recovery startup**

Replace `dismountCooldown` and `player.stopRiding()` with `beginBoatDismount(...)`. The request action neutralizes movement/boat/use inputs, sends sneak input, and leaves passenger state untouched. Save the boat entity ID and original position through the policy.

At the beginning of AUTO_WALK tick, before `advancePendingCapture()`, sample the former boat, contact, stable logical block support/safe water, server position change, egress availability, and progress. If recovery is active, execute its action and return immediately.

- [ ] **Step 4: Implement safe egress action execution**

Cache the selected egress candidate while clearing the boat. `STEER_EGRESS` applies bounded normal/aggressive horizontal intent toward that candidate only after its destination AABB revalidates. `PULSE_JUMP` applies one jump pulse through the existing jump velocity/input path. Neither action invokes `forceLocalReplan()` or `updateProgress()`.

On `COMPLETE_REPLAN`, clear recovery movement, call `forceLocalReplan()` once, and allow normal planning on the next tick. On `FAILED`, release input and return `MovementResult.pause(message.mappywall.auto_walk_stuck)`.

Modify `tryBoardNearbyBoat(...)` to skip an entity when `dismountRecovery.suppressBoarding(entity.getId())` is true.

- [ ] **Step 5: Run focused dismount and navigation tests**

```powershell
.\gradlew.bat test --tests "dev.mappywall.core.BoatDismountRecoveryTest" --tests "dev.mappywall.client.BoatEgressSelectorTest" --tests "dev.mappywall.client.MovementControllerPlanningGapTest" --no-daemon
```

Expected: all tests pass; no test observes ordinary replan/stall work during recovery.

- [ ] **Step 6: Commit controller integration**

```powershell
git add src/client/java/dev/mappywall/client/MovementController.java src/test/java/dev/mappywall/client/MovementControllerPlanningGapTest.java
git commit -m "Recover safely after boat dismount"
```

### Task 4: Dismount regression verification

**Files:**
- Modify: `docs/design.md`

**Interfaces:**
- Documents: server-confirmed detach, local egress, reboard suppression, and timeout.

- [ ] **Step 1: Run focused and full controller verification**

```powershell
.\gradlew.bat test --tests "dev.mappywall.core.BoatDismountRecoveryTest" --tests "dev.mappywall.client.*" --no-daemon
git diff --check
```

Expected: zero failures and no whitespace errors.

- [ ] **Step 2: Document manual validation**

Record tests for direct server placement on shore, standing on the boat edge, safe-water detach, blocked shoreline timeout, no immediate reboarding, and unchanged normal boat/swim cadence.

- [ ] **Step 3: Commit documentation**

```powershell
git add docs/design.md
git commit -m "Document boat dismount recovery"
```

# Logical Navigation Feet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate perpetual replanning and false vertical transitions when the player stands on partial-height block collision surfaces.

**Architecture:** Centralize conversion from physical player feet to the planner's integer feet cell in a stateless resolver. Use the same resolved value at every controller boundary that compares the live player with a grid plan; leave block legality and hazard checks in their existing validators.

**Tech Stack:** Java 25, Fabric Loom 1.17.13, Minecraft 26.2 official mappings, JUnit 5.14.

## Global Constraints

- Do not make partial collision blocks passable or loosen WALK/JUMP/DROP deltas.
- Coordinate normalization must not treat boat/entity support as block support.
- Airborne, swimming, and passenger movement retain physical/raw feet semantics.
- Existing unsafe support and hazard validation remains authoritative.
- Preserve the planner's current integer-grid model; sub-block route-cost redesign is out of scope.

---

### Task 1: Stateless logical-feet resolver

**Files:**
- Create: `src/client/java/dev/mappywall/client/NavigationFeetResolver.java`
- Create: `src/test/java/dev/mappywall/client/NavigationFeetResolverTest.java`

**Interfaces:**
- Produces: `BlockPos resolve(LocalPlayer player)`.
- Produces: `BlockPos resolveProjected(LocalPlayer player, double x, double z)`.
- Produces package-private pure overload `BlockPos resolve(double x, double feetY, double z, boolean grounded, Predicate<BlockPos> hasBlockCollision)` for deterministic tests.

- [ ] **Step 1: Write the failing parameterized resolver tests**

Use physical surface heights rather than block-name guesses:

```java
@ParameterizedTest
@CsvSource({
    "64.0,64",
    "63.9375,64",
    "63.875,64",
    "63.5,64",
    "63.125,64"
})
void groundedBlockCollisionResolvesToPlannerFeet(double feetY, int expectedY) {
    BlockPos result = resolver.resolve(0.5, feetY, 0.5, true, collidableBelow(feetY));
    assertEquals(expectedY, result.getY());
}
```

Add cases for exact-integer floating error, `grounded=false`, no block collision below the candidate (boat/entity support), and projected X/Z moving between a partial surface and an integer-height surface.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.NavigationFeetResolverTest" --no-daemon
```

Expected: compilation failure because the resolver does not exist.

- [ ] **Step 3: Implement coordinate normalization**

Use one epsilon and block-collision probe:

```java
static final double FEET_EPSILON = 1.0e-4;

BlockPos resolve(double x, double feetY, double z, boolean grounded,
                 Predicate<BlockPos> hasBlockCollision) {
    BlockPos raw = BlockPos.containing(x, feetY, z);
    if (!grounded) {
        return raw;
    }
    int candidateY = Mth.ceil(feetY - FEET_EPSILON);
    if (candidateY == raw.getY()) {
        return raw;
    }
    BlockPos candidate = new BlockPos(raw.getX(), candidateY, raw.getZ());
    return hasBlockCollision.test(candidate.below()) ? candidate : raw;
}
```

The live overload requires a level, `player.onGround()`, and excludes water, passenger, and climbing states before probing `getCollisionShape(...).isEmpty()`. The projected overload keeps the same physical feet Y but probes the destination column.

- [ ] **Step 4: Run resolver tests and verify GREEN**

Run the Step 2 command. Expected: all parameterized cases pass.

- [ ] **Step 5: Commit the resolver unit**

```powershell
git add src/client/java/dev/mappywall/client/NavigationFeetResolver.java src/test/java/dev/mappywall/client/NavigationFeetResolverTest.java
git commit -m "Normalize player feet on partial blocks"
```

### Task 2: Initial planning and live-step consistency

**Files:**
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`
- Modify: `src/test/java/dev/mappywall/client/InitialPathPlanPreparerTest.java`
- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`

**Interfaces:**
- Consumes: `NavigationFeetResolver.resolve(player)`.
- Preserves: `LocalPathPlanner.stableFeetPos(...)` as a defensive snapshot fallback.

- [ ] **Step 1: Add failing partial-support prefix tests**

Create a feature test with raw physical support at Y=63 and resolved feet at Y=64. Assert the old raw origin rejects a flat first step while the normalized origin accepts it:

```java
assertTrue(preparer.prepare(new BlockPos(0, 63, 0), flatPlan).isEmpty());
assertTrue(preparer.prepare(new BlockPos(0, 64, 0), flatPlan).isPresent());
```

Add a planner fixture whose snapshot start is the normalized cell and assert the planned start and first `WALK` both remain Y=64.

- [ ] **Step 2: Run focused tests and record RED feature behavior**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.InitialPathPlanPreparerTest" --tests "dev.mappywall.client.LocalPathPlannerTest" --no-daemon
```

Expected: the new normalized integration assertion cannot yet be wired through the controller helper.

- [ ] **Step 3: Replace raw grid origins consistently**

Add one resolver field to `MovementController`. In `acceptInitialPlan`, compute `BlockPos actualFeet = feetResolver.resolve(player)` exactly once and reuse it for:

```java
request.plannedStart().distSqr(actualFeet);
initialPathPlanPreparer.prepare(actualFeet, plan);
isInitialPathPrefixLiveSafe(client, actualFeet, prepared.plan());
```

Use the resolver for initial snapshot capture, action adjacency, `isMovementStepSafe` current feet and vertical delta, non-water entry-height comparison, and player-origin navigation target Y. Change `NavigationSnapshot.capture(player)` to seed from `new NavigationFeetResolver().resolve(player)` or remove the unused convenience method if no production caller remains.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Step 2 command plus:

```powershell
.\gradlew.bat test --tests "dev.mappywall.core.InitialPathPrefixValidatorTest" --no-daemon
```

Expected: all prefix/planner tests pass; real one-block JUMP and DROP structural tests are unchanged.

- [ ] **Step 5: Commit planning integration**

```powershell
git add src/client/java/dev/mappywall/client/MovementController.java src/client/java/dev/mappywall/client/LocalPathPlanner.java src/test/java/dev/mappywall/client/InitialPathPlanPreparerTest.java src/test/java/dev/mappywall/client/LocalPathPlannerTest.java
git commit -m "Use logical feet for path validation"
```

### Task 3: Waypoint and projected-support consistency

**Files:**
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Create: `src/test/java/dev/mappywall/client/NavigationFeetMovementPolicyTest.java`

**Interfaces:**
- Consumes: `resolve(player)` and `resolveProjected(player, x, z)`.
- Produces: package-private pure Y-completion helper for grounded movement tests.

- [ ] **Step 1: Write failing movement-geometry tests**

Cover grounded physical Y values `63.9375`, `63.875`, `63.5`, and `63.125` resolving to logical waypoint Y=64. Assert WALK/JUMP/DROP Y completion uses logical Y while SWIM and airborne completion use physical Y. Add a projected-support case across two farmland-like columns and assert the destination feet cell is Y=64 rather than the colliding Y=63 cell.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --no-daemon
```

Expected: failure because waypoint completion still uses physical `player.getY()` and projected support still floors it.

- [ ] **Step 3: Integrate grounded waypoint and projection semantics**

In `isAtWaypoint`, preserve horizontal/action conditions but compute grounded non-water vertical match from `feetResolver.resolve(player).getY() - waypointY`. Keep SWIM on physical Y. Do not increase existing tolerances. In `hasSafeProjectedSupport`, replace `BlockPos.containing(projectedX, entity.getY(), projectedZ)` with `feetResolver.resolveProjected(player, projectedX, projectedZ)` before body/support checks.

- [ ] **Step 4: Run movement-policy and existing client tests**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.NavigationFeetResolverTest" --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --tests "dev.mappywall.client.InitialPathPlanPreparerTest" --no-daemon
```

Expected: all tests pass without any enlarged jump/drop tolerance.

- [ ] **Step 5: Commit movement integration**

```powershell
git add src/client/java/dev/mappywall/client/MovementController.java src/test/java/dev/mappywall/client/NavigationFeetMovementPolicyTest.java
git commit -m "Apply logical feet to movement progress"
```

### Task 4: Logical-feet regression verification

**Files:**
- Modify: `docs/design.md`

**Interfaces:**
- Documents: physical support versus integer navigation feet and the coarse collision-model boundary.

- [ ] **Step 1: Run all focused navigation suites**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.*" --tests "dev.mappywall.core.InitialPathPrefixValidatorTest" --no-daemon
git diff --check
```

Expected: zero failures and no whitespace errors.

- [ ] **Step 2: Document limitations explicitly**

State that the fix removes coordinate disagreement and infinite replanning, but the snapshot still represents collision as a boolean; thin snow/carpet and local stair shapes may conservatively appear as integer transitions until a future surface-profile model is introduced.

- [ ] **Step 3: Commit documentation**

```powershell
git add docs/design.md
git commit -m "Document logical navigation feet"
```


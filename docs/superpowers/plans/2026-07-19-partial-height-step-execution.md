# Partial-Height Step Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let aggressive-mode grounded `WALK` movement cross vanilla-step-height lips such as farmland or dirt paths into full blocks without changing planner heights, jump behavior, or movement speed.

**Architecture:** Add one pure package-level decision seam in `MovementController` that distinguishes an ordinary validated grounded `WALK` from every dedicated movement path. Only that case bypasses MappyWall's simplified pre-collision velocity projection and delegates collision/step-up to vanilla `Entity.move`; all other movement and boat projection remains unchanged.

**Tech Stack:** Java 25, Minecraft 26.2 official mappings, Fabric Loom 1.17.13, JUnit 5.14, Gradle Wrapper.

## Global Constraints

- The mod remains client-only and compatible with vanilla servers.
- Do not change `AGGRESSIVE_GROUND_SPEED`, sprint state, jump velocity, `maxUpStep`, packet cadence, waypoint tolerances, or planner vertical limits.
- Do not modify `LocalPathPlanner`, `NavigationFeetResolver`, or classify a 15/16-to-full-block edge as `JUMP`.
- Only an on-ground, non-water, non-jumping, non-sneaking, non-dismount-recovery `StepAction.WALK` may delegate collision resolution to vanilla.
- `DROP`, `SWIM`, Elytra launch, modification approach, dismount egress, and boat driving retain their existing collision handling.
- Use `apply_patch` for edits and preserve unrelated worktree changes.
- Use `E:\MappyWall\.gradle-user-home` for every Gradle command.

---

## File map

- Modify `src/client/java/dev/mappywall/client/MovementController.java`: pure delegation decision plus one explicit integration flag on aggressive movement.
- Modify `src/test/java/dev/mappywall/client/NavigationFeetMovementPolicyTest.java`: behavioral truth table and source-contract wiring regression.
- Modify `docs/design.md`: record that ordinary grounded aggressive walking now relies on vanilla bounded step-up.

### Task 1: Specify the vanilla-collision delegation boundary

**Files:**
- Modify: `src/test/java/dev/mappywall/client/NavigationFeetMovementPolicyTest.java`
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`

**Interfaces:**
- Consumes: `LocalPathPlanner.StepAction`, grounded/water/jump/sneak/dismount observations already available in `moveToward()`.
- Produces: `static boolean shouldDelegateAggressiveWalkCollisionToVanilla(StepAction, boolean, boolean, boolean, boolean, boolean)`.

- [ ] **Step 1: Write the failing policy tests**

Add these imports and tests to `NavigationFeetMovementPolicyTest`:

```java
import static org.junit.jupiter.api.Assertions.assertThrows;

@Test
void groundedOrdinaryWalkDelegatesCollisionResolutionToVanilla() {
    assertTrue(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
            LocalPathPlanner.StepAction.WALK,
            true,
            false,
            false,
            false,
            false
    ));
}

@ParameterizedTest
@EnumSource(value = LocalPathPlanner.StepAction.class, names = {
        "JUMP", "DROP", "SWIM", "BREAK", "PLACE"
})
void nonWalkActionsKeepDedicatedCollisionHandling(LocalPathPlanner.StepAction action) {
    assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
            action,
            true,
            false,
            false,
            false,
            false
    ));
}

@Test
void walkOutsideOrdinaryGroundMovementKeepsDedicatedHandling() {
    assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
            LocalPathPlanner.StepAction.WALK, false, false, false, false, false));
    assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
            LocalPathPlanner.StepAction.WALK, true, true, false, false, false));
    assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
            LocalPathPlanner.StepAction.WALK, true, false, true, false, false));
    assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
            LocalPathPlanner.StepAction.WALK, true, false, false, true, false));
    assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
            LocalPathPlanner.StepAction.WALK, true, false, false, false, true));
}

@Test
void vanillaCollisionDelegationRejectsNullActions() {
    assertThrows(NullPointerException.class, () ->
            MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                    null, true, false, false, false, false));
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --rerun-tasks
```

Expected: compilation fails because `shouldDelegateAggressiveWalkCollisionToVanilla` does not exist.

- [ ] **Step 3: Add the minimal pure decision seam**

Add this package-visible static method near `planningGapVelocity()` in `MovementController`:

```java
static boolean shouldDelegateAggressiveWalkCollisionToVanilla(
        LocalPathPlanner.StepAction action,
        boolean onGround,
        boolean inWater,
        boolean jump,
        boolean sneak,
        boolean dismountRecovering
) {
    Objects.requireNonNull(action, "action");
    return action == LocalPathPlanner.StepAction.WALK
            && onGround
            && !inWater
            && !jump
            && !sneak
            && !dismountRecovering;
}
```

- [ ] **Step 4: Run the focused test and confirm GREEN**

Run the Step 2 command again.

Expected: `NavigationFeetMovementPolicyTest` passes with no failures.

- [ ] **Step 5: Commit the tested decision seam**

```powershell
git add -- src/client/java/dev/mappywall/client/MovementController.java src/test/java/dev/mappywall/client/NavigationFeetMovementPolicyTest.java
git commit -m "Define aggressive vanilla step delegation"
```

### Task 2: Wire ordinary WALK to vanilla collision without touching other movement

**Files:**
- Modify: `src/test/java/dev/mappywall/client/NavigationFeetMovementPolicyTest.java`
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`

**Interfaces:**
- Consumes: `shouldDelegateAggressiveWalkCollisionToVanilla(...)` from Task 1.
- Produces: an eight-argument private `applyAggressiveGroundVelocity(..., boolean delegateVanillaGroundCollision)` overload; the existing seven-argument method remains the conservative entry for non-waypoint callers.

- [ ] **Step 1: Add a failing source-contract test for the production wiring**

Append this test to `NavigationFeetMovementPolicyTest`:

```java
@Test
void aggressiveWalkDelegationIsWiredOnlyThroughWaypointMovement() throws IOException {
    String source = Files.readString(Path.of(
            "src", "client", "java", "dev", "mappywall", "client", "MovementController.java"));

    int moveStart = source.indexOf("private MovementResult moveToward(");
    int moveEnd = source.indexOf("private MovementResult tickElytra(", moveStart);
    String moveSource = source.substring(moveStart, moveEnd);
    String compactMove = moveSource.replaceAll("\\s+", "");
    assertTrue(moveSource.contains("shouldDelegateAggressiveWalkCollisionToVanilla("));
    assertTrue(moveSource.contains("waypoint.action()"));
    assertTrue(moveSource.contains("dismountRecovery.active()"));
    assertTrue(compactMove.contains(
            "applyAggressiveGroundVelocity(client,player,targetX-player.getX(),"
                    + "targetZ-player.getZ(),jump,sneak,sprint,"
                    + "delegateVanillaGroundCollision);"));

    int applyStart = source.indexOf("private void applyAggressiveGroundVelocity(");
    int applyEnd = source.indexOf("private Vec3 collisionAdjustedHorizontalVelocity(", applyStart);
    String applySource = source.substring(applyStart, applyEnd);
    String compactApply = applySource.replaceAll("\\s+", "");
    assertTrue(applySource.contains("boolean delegateVanillaGroundCollision"));
    assertTrue(applySource.contains("!delegateVanillaGroundCollision"));
    assertTrue(compactApply.contains(
            "applyAggressiveGroundVelocity(client,player,dx,dz,jump,sneak,sprint,false);"));

    int boatStart = source.indexOf("private MovementResult driveBoatToward(");
    int boatEnd = source.indexOf("private AutomationStyle currentAutomationStyle()", boatStart);
    String boatSource = source.substring(boatStart, boatEnd);
    assertTrue(boatSource.contains("collisionAdjustedHorizontalVelocity("));
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --rerun-tasks
```

Expected: the new source-contract assertions fail because `moveToward()` does not call the seam and the overload flag does not exist.

- [ ] **Step 3: Compute the delegation flag in `moveToward()`**

Replace the aggressive branch in the six-parameter `moveToward(...)` method with:

```java
if (currentAutomationStyle() == AutomationStyle.AGGRESSIVE) {
    boolean delegateVanillaGroundCollision = shouldDelegateAggressiveWalkCollisionToVanilla(
            waypoint.action(),
            player.onGround(),
            player.isInWater(),
            jump,
            sneak,
            dismountRecovery.active()
    );
    applyAggressiveGroundVelocity(
            client,
            player,
            targetX - player.getX(),
            targetZ - player.getZ(),
            jump,
            sneak,
            sprint,
            delegateVanillaGroundCollision
    );
    return MovementResult.active(pathSnapshot());
}
```

- [ ] **Step 4: Preserve conservative behavior for every direct caller**

Apply this focused diff. It keeps the seven-argument entry conservative, moves the existing body into the eight-argument overload, and changes only the projection guard:

```diff
 private void applyAggressiveGroundVelocity(
         Minecraft client,
         LocalPlayer player,
         double dx,
         double dz,
         boolean jump,
         boolean sneak,
         boolean sprint
 ) {
+    applyAggressiveGroundVelocity(client, player, dx, dz, jump, sneak, sprint, false);
+}
+
+private void applyAggressiveGroundVelocity(
+        Minecraft client,
+        LocalPlayer player,
+        double dx,
+        double dz,
+        boolean jump,
+        boolean sneak,
+        boolean sprint,
+        boolean delegateVanillaGroundCollision
+) {
     clearVanillaMovementKeys(client);
@@
-    if (!jump
+    if (!delegateVanillaGroundCollision
+            && !jump
             && !shouldJump
             && !player.isInWater()
             && client.level != null) {
         Vec3 safeVelocity = collisionAdjustedHorizontalVelocity(
                 client,
                 player,
                 velocityX,
                 velocityY,
                 velocityZ
         );
         velocityX = safeVelocity.x;
         velocityZ = safeVelocity.z;
     }
```

Do not change `collisionAdjustedHorizontalVelocity()` or `driveBoatToward()`.

- [ ] **Step 5: Run the focused test and related navigation regression suite**

Run:

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationFeetResolverTest" --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --tests "dev.mappywall.client.MovementControllerDismountTest" --tests "dev.mappywall.client.MovementControllerPlanningGapTest" --tests "dev.mappywall.client.LocalPathPlannerTest" --rerun-tasks
```

Expected: all selected tests pass. In particular, the existing boat/dismount source contracts still find boat collision projection and bounded egress behavior.

- [ ] **Step 6: Commit the controller integration**

```powershell
git add -- src/client/java/dev/mappywall/client/MovementController.java src/test/java/dev/mappywall/client/NavigationFeetMovementPolicyTest.java
git commit -m "Delegate aggressive walking collision to vanilla"
```

### Task 3: Document and verify the partial-height fix

**Files:**
- Modify: `docs/design.md`

**Interfaces:**
- Consumes: the tested behavior from Tasks 1-2.
- Produces: repository design documentation and a verified build artifact.

- [ ] **Step 1: Add the execution invariant to `docs/design.md`**

Insert this bullet after the existing logical-feet bullets:

```markdown
- For an already validated ordinary `WALK`, aggressive mode leaves grounded player collision resolution to vanilla movement so `maxUpStep` can cross legal partial-height lips such as farmland and dirt paths. Dedicated drop, swim, Elytra, dismount, and boat collision handling remains unchanged; MappyWall never changes `maxUpStep` or injects vertical motion for this case.
```

- [ ] **Step 2: Run whitespace, focused tests, full tests, and build**

Run each command separately:

```powershell
git diff --check
```

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --tests "dev.mappywall.client.NavigationFeetResolverTest" --tests "dev.mappywall.client.NavigationFeetMovementPolicyTest" --tests "dev.mappywall.client.MovementControllerDismountTest" --tests "dev.mappywall.client.MovementControllerPlanningGapTest" --tests "dev.mappywall.client.LocalPathPlannerTest" --rerun-tasks
```

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test --rerun-tasks
```

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat build --rerun-tasks
```

Expected: `git diff --check` is silent; focused and full suites pass; `build` ends with `BUILD SUCCESSFUL` and produces `build/libs/mappywall-0.1.31.jar`.

- [ ] **Step 3: Commit the documentation**

```powershell
git add -- docs/design.md
git commit -m "Document vanilla partial-step execution"
```

- [ ] **Step 4: Perform manual acceptance before declaring the subsystem complete**

In aggressive mode, run cardinally and diagonally from farmland, dirt paths, and soul sand onto full blocks. Confirm there is no hop, stall, or replan. Then run into a real wall and traverse a planned full one-block climb; confirm the wall remains blocked and the climb still uses the existing `JUMP` behavior. Recheck one boat segment and one dismount recovery so their collision paths remain unchanged.

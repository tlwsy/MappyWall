# Linux Handoff: Partial-Step and Confirmed Water Transit

## Purpose

This document is the durable handoff for completing the selected navigation
pipeline repair on Linux. It replaces the local `.superpowers/sdd/progress.md`
as a migration reference: that directory is intentionally ignored and its
older sections contain stale `pending` labels for work already present in Git.

The implementation baseline is commit
`9a3c9ac0defc3164269a412f746e16471841a886` on branch
`26.2/navigation-pipeline-repair`. That commit contains the final design and
the two executable plans, but it contains none of the new partial-step or water
transit production implementation. A later documentation-only handoff commit
may be the branch tip; verify that the baseline is an ancestor rather than
requiring the tip to equal it exactly.

At the handoff point, the branch and its remote tracking branch were clean and
synchronized, and the full Windows baseline command
`gradlew.bat test --no-daemon` completed successfully on 2026-07-20.

## Authoritative Inputs

Read these files completely before editing code, in this order:

1. `AGENTS.md`
2. `docs/superpowers/specs/2026-07-19-partial-step-and-water-transit-design.md`
3. `docs/superpowers/plans/2026-07-19-partial-height-step-execution.md`
4. `docs/superpowers/plans/2026-07-19-confirmed-water-transit.md`
5. the source and test files named by each task before starting that task

The plans already incorporate the final review corrections, including exact
partial-step wiring contracts, soul-sand manual coverage,
`noBlockCollision` protection, and reuse of
`currentWaterTravelDecision` to avoid a duplicate same-tick evidence scan.
Do not restart design or substitute one of the two fallback strategy branches.

## Repository and Linux Bootstrap

For a fresh clone:

```bash
git clone https://github.com/tlwsy/MappyWall.git
cd MappyWall
git fetch origin --prune
git switch -c 26.2/navigation-pipeline-repair \
  --track origin/26.2/navigation-pipeline-repair
git merge-base --is-ancestor \
  9a3c9ac0defc3164269a412f746e16471841a886 HEAD
git status --short --branch
```

For an existing clone, preserve any local changes, then use `git fetch`,
`git switch 26.2/navigation-pipeline-repair`, and `git pull --ff-only` only
when the worktree is clean.

Linux requires JDK 25. The `gradlew` file currently has Git mode `100644`, so
use `bash ./gradlew` unless a separate executable-bit commit is made. Do not
copy the plans' Windows `E:\MappyWall\.gradle-user-home` value literally;
override it for Linux:

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
bash ./gradlew test --no-daemon
```

This is a fresh Linux baseline gate. If it fails, diagnose the environment or
pre-existing failure before changing production behavior.

## Work That Remains

All eleven implementation tasks below are pending:

- Partial-step Task 1: add the pure vanilla-collision delegation policy seam.
- Partial-step Task 2: integrate it only into ordinary aggressive grounded
  `WALK` execution.
- Partial-step Task 3: update design documentation and run focused, full, and
  manual verification.
- Water Task 1: model confirmed water-run distance and eligibility.
- Water Task 2: persist and validate the configurable 12-block threshold.
- Water Task 3: resolve submerged accepted route cells to real boatable
  surfaces.
- Water Task 4: model bounded boat acquisition and confirmation.
- Water Task 5: integrate water evidence, completed distance, mounted waypoint
  completion, and deep-water traversal into the controller.
- Water Task 6: replace cooldown-only placement with confirmed acquisition.
- Water Task 7: wire the setting through the runtime and navigation screen.
- Water Task 8: complete documentation, automated verification, JAR checks,
  and manual acceptance.

Execute them in the order written in the plans. Use task-sized commits so a
review or regression can be isolated without discarding later work.

## Required Execution Discipline

For every task:

1. Inspect the current implementation and confirm the plan still matches its
   real signatures and mappings.
2. Write and run the specified failing test first. Confirm it fails for the
   intended missing behavior, not because of a compile typo or environment
   problem.
3. Make the smallest production change with `apply_patch`.
4. Run the task's focused tests and then the relevant broader suite.
5. Inspect the diff and commit only that task with an imperative message.
6. Have a fresh reviewer compare the task brief, implementation report, exact
   base-to-head diff, global constraints, and test evidence. Fix every valid
   finding and re-review before starting the next task.
7. Record the task's base/head commits, commands, results, and review status in
   a new local `.superpowers/sdd/progress.md` ledger if the orchestration system
   supports it.

Only one implementation agent may edit the shared worktree at a time. Read-only
analysis and review may run in parallel. Do not blindly paste plan snippets if
the current source proves a signature has changed; investigate and update the
smallest affected plan assumption with evidence.

## Non-Negotiable Behavior and Safety

### Partial-height transition

- Only an on-ground, non-water, non-jumping, non-sneaking,
  non-dismount-recovery `StepAction.WALK` in aggressive mode may delegate
  collision resolution to vanilla movement.
- `DROP`, `SWIM`, Elytra launch, modification approach, boat driving, and
  dismount egress retain their current conservative collision handling.
- Do not change `AGGRESSIVE_GROUND_SPEED`, sprint state, jump velocity,
  `maxUpStep`, packet cadence, waypoint tolerances, planner vertical limits,
  `LocalPathPlanner`, or `NavigationFeetResolver` to make the test pass.
- The fix must not synthesize a hop or reclassify the 15/16-height transition
  as `JUMP`.

### Confirmed water transit

- The default threshold is exactly 12 blocks and persisted values are integers
  in the inclusive range 1 through 128. Distances below 12 swim; exactly 12 or
  more may acquire a boat. Normal style always evaluates with 12; the custom
  persisted threshold applies to aggressive style.
- Count only active accepted steps and an accepted `CONTINUOUS` buffer. Never
  count pending, stale, hidden-retreat, unloaded, obstructed, or guessed
  terrain.
- Accumulate a completed prefix exactly once. Preserve it only across a
  compatible same-target replan; reset it on a disconnected/non-water run,
  automation-style change, target/world change, pause, hard reset, or terminal
  transition.
- Resolve submerged `SWIM` cells to the actual compatible surface. Boat hull,
  waypoint, and water-run surfaces must agree before mounted completion or
  dismount decisions.
- Short crossings ignore both nearby and carried boats. Long crossings prefer
  an empty boat on the accepted corridor, otherwise a carried boat.
- While approaching a compatible existing boat or the true surface from deep
  water, preserve vertical progress and suspend the absolute waypoint timer
  only for a real bounded approach; ordinary stalls must still time out.
- Validate the placement envelope with `noBlockCollision`, not `noCollision`,
  so the player or an existing boat does not invalidate route proof.
- Treat `InteractionResult.consumesAction()` as client prediction only. Confirm
  placement with a new accepted-corridor boat entity ID absent from the
  pre-placement baseline, and confirm boarding only through passenger state.
- Use bounded retries and the specified 40-tick rejected-placement backoff.
  Failure must continue swimming rather than consume boats or loop forever.
- After a non-hotbar inventory swap dispatch, call `selectionRequested` once
  when its preconditions held; do not repeatedly send swap packets merely
  because the existing helper returns `false` in that tick.
- Opening a GUI may defer inventory/world transactions, but aggressive
  swimming must remain uninterrupted.
- `nextWaypoint` is the sole evidence refresh for the final `SWIM` step;
  `swimOrBoat` consumes the stored decision without rescanning the same columns
  in that controller tick.
- Do not increase any walking, sprinting, swimming, boat, jump, placement,
  breaking, or packet-rate constant. Keep all Minecraft world/entity/chunk/
  inventory reads on the client thread and preserve vanilla-server
  compatibility.
- Existing server-confirmed dismount recovery and original-boat reboarding
  suppression remain authoritative.

## Completion Gates

Run every focused command specified by the plans. Before declaring the branch
complete, run fresh full gates on the final commit:

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
bash ./gradlew test --no-daemon --rerun-tasks
bash ./gradlew clean build --no-daemon --rerun-tasks
git diff --check \
  9a3c9ac0defc3164269a412f746e16471841a886..HEAD
git diff --check

python3 -m json.tool \
  src/client/resources/assets/mappywall/lang/zh_cn.json >/dev/null
python3 -m json.tool \
  src/client/resources/assets/mappywall/lang/en_us.json >/dev/null

jar_file='build/libs/mappywall-0.1.31.jar'
python3 - "$jar_file" <<'PY'
import json
import sys
import zipfile

required = {
    "fabric.mod.json",
    "dev/mappywall/core/WaterTransitPolicy.class",
    "dev/mappywall/core/BoatAcquisitionPolicy.class",
    "dev/mappywall/client/WaterRouteEvidenceAdapter.class",
}

with zipfile.ZipFile(sys.argv[1]) as archive:
    entries = set(archive.namelist())
    missing = sorted(required - entries)
    if missing:
        raise SystemExit(f"Missing JAR entries: {missing}")
    metadata = json.loads(archive.read("fabric.mod.json"))

entrypoints = metadata.get("entrypoints", {})
if metadata.get("environment") != "client":
    raise SystemExit("fabric.mod.json is not client-only")
if "client" not in entrypoints:
    raise SystemExit("fabric.mod.json has no client entrypoint")
if "main" in entrypoints or "server" in entrypoints:
    raise SystemExit("fabric.mod.json contains a server-capable entrypoint")
PY
```

An independent final diff review must also confirm that the implementation did
not add a server initializer, custom protocol, or server-required packet class;
the metadata/JAR assertions alone cannot prove that architectural boundary.

Then perform the specification's nine real-client scenarios in aggressive
mode. They include cardinal and diagonal farmland/dirt-path/soul-sand exits,
a real wall and normal one-block jump, 3/11/12/>12-block crossings, rolling
lookahead qualification, submerged waypoints, an accepted-corridor existing
boat, bidirectional style switching with a custom value of 37, GUI/rejected/
delayed-placement cases, and shore dismount recovery.

Do not report manual validation as passed unless it was actually performed.
If no graphical or server test environment is available, report automated
completion separately and leave the exact manual matrix explicitly pending.

## Final Handoff Output

On completion, push `26.2/navigation-pipeline-repair` and report:

- the final commit and each task commit;
- files and behavior changed;
- focused, full-test, build, JSON, JAR, and `git diff --check` evidence;
- independent review findings and how they were resolved;
- manual scenarios actually executed versus those still pending;
- any residual risk or deliberately deferred work.

Do not merge, rewrite shared history, or move the two fallback strategy
branches unless the user explicitly requests it.

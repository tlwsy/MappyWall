# Repository Guidelines

## Current Project and Layout

MappyWall is an implemented Fabric client mod targeting Minecraft `26.2`. The
project uses Java `25`, Fabric Loader `0.19.3`, Fabric API `0.152.2+26.2`, and
Fabric Loom `1.17.13` with the official Minecraft names.

- `src/main/java/dev/mappywall/core/`: pure planning, map-grid, persistence,
  and policy logic that must remain testable without launching Minecraft.
- `src/client/java/dev/mappywall/client/`: Minecraft/Fabric integration,
  movement and map-opening controllers, HUD/settings UI, and client mixins.
- `src/main/resources/`: `fabric.mod.json` and common mod metadata.
- `src/client/resources/`: client mixin configuration, icon, and translations.
- `src/test/java/`: deterministic core, client-policy, integration-contract,
  persistence, and regression tests.
- `docs/`: design specifications, implementation plans, build notes, and
  handoff records.

Keep world and entity access on the client thread. Keep reusable decisions in
pure classes wherever possible so they can be exercised by ordinary JUnit
tests.

## Build, Test, and Development Commands

Set a repository-local Gradle cache when working in an isolated environment:

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
```

The checked-in Linux wrapper currently has Git mode `100644`, so invoke it
through Bash unless its executable bit is deliberately committed:

```bash
bash ./gradlew test --no-daemon
bash ./gradlew build --no-daemon
bash ./gradlew runClient
```

On Windows, use the equivalent `gradlew.bat` commands. There is currently no
isolated core-only test task: `-PenableFabric=false test` still discovers tests
that import client/Minecraft classes and is not a valid fallback. No formatter
task is currently configured.

Do not commit generated output such as `build/`, `.gradle/`,
`.gradle-user-home/`, `run/`, logs, IDE state, or local SDD scratch files.

## Coding Style and Boundaries

Use Java consistently with 4-space indentation, descriptive names, focused
classes, and immutable records/value objects where appropriate. Preserve the
split between automatic movement, map opening, HUD guidance, persistence, and
pure route policy. Automation must remain behind an explicit user-facing
toggle.

Do not hide movement defects by increasing speed, jump power, packet cadence,
waypoint tolerances, or planner height limits. Prefer a narrow policy seam and
tests that distinguish the intended transition from every conservative path.

## Testing Guidelines

Use test-driven development for behavior changes: establish a meaningful RED,
implement the minimum production change, then run focused and full regression
tests. Name tests after behavior, for example
`resumesAfterManualMapOpen()` or `plansNextRegionForScale4()`.

Before handing off a task, run the plan-specific focused tests followed by the
full `test` and `build` tasks. Run `git diff --check <task-base>..HEAD` to
inspect committed task changes and ordinary `git diff --check` for remaining
worktree changes. Manual navigation acceptance must cover vanilla-compatible
servers, slow chunk generation, walking and swimming, boat placement/boarding/
dismount, partial-height blocks, Elytra/firework mode, GUI deferral, and pause/
resume recovery. Never report a manual scenario as passed unless it was
actually exercised in a client.

## Plans, Progress, Commits, and Reviews

Read the applicable specification and implementation plan completely before
editing. Current Linux migration state is recorded in
`docs/handoff/2026-07-20-linux-navigation-handoff.md`.

`.superpowers/sdd/` is ignored scratch state and is not a durable source of
truth after cloning. Maintain a fresh local ledger there if useful, but derive
authoritative scope from tracked plans, Git history, tests, and handoff docs.

Use concise imperative commits and keep them task-scoped. Record focused/full
test evidence and independently review each completed plan task before moving
on. Preserve unrelated worktree changes and never use destructive Git cleanup
to make a mixed tree appear clean.

## Server Compatibility and Safety

The mod must remain client-only and able to join vanilla servers. Do not add
server-side blocks, items, entities, custom packets, or behavior that assumes a
Fabric server installation. Treat automation as optional and clearly visible
because some servers may prohibit automated movement or rapid chunk
generation.

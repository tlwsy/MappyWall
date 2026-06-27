# Repository Guidelines

## Project Structure & Module Organization

This repository is currently empty and is intended to become a Fabric client mod for Minecraft 26.1.2. Use a conventional Gradle/Fabric layout once the project is scaffolded:

- `src/main/java/` or `src/main/kotlin/`: client mod source code.
- `src/main/resources/`: `fabric.mod.json`, mixin config, assets, and client-only metadata.
- `src/test/`: unit tests for route planning, map-grid math, and inventory ordering logic.
- `docs/`: design notes for map-wall planning, HUD flows, and server compatibility constraints.

Keep client-only features isolated from pure planning logic so route calculations can be tested without launching Minecraft.

## Build, Test, and Development Commands

After Fabric Loom is added, prefer these commands:

- `./gradlew runClient` or `gradlew.bat runClient`: launch a local Minecraft client for manual testing.
- `./gradlew test`: run unit tests.
- `./gradlew build`: compile, test, and produce the mod jar.
- `./gradlew spotlessApply` or `./gradlew format`: format code, if a formatter is configured.

Do not commit generated build output such as `build/`, `.gradle/`, or local run directories.

## Coding Style & Naming Conventions

Use Java or Kotlin consistently once chosen. Prefer 4-space indentation, descriptive class names, and small services with focused responsibilities. Suggested naming:

- `MapWallPlanner` for grid and coordinate planning.
- `MapOpenController` for empty-map handling.
- `HudProgressRenderer` for HUD state display.
- `InventoryMapIndex` for persisted map-id to wall-position mapping.

Separate automatic movement from map opening and HUD guidance. Automatic movement must remain behind an explicit user-facing toggle.

## Testing Guidelines

Prioritize deterministic tests for map scale math, next-region selection, pause/resume recovery, non-contiguous map IDs, and inventory ordering. Name tests after behavior, for example `resumesAfterManualMapOpen()` or `plansNextRegionForScale4()`.

Manual testing should include vanilla-server joins, slow chunk generation, walking mode, Elytra/firework mode, and paused route recovery.

## Commit & Pull Request Guidelines

There is no existing commit history yet. Use concise imperative commits, for example `Add map wall planner` or `Implement HUD progress state`. Pull requests should include a short summary, test results, screenshots or clips for GUI/HUD changes, and any server-compatibility notes.

## Server Compatibility & Safety

This mod must be client-only and able to join vanilla servers. Do not add server-side blocks, items, entities, packets requiring a Fabric server, or behavior that assumes server installation. Prefer HUD prompts, route hints, map data reading, and inventory organization. Treat automation as optional and clearly visible because some servers may forbid automated movement or fast chunk generation.

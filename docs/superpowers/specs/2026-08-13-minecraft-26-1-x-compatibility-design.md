# Minecraft 26.1.x Compatibility Design

## Goal

Publish MappyWall for Minecraft `26.1`, `26.1.1`, and `26.1.2` while
preserving the current client-only behavior and navigation safety boundaries.
Prefer one release JAR that runs on all three versions. If real compatibility
evidence shows that a shared binary is unsafe, produce three version-specific
JARs from the same source tree instead.

Minecraft `26.2` is outside this release. The metadata must reject it even
though this branch started from the existing 26.2 implementation.

## Release Strategy

The first release candidate is one JAR compiled against the oldest supported
game version, Minecraft `26.1`. Compiling against the lowest version prevents
the release bytecode from accidentally depending on classes or methods added
only by `26.1.1` or `26.1.2`.

The JAR metadata declares the exact supported interval:

```text
>=26.1 <=26.1.2
```

This includes only the three requested releases. It excludes Minecraft
`26.2` and does not silently opt into a future, unverified `26.1.3`.

The repository also exposes build-matrix targets for all three versions. The
`26.1.1` and `26.1.2` matrix builds are compatibility diagnostics; they do not
become extra release artifacts while the shared JAR remains viable.

This compatibility release uses mod version
`0.1.33+mc26.1-26.1.2`, producing the exact shared candidate filename
`mappywall-0.1.33+mc26.1-26.1.2.jar`.

## Version Matrix

Use Java `25`, Fabric Loader `0.19.3`, and Fabric Loom `1.17.13` for every
target. Pin the Minecraft and Fabric API coordinates together so a command
cannot accidentally pair a game version with an API for another game release.

| Target | Minecraft | Fabric API | Purpose |
|---|---|---|---|
| `26.1` | `26.1` | `0.145.1+26.1` | Default build and shared release JAR |
| `26.1.1` | `26.1.1` | `0.145.4+26.1.1` | Compatibility build and test |
| `26.1.2` | `26.1.2` | `0.155.2+26.1.2` | Compatibility build and test |

These Fabric API versions are the latest matching releases present in the
official Fabric Maven metadata when this design was written. Fabric API stays
an external client dependency and is not bundled into MappyWall.

## Source Compatibility

The pure classes under `src/main/java` remain shared and unchanged unless a
test exposes an actual migration defect. Persistence formats, project saves,
navigation policies, movement constants, and user-facing automation toggles
must retain their current behavior.

The client integration must audit APIs changed by the 26.2 migration and use
behavior-preserving code shared by all three 26.1.x targets. Git history
identifies three known seams:

- screen transitions use `setScreenAndShow`, which is available on all three
  supported targets and must be retained to preserve its immediate render;
- block-center calculations can use version-neutral coordinate math with an
  exact negative-coordinate behavior contract;
- world path rendering uses the historical 26.1.x direct-buffer render path.

Prefer direct 26.1.x public APIs or version-neutral calculations. Do not add
reflection, runtime bytecode probing, optional class loading, or conditional
Mixins merely to preserve a single JAR. All three supported releases belong to
one patch line, so a narrow common implementation is the maintainable target.

After these known seams compile, the version matrix is authoritative for any
additional source or Mixin signature differences. Fix only demonstrated
compatibility gaps and keep Minecraft world and entity access on the client
thread.

## Build Interface and Artifacts

The default Gradle invocation targets Minecraft `26.1` and produces the one
candidate release JAR. An immutable `supportedMinecraftTargets` map in
`build.gradle` selects each supported target by one stable property and derives
both the Minecraft and Fabric API coordinates from it.

Each version target must support the repository-standard commands through
Bash:

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
bash ./gradlew test --no-daemon --rerun-tasks -Pminecraft_target=26.1
bash ./gradlew clean build --no-daemon --rerun-tasks -Pminecraft_target=26.1
```

The same commands run with `26.1.1` and `26.1.2`. Unsupported target values
fail during configuration with an error listing the three accepted values.

Diagnostic builds may temporarily produce ordinary Gradle output under
`build/`, but only the default `26.1` build is handed off as the shared release
candidate. Its filename is
`mappywall-0.1.33+mc26.1-26.1.2.jar`.

If the shared binary fails the compatibility decision below, switch the
artifact layout to:

```text
mappywall-0.1.33+mc26.1.jar
mappywall-0.1.33+mc26.1.1.jar
mappywall-0.1.33+mc26.1.2.jar
```

The fallback changes only build inputs, metadata, and version-specific client
adapter sources proven necessary by failures. It must not fork the planner,
persistence model, or navigation behavior into three maintained copies.

## Automated Verification

For each target, run focused migration checks followed by the complete `test`
and `clean build` tasks. The matrix is successful only when all three targets
compile their client source, run the same test suite, and assemble a JAR.

Inspect the default candidate JAR and assert that:

- `fabric.mod.json` declares Minecraft `>=26.1 <=26.1.2`;
- the environment remains `client` and only the client entrypoint exists;
- the client Mixin configuration is present;
- representative core, client, and Mixin classes are present;
- Fabric API is not bundled into the JAR;
- no server initializer, custom protocol, or server-required packet is added.

Run `git diff --check` for both committed branch changes and remaining
worktree changes. JSON resources must parse successfully.

Compiling the source against all three targets proves source compatibility,
but it does not by itself prove that the lowest-version bytecode links and
behaves correctly in every newer client. The shared candidate therefore
remains conditional on the real-client checks below.

## Manual Verification and Ownership

The user will manually test the exact same shared candidate JAR in clean
Fabric client instances for Minecraft `26.1`, `26.1.1`, and `26.1.2`, each
with the matching Fabric API. The implementation handoff must provide a short
checklist and identify the exact JAR hash so all three runs exercise the same
binary.

At minimum, each version must demonstrate:

1. Fabric Loader accepts the Minecraft dependency range and reaches the main
   menu without MappyWall linkage or Mixin errors.
2. A world can be opened and the MappyWall settings and task screens can be
   opened, closed, and reopened.
3. HUD and world target/path rendering execute without crashing.
4. An existing project save can be read and written without format changes.
5. Manual mode and the explicit automation toggle remain available.

The broader navigation acceptance matrix already recorded in `AGENTS.md` and
the Linux handoff remains applicable. Results must be reported as executed or
pending; automated builds must never be presented as proof that a real-client
scenario passed.

## Single-JAR Decision and Fallback

Keep the shared JAR only if all automated matrix gates pass and the user
reports that the identical candidate starts and completes the compatibility
smoke checklist on all three Minecraft versions.

Fall back to three JARs when any of the following is observed and cannot be
removed with a direct public API common to all three releases:

- a source or Mixin signature cannot compile across the matrix;
- the shared candidate has a missing class, method, or field at startup;
- a Mixin fails to apply on any supported version;
- screen, HUD, or world rendering needs incompatible method signatures;
- a supported version requires behavior-changing compatibility code.

The fallback is evidence-driven. A transient dependency-download failure is a
build-environment problem and does not by itself justify multiple JARs.

## Safety and Non-Goals

- Preserve client-only and vanilla-server compatibility.
- Keep automation optional, visible, pausable, and emergency-stoppable.
- Do not increase movement speed, jump power, packet cadence, waypoint
  tolerance, planner height limits, or any other navigation constant.
- Do not add server-side content, custom packets, or a server entrypoint.
- Do not add 26.2 to the supported metadata or release artifacts.
- Do not redesign navigation, persistence, or UI behavior as part of this
  migration.
- Do not claim manual compatibility before the user reports the actual runs.

## Completion Criteria

The migration implementation is ready for manual handoff when the default
shared JAR is built against Minecraft `26.1`, all three automated target
matrices pass, artifact and metadata checks pass, documentation names exactly
the three supported releases, and the user receives the candidate hash and
manual checklist.

Final single-JAR compatibility is accepted only after the user completes the
three-version smoke test. If that evidence reveals a real incompatibility, the
same branch proceeds with the documented multi-JAR fallback.

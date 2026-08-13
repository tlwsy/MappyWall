# Build Notes

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
must not replace the release candidate. The guarded target map rejects every
other value, including `26.2`.

## Default build

The default build now compiles the Fabric client, pure core planner, persistence, binding recovery, and tests:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Use `GRADLE_USER_HOME` inside the repository if the machine-wide Gradle cache has native library problems:

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
.\gradlew.bat test
```

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

## Release candidate and manual acceptance

Minecraft 26.1.x official unobfuscated names are the runtime names, so the
ordinary `jar` output is the distributable artifact. Build and validate its
exact filename, metadata, client-only contents, and Minecraft dependency range
with:

```bash
bash ./gradlew clean verifyReleaseJar --no-daemon --rerun-tasks \
  -Pminecraft_target=26.1
sha256sum build/libs/mappywall-0.1.33+mc26.1-26.1.2.jar
```

The user must install that same SHA-256-identical file in matching Fabric API
environments for Minecraft 26.1, 26.1.1, and 26.1.2. In each version, record
that the loader reaches the main menu without linkage or Mixin errors; a world
and the task/settings screens open, close, and reopen; HUD and path rendering
run without a crash; an existing project save reads and writes without a
format change; and manual mode plus the explicit automation toggle remain
available. Full navigation scenarios—including vanilla-compatible servers,
slow chunk generation, walking, swimming, boats, partial-height blocks,
Elytra/fireworks, GUI deferral, and pause/resume recovery—remain pending until
the user actually exercises and reports them in a client. Keep the one-JAR
result only after all three version reports pass.

## Proxy for China network environments

If Gradle downloads from `services.gradle.org`, Fabric Maven, or Mojang libraries are slow or time out, use the local proxy. Gradle's JVM networking worked reliably with SOCKS settings in this environment:

```powershell
$env:GRADLE_OPTS='-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=7890'
.\gradlew.bat build
```

HTTP proxy environment variables were useful for ad-hoc metadata checks, but Gradle artifact downloads were more reliable with `GRADLE_OPTS`.

## Fabric-disabled diagnostic only

There is currently no isolated core-only test source set. Do not use
`-PenableFabric=false test` as a fallback: the ordinary test source set still
contains tests that import client and Minecraft classes.

The pure `src/main/java` sources can be compiled as a limited diagnostic
without applying Loom, but this does not validate the client, tests, or release
jar:

```powershell
.\gradlew.bat -PenableFabric=false compileJava
```

## Gradle wrapper

The wrapper is generated for Gradle `9.6.0`. With the SOCKS proxy above, the wrapper successfully downloaded the distribution and ran `test`.

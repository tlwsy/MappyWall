# Build Notes

## Current target

The current development target is Minecraft `1.21.11` with Yarn `1.21.11+build.6`, Fabric Loader `0.19.3`, Fabric API `0.140.2+1.21.11`, and Java `21`.

Minecraft `26.1.2` remains the long-term target from the original brief, but its mappings were not usable during the initial implementation pass. Development is temporarily focused on `1.21.11` so the client mod can compile and the MVP can keep moving.

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

## Proxy for China network environments

If Gradle downloads from `services.gradle.org`, Fabric Maven, or Mojang libraries are slow or time out, use the local proxy. Gradle's JVM networking worked reliably with SOCKS settings in this environment:

```powershell
$env:GRADLE_OPTS='-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=7890'
.\gradlew.bat build
```

HTTP proxy environment variables were useful for ad-hoc metadata checks, but Gradle artifact downloads were more reliable with `GRADLE_OPTS`.

## Core-only fallback

If mappings or remote dependencies are temporarily unavailable, the pure core can still be tested without compiling the Minecraft client source:

```powershell
.\gradlew.bat -PenableFabric=false test
```

## Gradle wrapper

The wrapper is generated for Gradle `9.6.0`. With the SOCKS proxy above, the wrapper successfully downloaded the distribution and ran `test`.

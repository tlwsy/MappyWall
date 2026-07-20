# Build Notes

## Current target

The current development target is Minecraft `26.2`, Fabric Loader `0.19.3`, Fabric API `0.152.2+26.2`, Fabric Loom `1.17.13`, and Java `25`.

Minecraft 26.x is unobfuscated, so this branch uses the official game names directly and does not declare a Yarn mappings dependency. Other Minecraft releases remain on their version-specific branches.

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

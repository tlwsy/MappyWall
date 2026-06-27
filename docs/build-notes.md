# Build Notes

## Current default build

The default build compiles the pure core planner, persistence, binding recovery, and tests:

```powershell
gradle test
gradle build
```

Use `GRADLE_USER_HOME` inside the repository if the machine-wide Gradle cache has native library problems:

```powershell
$env:GRADLE_USER_HOME='E:\MappyWall\.gradle-user-home'
gradle test
```

## Fabric client build gate

Fabric Loader `0.19.3` and Fabric API `0.153.0+26.1.2` are available for Minecraft `26.1.2`, but as of this implementation pass Loom cannot find official Mojang mappings for `26.1.2`, and Fabric Yarn metadata returns an empty list.

The Fabric/Loom configuration is therefore behind an explicit property:

```powershell
gradle -PenableFabric=true build
```

Once mappings are published, this command should become the main mod build command. Until then, the client source under `src/client/java` documents the intended Fabric integration and the default build keeps the testable core green.

## Gradle wrapper

The wrapper is generated for Gradle `9.6.0`. This environment timed out when downloading from `services.gradle.org`, while the installed `gradle` command worked with the repository-local `GRADLE_USER_HOME`.


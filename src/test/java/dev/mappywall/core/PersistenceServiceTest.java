package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceServiceTest {
    @Test
    void savesAndLoadsProjectState(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("test-project", "local", "minecraft:overworld", 1, 2, 2, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());

        persistence.save(path, save);

        assertTrue(Files.exists(path));
        MapWallSave loaded = persistence.load(path).orElseThrow();
        assertEquals(save.project().id(), loaded.project().id());
        assertEquals(save.route().size(), loaded.route().size());
        assertEquals(save.session().currentStep(), loaded.session().currentStep());
    }

    @Test
    void savesAndLoadsMultipleMapIdsForOneRegion(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "map-aliases", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave save = planner.createSave(project);
        RouteStep step = save.route().getFirst();
        save = save.withBindings(List.of(
                new MapBinding(
                        step.wallPos(), step.region().signature(), 4, Instant.EPOCH, BindingVerification.MAP_STATE
                ),
                new MapBinding(
                        step.wallPos(), step.region().signature(), 5, Instant.EPOCH, BindingVerification.MAP_STATE
                )
        ));
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());

        persistence.save(path, save);

        MapWallSave loaded = persistence.load(path).orElseThrow();
        assertEquals(List.of(4, 5), loaded.bindingsForRegion(step.region().signature()).stream()
                .map(MapBinding::mapId)
                .toList());
    }

    @Test
    void savesAndLoadsPendingCartographyLineage(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "pending-zoom", "local", "minecraft:overworld", 2, 1, 1, 0, 0, RunMode.MANUAL
        );
        PendingMapZoom pending = new PendingMapZoom(
                planner.planRoute(project).getFirst().region().signature(),
                4,
                1,
                java.util.Set.of(4, 8, 11),
                2,
                Instant.parse("2026-07-13T00:00:00Z")
        );
        MapWallSave initial = planner.createSave(project);
        MapWallSave save = initial.withSession(initial.session()
                .withPendingMapZoom(pending)
                .withKnownMapScale(12, 1));
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());

        persistence.save(path, save);

        MapWallSave loaded = persistence.load(path).orElseThrow();
        assertEquals(pending, loaded.session().pendingMapZoom());
        assertEquals(1, loaded.session().knownScaleForMapId(12));
    }

    @Test
    void loadsLegacyPendingCartographyWithoutTimestampAsStale(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-pending-zoom", "local", "minecraft:overworld", 2, 1, 1, 0, 0, RunMode.MANUAL
        );
        PendingMapZoom pending = new PendingMapZoom(
                planner.planRoute(project).getFirst().region().signature(),
                4,
                1,
                java.util.Set.of(4),
                1,
                Instant.parse("2026-07-13T00:00:00Z")
        );
        MapWallSave initial = planner.createSave(project);
        MapWallSave save = initial.withSession(initial.session().withPendingMapZoom(pending));
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());
        persistence.save(path, save);
        String legacyJson = Files.readString(path).replaceFirst(
                ",\\s*\"startedAt\"\\s*:\\s*\"[^\"]+\"",
                ""
        );
        Files.writeString(path, legacyJson);

        PendingMapZoom loaded = persistence.load(path).orElseThrow().session().pendingMapZoom();

        assertEquals(Instant.EPOCH, loaded.startedAt());
    }

    @Test
    void loadsLegacySessionWithoutMapScaleLineage(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-scale-lineage", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());
        persistence.save(path, planner.createSave(project));
        String legacyJson = Files.readString(path).replaceFirst(
                ",\\s*\"knownMapScales\"\\s*:\\s*\\{[^}]*}",
                ""
        );
        Files.writeString(path, legacyJson);

        RunSessionState loaded = persistence.load(path).orElseThrow().session();

        assertTrue(loaded.knownMapScales().isEmpty());
    }

    @Test
    void savesAndLoadsPendingMapOpeningLineage(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "pending-open", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        PendingMapOpening pending = new PendingMapOpening(
                planner.planRoute(project).getFirst().region().signature(),
                java.util.Set.of(4, 8),
                7,
                Instant.EPOCH
        );
        MapWallSave initial = planner.createSave(project);
        MapWallSave save = initial.withSession(initial.session().withPendingMapOpening(pending));
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());

        persistence.save(path, save);

        assertEquals(pending, persistence.load(path).orElseThrow().session().pendingMapOpening());
    }

    @Test
    void recoversLastGoodBackupWhenPrimaryJsonIsTruncated(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "backup-project", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave first = planner.createSave(project);
        MapWallSave second = first.withProject(first.project().withStatus(ProjectStatus.PAUSED));
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());

        persistence.save(path, first);
        persistence.save(path, second);
        Files.writeString(path, "{\"schemaVersion\":1,");

        MapWallSave recovered = persistence.load(path).orElseThrow();
        assertEquals(ProjectStatus.RUNNING, recovered.project().status());
        assertTrue(Files.isRegularFile(persistence.backupPath(path)));
    }

    @Test
    void malformedJsonWithoutBackupLoadsAsEmpty(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("truncated.json");
        Files.writeString(path, "{\"project\":");

        assertTrue(new PersistenceService().load(path).isEmpty());
    }

    @Test
    void savingOverCorruptPrimaryPreservesKnownGoodBackup(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "preserve-backup", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave first = planner.createSave(project);
        MapWallSave second = first.withProject(first.project().withStatus(ProjectStatus.PAUSED));
        MapWallSave third = first.withProject(first.project().withStatus(ProjectStatus.STOPPED));
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());

        persistence.save(path, first);
        persistence.save(path, second);
        Files.writeString(path, "not-json");
        persistence.save(path, third);

        assertEquals(ProjectStatus.STOPPED, persistence.load(path).orElseThrow().project().status());
        assertEquals(
                ProjectStatus.RUNNING,
                persistence.load(persistence.backupPath(path)).orElseThrow().project().status()
        );
        Files.delete(path);
        assertTrue(persistence.load(path).isEmpty());
    }

    @Test
    void loadsLegacySaveWithoutDirectionFieldsAndInfersThemFromRoute(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-directions",
                "local",
                "minecraft:overworld",
                0,
                2,
                2,
                0,
                0,
                RunMode.MANUAL,
                WallAnchorMode.FIRST_REGION,
                -1,
                -1
        );
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());
        persistence.save(path, planner.createSave(project));
        String legacyJson = Files.readString(path).replaceFirst(
                ",\\s*\"columnStepX\"\\s*:\\s*-?\\d+\\s*,\\s*\"rowStepZ\"\\s*:\\s*-?\\d+",
                ""
        );
        Files.writeString(path, legacyJson);

        MapWallSave loaded = persistence.load(path).orElseThrow();
        assertEquals(-1, loaded.project().columnStepX());
        assertEquals(-1, loaded.project().rowStepZ());
        assertFalse(loaded.route().isEmpty());
    }

    @Test
    void loadsMissingBindingRevisionAsLegacyAndMigratesIt(@TempDir Path tempDir) throws Exception {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-binding-revision", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        PersistenceService persistence = new PersistenceService();
        Path path = persistence.projectPath(tempDir, project.serverKey(), project.dimension(), project.id());
        MapWallSave save = planner.bindCurrentStep(
                planner.createSave(project), 4, Instant.EPOCH, BindingVerification.MAP_STATE
        );
        persistence.save(path, save);
        String legacyJson = Files.readString(path).replaceFirst(
                ",\\s*\"bindingDataVersion\"\\s*:\\s*1",
                ""
        );
        Files.writeString(path, legacyJson);

        MapWallSave loaded = persistence.load(path).orElseThrow();
        MapWallSave migrated = planner.migrateLegacyBindingData(loaded);

        assertEquals(0, loaded.session().bindingDataVersion());
        assertEquals(RunSessionState.CURRENT_BINDING_DATA_VERSION, migrated.session().bindingDataVersion());
        assertEquals(BindingVerification.TARGET_CAPTURE, migrated.bindings().getFirst().verifiedBy());
    }
}

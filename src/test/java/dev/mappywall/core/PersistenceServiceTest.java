package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
}

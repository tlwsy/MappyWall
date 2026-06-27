package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

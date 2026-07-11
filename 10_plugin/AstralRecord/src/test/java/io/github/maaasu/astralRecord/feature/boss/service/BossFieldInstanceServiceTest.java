package io.github.maaasu.astralRecord.feature.boss.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class BossFieldInstanceServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void deleteDirectoryRemovesNestedWorldFilesWithoutCollectingAllPaths() throws Exception {
        Path worldDirectory = tempDirectory.resolve("boss_instance");
        Files.createDirectories(worldDirectory.resolve("region/nested"));
        Files.writeString(worldDirectory.resolve("level.dat"), "level");
        Files.writeString(worldDirectory.resolve("region/r.0.0.mca"), "region");
        Files.writeString(worldDirectory.resolve("region/nested/data.bin"), "data");

        BossFieldInstanceService.deleteDirectory(worldDirectory);

        assertFalse(Files.exists(worldDirectory));
    }
}

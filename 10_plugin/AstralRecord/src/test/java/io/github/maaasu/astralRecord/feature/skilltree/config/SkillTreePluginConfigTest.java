package io.github.maaasu.astralRecord.feature.skilltree.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillTreePluginConfigTest {
    @TempDir
    Path tempDirectory;

    @Test
    void loadFileReadsTheCurrentDiskSnapshot() throws IOException {
        Path configFile = tempDirectory.resolve("config.yml");
        Files.writeString(configFile, """
                skilltree:
                  worldName: custom/world
                  structureId: first
                  center:
                    x: 10
                    y: 20
                    z: 30
                """, StandardCharsets.UTF_8);

        SkillTreePluginConfig first = SkillTreePluginConfig.loadFile(configFile.toFile());
        Files.writeString(configFile, """
                skilltree:
                  worldName: custom/world
                  structureId: second
                  center:
                    x: -10
                    y: 64
                    z: 500
                """, StandardCharsets.UTF_8);
        SkillTreePluginConfig second = SkillTreePluginConfig.loadFile(configFile.toFile());

        assertEquals("first", first.structureId());
        assertEquals(new SkillTreePluginConfig.Center(10, 20, 30), first.center());
        assertEquals("second", second.structureId());
        assertEquals(new SkillTreePluginConfig.Center(-10, 64, 500), second.center());
    }

    @Test
    void rejectsStructureIdOutsideTheStructureSchemaRule() throws IOException {
        Path configFile = tempDirectory.resolve("config.yml");
        Files.writeString(configFile, """
                skilltree:
                  worldName: custom/world
                  structureId: Starter
                  center: {x: 0, y: 0, z: 0}
                """, StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> SkillTreePluginConfig.loadFile(configFile.toFile())
        );
    }
}

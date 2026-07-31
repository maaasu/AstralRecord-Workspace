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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: loadFileは共有cacheでなく呼出時点のdisk上config.ymlを毎回読む。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: structure IDを小文字英数字始まりかつ小文字英数字/_/-だけに制限する。
     */
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

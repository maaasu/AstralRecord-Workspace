package io.github.maaasu.astralRecord.feature.teleporter.repository;

import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WaystoneDefinitionRepositoryTest extends MockBukkitTestBase {
    @TempDir
    Path tempDir;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-リポジトリ.md
     * 章・見出し: # 25_3-リポジトリ > ## ウェイストーン定義一覧取得
     * 検証契約: icon欠落・未知Materialを未設定として読み、表示時はBEACONへfallbackする。
     */
    @Test
    void loadAllUsesBeaconWhenIconIsMissingOrInvalid() throws IOException {
        Files.writeString(tempDir.resolve("waystones.yml"), """
                waystones:
                  - id: default-icon
                    name: Default
                    worldName: world
                  - id: invalid-icon
                    name: Invalid
                    worldName: world
                    icon: NOT_A_MATERIAL
                """, StandardCharsets.UTF_8);

        List<WaystoneDefinition> definitions = repository().loadAll();

        assertEquals(2, definitions.size());
        assertNull(definitions.get(0).icon());
        assertEquals(Material.BEACON, definitions.get(0).displayIcon());
        assertNull(definitions.get(1).icon());
        assertEquals(Material.BEACON, definitions.get(1).displayIcon());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-リポジトリ.md
     * 章・見出し: # 25_3-リポジトリ > ## ウェイストーン定義一覧取得
     * 検証契約: 有効なitem Material名AMETHYST_SHARDを定義iconと表示iconへ反映する。
     */
    @Test
    void loadAllReadsConfiguredItemMaterial() throws IOException {
        Files.writeString(tempDir.resolve("waystones.yml"), """
                waystones:
                  - id: configured-icon
                    name: Configured
                    worldName: world
                    icon: AMETHYST_SHARD
                """, StandardCharsets.UTF_8);

        WaystoneDefinition definition = repository().loadAll().get(0);

        assertEquals(Material.AMETHYST_SHARD, definition.icon());
        assertEquals(Material.AMETHYST_SHARD, definition.displayIcon());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-リポジトリ.md
     * 章・見出し: # 25_3-リポジトリ > ## ウェイストーン定義保存
     * 検証契約: icon未設定の定義を保存するとYAMLへiconキーを出力しない。
     */
    @Test
    void saveAllOmitsUnsetIcon() throws IOException {
        WaystoneDefinition definition = new WaystoneDefinition(
                "default-icon",
                "Default",
                "world",
                0.0D,
                64.0D,
                0.0D,
                0.0F,
                0.0F,
                false,
                0L,
                null,
                Instant.EPOCH,
                "test"
        );

        repository().saveAll(List.of(definition));

        String yaml = Files.readString(tempDir.resolve("waystones.yml"), StandardCharsets.UTF_8);
        assertFalse(yaml.contains("icon:"));
    }

    private WaystoneDefinitionRepository repository() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        return new WaystoneDefinitionRepository(plugin);
    }
}

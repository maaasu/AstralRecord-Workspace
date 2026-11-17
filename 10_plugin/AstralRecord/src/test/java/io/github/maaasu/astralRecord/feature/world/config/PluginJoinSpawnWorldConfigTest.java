package io.github.maaasu.astralRecord.feature.world.config;

import io.github.maaasu.astralRecord.AstralRecord;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginJoinSpawnWorldConfigTest {

    /**
     * 参加先設定が未指定の場合は BASE の WorldMasterData ID `starlit_nox` を返す。
     */
    @Test
    void defaultsToCentralBaseWorldMasterId() {
        assertEquals("starlit_nox", PluginJoinSpawnWorldConfig.load(pluginWithConfig(null)));
    }

    /**
     * 旧構成の参加先設定 `world` は現行 BASE の WorldMasterData ID `starlit_nox` へ変換する。
     */
    @Test
    void migratesLegacyWorldMasterId() {
        assertEquals("starlit_nox", PluginJoinSpawnWorldConfig.load(pluginWithConfig("world")));
    }

    /**
     * 参加先設定に指定した現行 WorldMasterData ID は変更せず返す。
     */
    @Test
    void preservesExplicitWorldMasterId() {
        assertEquals("skyhaven_isle", PluginJoinSpawnWorldConfig.load(pluginWithConfig("skyhaven_isle")));
    }

    private AstralRecord pluginWithConfig(String worldId) {
        AstralRecord plugin = mock(AstralRecord.class);
        FileConfiguration config = new YamlConfiguration();
        if (worldId != null) {
            config.set("plugin.joinSpawn.world", worldId);
        }
        when(plugin.getConfig()).thenReturn(config);
        return plugin;
    }
}

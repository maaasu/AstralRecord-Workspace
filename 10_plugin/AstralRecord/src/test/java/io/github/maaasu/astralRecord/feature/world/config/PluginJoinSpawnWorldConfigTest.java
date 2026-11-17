package io.github.maaasu.astralRecord.feature.world.config;

import io.github.maaasu.astralRecord.AstralRecord;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginJoinSpawnWorldConfigTest {

    /**
     * 未指定・旧構成では現行の拠点マスターを選び、明示された現行IDは維持する。
     */
    @Test
    void resolvesDefaultLegacyAndExplicitWorldIds() {
        assertAll(
            () -> assertEquals("starlit_nox", PluginJoinSpawnWorldConfig.load(pluginWithConfig(null)), "未指定"),
            () -> assertEquals("starlit_nox", PluginJoinSpawnWorldConfig.load(pluginWithConfig("world")), "旧構成"),
            () -> assertEquals("skyhaven_isle", PluginJoinSpawnWorldConfig.load(pluginWithConfig("skyhaven_isle")), "明示ID")
        );
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

package io.github.maaasu.astralRecord.infrastructure.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ConfigPropertiesTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_2-ユースケース.md
     * 章・見出し: # 03_2-ユースケース > ## 11. whitelist メンテナンス
     * 検証契約: debugUsers / whitelistUsers は UUID文字列を前後空白除去後に解析し、不正値と空値を無視し、reload後は新しい設定値へ置き換える。
     */
    @Test
    void parsesConfiguredUserListsAndReplacesThemOnReload() {
        UUID debugUuid = UUID.randomUUID();
        UUID whitelistUuid = UUID.randomUUID();
        UUID reloadedWhitelistUuid = UUID.randomUUID();
        FileConfiguration initialConfig = new YamlConfiguration();
        initialConfig.set(
                ConfigKeys.PLUGIN_DEBUG_USERS,
                List.of(" " + debugUuid + " ", "not-a-uuid", " ")
        );
        initialConfig.set(
                ConfigKeys.PLUGIN_WHITELIST_USERS,
                List.of(" " + whitelistUuid + " ", "invalid", "")
        );

        FileConfiguration reloadedConfig = new YamlConfiguration();
        reloadedConfig.set(ConfigKeys.PLUGIN_DEBUG_USERS, List.of());
        reloadedConfig.set(
                ConfigKeys.PLUGIN_WHITELIST_USERS,
                List.of(reloadedWhitelistUuid.toString())
        );

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfig()).thenReturn(initialConfig);
        doAnswer(invocation -> {
            when(configManager.getConfig()).thenReturn(reloadedConfig);
            return null;
        }).when(configManager).reload();

        try (MockedStatic<ConfigManager> managers = mockStatic(ConfigManager.class)) {
            managers.when(ConfigManager::getInstance).thenReturn(configManager);
            ConfigProperties properties = ConfigProperties.getInstance();

            properties.initialize();

            assertTrue(properties.isDebugUser(debugUuid));
            assertFalse(properties.isWhitelistUser(debugUuid));
            assertTrue(properties.isWhitelistUser(whitelistUuid));
            assertFalse(properties.isDebugUser(whitelistUuid));
            assertFalse(properties.isWhitelistUser(null));

            properties.reload();

            assertFalse(properties.isDebugUser(debugUuid));
            assertFalse(properties.isWhitelistUser(whitelistUuid));
            assertTrue(properties.isWhitelistUser(reloadedWhitelistUuid));
        }
    }
}

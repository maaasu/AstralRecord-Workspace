package io.github.maaasu.astralRecord.feature.whitelist.service;

import io.github.maaasu.astralRecord.feature.discord.service.DiscordSrvChatBridge;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigKeys;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigManager;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

class WhitelistServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_2-ユースケース.md
     * 章・見出し: # 03_2-ユースケース > ## 11. whitelist メンテナンス
     * 検証契約: whitelist 有効化は設定を保存し、debugUsers / whitelistUsers のいずれにも含まれない接続中プレイヤーだけを遮断する。
     */
    @Test
    void enablingWhitelistPersistsStateAndKicksPlayersOutsideConfiguredUsers() {
        ConfigManager configManager = mock(ConfigManager.class);
        ConfigProperties configProperties = mock(ConfigProperties.class);
        Player debugPlayer = mock(Player.class);
        Player whitelistPlayer = mock(Player.class);
        Player deniedPlayer = mock(Player.class);
        UUID debugUuid = UUID.randomUUID();
        UUID whitelistUuid = UUID.randomUUID();
        UUID deniedUuid = UUID.randomUUID();
        AtomicBoolean enabled = new AtomicBoolean(false);

        when(debugPlayer.getUniqueId()).thenReturn(debugUuid);
        when(whitelistPlayer.getUniqueId()).thenReturn(whitelistUuid);
        when(deniedPlayer.getUniqueId()).thenReturn(deniedUuid);
        when(configProperties.isPluginWhitelistEnabled()).thenAnswer(invocation -> enabled.get());
        when(configProperties.isDebugUser(debugUuid)).thenReturn(true);
        when(configProperties.isWhitelistUser(debugUuid)).thenReturn(true);
        when(configProperties.isDebugUser(whitelistUuid)).thenReturn(false);
        when(configProperties.isWhitelistUser(whitelistUuid)).thenReturn(true);
        when(configProperties.isDebugUser(deniedUuid)).thenReturn(false);
        when(configProperties.isWhitelistUser(deniedUuid)).thenReturn(false);
        doAnswer(invocation -> {
            enabled.set(invocation.getArgument(0));
            return null;
        }).when(configProperties).setPluginWhitelistEnabled(anyBoolean());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<ConfigManager> managers = mockStatic(ConfigManager.class);
             MockedStatic<ConfigProperties> properties = mockStatic(ConfigProperties.class);
             MockedStatic<DiscordSrvChatBridge> discord = mockStatic(DiscordSrvChatBridge.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(debugPlayer, whitelistPlayer, deniedPlayer));
            managers.when(ConfigManager::getInstance).thenReturn(configManager);
            properties.when(ConfigProperties::getInstance).thenReturn(configProperties);

            WhitelistService.getInstance().setEnabled(true);

            verify(configManager).set(ConfigKeys.PLUGIN_WHITELIST_ENABLED, true);
            verify(configManager).save();
            verify(configProperties).setPluginWhitelistEnabled(true);
            verify(deniedPlayer).kick(any(Component.class));
            verify(debugPlayer, never()).kick(any(Component.class));
            verify(whitelistPlayer, never()).kick(any(Component.class));
            discord.verify(() -> DiscordSrvChatBridge.setServerLifecycleMessagesSuppressed(true));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 7. whitelist ユーザー設定
     * 検証契約: whitelist ユーザー追加はUUIDを設定ファイルへ保存し、実行中の設定にも反映する。
     */
    @Test
    void addsWhitelistUserToConfigAndRuntimeState() {
        UUID playerUuid = UUID.randomUUID();
        ConfigManager configManager = mock(ConfigManager.class);
        ConfigProperties configProperties = mock(ConfigProperties.class);
        when(configProperties.getPluginWhitelistUsers()).thenReturn(Set.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<ConfigManager> managers = mockStatic(ConfigManager.class);
             MockedStatic<ConfigProperties> properties = mockStatic(ConfigProperties.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            managers.when(ConfigManager::getInstance).thenReturn(configManager);
            properties.when(ConfigProperties::getInstance).thenReturn(configProperties);

            boolean changed = WhitelistService.getInstance().addWhitelistUser(playerUuid);

            org.junit.jupiter.api.Assertions.assertTrue(changed);
            verify(configManager).set(ConfigKeys.PLUGIN_WHITELIST_USERS, List.of(playerUuid.toString()));
            verify(configManager).save();
            verify(configProperties).setPluginWhitelistUsers(anySet());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 7. whitelist ユーザー設定
     * 検証契約: whitelist ユーザー削除は対象UUIDだけを設定ファイルから除去し、実行中の設定にも反映する。
     */
    @Test
    void removesWhitelistUserFromConfigAndRuntimeState() {
        UUID playerUuid = UUID.randomUUID();
        ConfigManager configManager = mock(ConfigManager.class);
        ConfigProperties configProperties = mock(ConfigProperties.class);
        when(configProperties.getPluginWhitelistUsers()).thenReturn(Set.of(playerUuid));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<ConfigManager> managers = mockStatic(ConfigManager.class);
             MockedStatic<ConfigProperties> properties = mockStatic(ConfigProperties.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            managers.when(ConfigManager::getInstance).thenReturn(configManager);
            properties.when(ConfigProperties::getInstance).thenReturn(configProperties);

            boolean changed = WhitelistService.getInstance().removeWhitelistUser(playerUuid);

            org.junit.jupiter.api.Assertions.assertTrue(changed);
            verify(configManager).set(ConfigKeys.PLUGIN_WHITELIST_USERS, List.of());
            verify(configManager).save();
            verify(configProperties).setPluginWhitelistUsers(anySet());
        }
    }
}

package io.github.maaasu.astralRecord.feature.playersetting.service;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingModel;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerScreenHudSettingTest {
    /**
     * 旧MSPT・PingがONの場合も、新しい独立項目の明示OFFを維持し、未登録項目だけを旧値で補う。
     */
    @Test
    void onlyMissingPerformanceFlagsInheritTheLegacyValue() {
        UUID userId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        when(repository.findByUserId(userId)).thenReturn(List.of(
            stored(userId, PlayerSettingKey.PERFORMANCE_INFO_DISPLAY, true),
            stored(userId, PlayerSettingKey.MSPT_DISPLAY, false)
        ));
        PlayerSettingService settings = new PlayerSettingService(repository, new PlayerSettingDefaults(), new PlayerSettingCache());

        assertFalse(settings.isHudDisplayEnabled(userId, PlayerSettingKey.MSPT_DISPLAY));
        assertFalse(settings.isHudDisplayEnabled(userId, PlayerSettingKey.PING_DISPLAY));
        assertTrue(settings.isHudDisplayEnabled(userId, PlayerSettingKey.WORLD_NAME_DISPLAY));
        verifyNoInteractions(repository);

        long token = settings.beginSession(userId);
        settings.warmup(userId, token);
        assertFalse(settings.isHudDisplayEnabled(userId, PlayerSettingKey.MSPT_DISPLAY));
        assertTrue(settings.isHudDisplayEnabled(userId, PlayerSettingKey.PING_DISPLAY));
        assertEquals(4, settings.getSnapshot(userId).getEntry(PlayerSettingKey.MSPT_DISPLAY).getVersion());
    }

    /**
     * 旧コマンドの一括設定は両項目を更新し、直後の独立更新は他方を変えずに同一完成状態へ保存する。
     */
    @Test
    void independentFlagsAndLegacyCommandProduceTheExpectedSnapshot() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        when(repository.findByUserId(userId)).thenReturn(List.of(stored(userId, PlayerSettingKey.QUEST_HUD_DISPLAY, false)));
        PlayerSettingService settings = new PlayerSettingService(repository, new PlayerSettingDefaults(), new PlayerSettingCache());
        long token = settings.beginSession(userId);
        settings.warmup(userId, token);
        assertTrue(settings.isGuideDisplayEnabled(userId));
        assertFalse(settings.isQuestHudDisplayEnabled(userId));

        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(userId);
        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(player));
            assertTrue(settings.updatePlayerSetting(new PlayerSettingChangeRequest(
                userId, PlayerSettingKey.PERFORMANCE_INFO_DISPLAY, true, userId), token).success());
            assertTrue(settings.isHudDisplayEnabled(userId, PlayerSettingKey.MSPT_DISPLAY));
            assertTrue(settings.isHudDisplayEnabled(userId, PlayerSettingKey.PING_DISPLAY));
            assertTrue(settings.updatePlayerSetting(new PlayerSettingChangeRequest(
                userId, PlayerSettingKey.PING_DISPLAY, false, userId), token).success());
            assertTrue(settings.updatePlayerSetting(new PlayerSettingChangeRequest(
                userId, PlayerSettingKey.GUIDE_DISPLAY, false, userId), token).success());
            assertFalse(settings.isGuideDisplayEnabled(userId));
            assertTrue(settings.isGuideReminderEnabled(userId));
            var section = settings.snapshotPlayerState(accountId);
            var settingsJson = section.payload().getAsJsonObject().getAsJsonArray("settings").asList().stream()
                .map(value -> value.getAsJsonObject())
                .collect(java.util.stream.Collectors.toMap(
                    value -> value.get("settingKey").getAsString(),
                    value -> JsonParser.parseString(value.get("settingValueJson").getAsString()).getAsJsonObject()
                ));
            assertFalse(settingsJson.get(PlayerSettingKey.GUIDE_DISPLAY.getCode()).get("enabled").getAsBoolean());
            assertTrue(settingsJson.get(PlayerSettingKey.MSPT_DISPLAY.getCode()).get("enabled").getAsBoolean());
            assertFalse(settingsJson.get(PlayerSettingKey.PING_DISPLAY.getCode()).get("enabled").getAsBoolean());
            assertFalse(settingsJson.get(PlayerSettingKey.QUEST_HUD_DISPLAY.getCode()).get("enabled").getAsBoolean());
        }
    }

    /**
     * 統合版フォーム作成後にTIP対応を失った場合やJava版専用設定が送られた場合は変更せず、失効した設定セッションも拒否する。
     */
    @Test
    void unsupportedOrStaleBedrockSubmissionNeverMutatesSettings() {
        UUID userId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        PlayerSettingService settings = mock(PlayerSettingService.class);
        when(settings.captureSessionToken(userId)).thenReturn(2L);
        AtomicBoolean questHudSupported = new AtomicBoolean(true);
        var save = new PlayerSettingGuiSaveService(mock(AstralRecord.class), settings,
            id -> id.equals(playerId) && questHudSupported.get());
        Player bukkit = mock(Player.class);
        when(bukkit.getUniqueId()).thenReturn(playerId);
        AstPlayer player = mock(AstPlayer.class);
        UserModel user = mock(UserModel.class);
        when(player.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(userId);
        when(player.getBukkit()).thenReturn(bukkit);
        when(player.isBedrock()).thenReturn(true);

        assertTrue(save.canEdit(player, PlayerSettingKey.QUEST_HUD_DISPLAY));
        assertTrue(save.canEdit(player, PlayerSettingKey.WORLD_NAME_DISPLAY));
        assertFalse(save.canEdit(player, PlayerSettingKey.ACTION_RING_COOLDOWN_DISPLAY));
        assertFalse(save.canEdit(player, PlayerSettingKey.EQUIPPED_SKILL_COOLDOWN_DISPLAY));
        questHudSupported.set(false);
        assertTrue(save.canEdit(player, PlayerSettingKey.GUIDE_DISPLAY));
        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(bukkit)).thenReturn(player);
            save.save(bukkit, userId, 2L, Map.of(
                PlayerSettingKey.QUEST_HUD_DISPLAY, false,
                PlayerSettingKey.ACTION_RING_COOLDOWN_DISPLAY, false,
                PlayerSettingKey.EQUIPPED_SKILL_COOLDOWN_DISPLAY, false));
            save.save(bukkit, userId, 1L, Map.of(PlayerSettingKey.WORLD_NAME_DISPLAY, false));
        }
        verify(settings, never()).updatePlayerSetting(any(), anyLong());
        verify(settings, never()).getPlayerSetting(any(), any());
    }

    private static PlayerSettingModel stored(UUID userId, PlayerSettingKey key, boolean enabled) {
        var time = LocalDateTime.of(2026, 11, 16, 0, 0);
        return new PlayerSettingModel(UUID.randomUUID(), userId, key.getCode(), "{\"enabled\":" + enabled + "}",
            4, time, time, userId, userId, false);
    }
}

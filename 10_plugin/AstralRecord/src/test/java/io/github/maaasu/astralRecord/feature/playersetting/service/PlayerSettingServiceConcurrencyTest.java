package io.github.maaasu.astralRecord.feature.playersetting.service;

import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerSettingServiceConcurrencyTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 6. 保存契機
     * 検証契約: 設定更新は所有アカウントを共通snapshot保存キューへ登録する。
     */
    @Test
    void updateQueuesTheOwningAccountForCommonSnapshotSave() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PlayerSettingService service = new PlayerSettingService(
            mock(PlayerSettingRepository.class), new PlayerSettingDefaults(), new PlayerSettingCache());
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(userId);
        AtomicReference<UUID> queued = new AtomicReference<>();
        service.setLocalPlayerSaveRequester(queued::set);
        long token = service.beginSession(userId);

        try (var ignored = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            org.mockito.Mockito.when(AstPlayerCache.getAll()).thenReturn(java.util.List.of(player));
            service.updatePlayerSetting(new PlayerSettingChangeRequest(
                userId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, false, userId), token);
        }

        assertEquals(accountId, queued.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 6. 保存契機
     * 検証契約: 設定更新はキャッシュを変更し、旧repository writerを呼び出さない。
     */
    @Test
    void updateChangesCacheWithoutCallingLegacyRepositoryWriter() {
        UUID userId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        PlayerSettingCache cache = new PlayerSettingCache();
        PlayerSettingService service = new PlayerSettingService(repository, new PlayerSettingDefaults(), cache);
        long token = service.beginSession(userId);

        PlayerSettingService.UpdateResult result = service.updatePlayerSetting(
            new PlayerSettingChangeRequest(userId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, false, userId), token);

        assertTrue(result.success());
        assertFalse((Boolean) cache.find(userId).getEntry(PlayerSettingKey.DAMAGE_LOG_DISPLAY).getValue());
        verifyNoInteractions(repository);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 6. 保存契機
     * 検証契約: clear後も共通保存経路のACKまではdirty設定値を保持する。
     */
    @Test
    void clearRetainsDirtyCacheValueUntilCommonSaveLaneAcknowledgesIt() {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        PlayerSettingService service = new PlayerSettingService(mock(PlayerSettingRepository.class), new PlayerSettingDefaults(), cache);
        long token = service.beginSession(userId);
        service.updatePlayerSetting(new PlayerSettingChangeRequest(
            userId, PlayerSettingKey.DROP_LOG_DISPLAY, false, userId), token);

        service.clear(userId);

        assertFalse((Boolean) cache.find(userId).getEntry(PlayerSettingKey.DROP_LOG_DISPLAY).getValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 6. 保存契機
     * 検証契約: オンラインキャッシュから退出後もdirty sectionはアカウント対応を保持する。
     */
    @Test
    void dirtySectionKeepsAccountMappingAfterPlayerLeavesOnlineCache() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PlayerSettingService service = new PlayerSettingService(
            mock(PlayerSettingRepository.class), new PlayerSettingDefaults(), new PlayerSettingCache());
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(userId);
        long token = service.beginSession(userId);
        try (var ignored = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            org.mockito.Mockito.when(AstPlayerCache.getAll()).thenReturn(java.util.List.of(player));
            service.updatePlayerSetting(new PlayerSettingChangeRequest(
                userId, PlayerSettingKey.DROP_LOG_DISPLAY, false, userId), token);
        }

        service.clear(userId);

        assertTrue(service.snapshotPlayerState(accountId) != null);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 2. プレイヤーセッション
     * 検証契約: 古いセッションtokenの設定更新は適用せずstale結果を返す。
     */
    @Test
    void staleSessionDoesNotApplySettingChange() {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        PlayerSettingService service = new PlayerSettingService(mock(PlayerSettingRepository.class), new PlayerSettingDefaults(), cache);
        service.beginSession(userId);

        PlayerSettingService.UpdateResult result = service.updatePlayerSetting(new PlayerSettingChangeRequest(
            userId, PlayerSettingKey.AUTO_SAVE_MESSAGE, true, userId), 0L);

        assertTrue(result.staleSession());
        assertTrue(cache.find(userId) == null);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 6. 保存契機
     * 検証契約: section ACKは世代を統合し、ACK対象だけのdirty状態を解除する。
     */
    @Test
    void sectionAckMergesVersionsAndClearsOnlyCapturedDirtyState() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        PlayerSettingService service = new PlayerSettingService(mock(PlayerSettingRepository.class), new PlayerSettingDefaults(), cache);
        long token = service.beginSession(userId);
        service.updatePlayerSetting(new PlayerSettingChangeRequest(
            userId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, false, userId), token);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(userId);
        try (var ignored = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            org.mockito.Mockito.when(AstPlayerCache.getAll()).thenReturn(java.util.List.of(player));
            PlayerStateSection section = service.snapshotPlayerState(accountId);
            assertTrue(section != null);
            JsonObject payload = JsonParser.parseString(section.payload().toString()).getAsJsonObject();
            JsonObject acknowledgement = new JsonObject();
            acknowledgement.addProperty("clientRevision", payload.get("clientRevision").getAsLong());
            JsonArray settings = new JsonArray();
            for (var element : payload.getAsJsonArray("settings")) {
                JsonObject requested = element.getAsJsonObject();
                JsonObject acknowledged = new JsonObject();
                acknowledged.addProperty("userSettingId", requested.get("userSettingId").getAsString());
                acknowledged.addProperty("settingKey", requested.get("settingKey").getAsString());
                acknowledged.addProperty("version", 1);
                settings.add(acknowledged);
            }
            acknowledgement.add("settings", settings);
            section.acknowledge().accept(acknowledgement);
            assertTrue(service.snapshotPlayerState(accountId) == null);
        }
        assertFalse((Boolean) cache.find(userId).getEntry(PlayerSettingKey.DAMAGE_LOG_DISPLAY).getValue());
        assertTrue(cache.find(userId).getEntry(PlayerSettingKey.DAMAGE_LOG_DISPLAY).getVersion() == 1);
    }
}

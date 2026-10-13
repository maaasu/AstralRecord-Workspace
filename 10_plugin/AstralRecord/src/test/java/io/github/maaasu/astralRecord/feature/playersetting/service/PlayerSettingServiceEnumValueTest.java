package io.github.maaasu.astralRecord.feature.playersetting.service;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingModel;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerVisualMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerSettingServiceEnumValueTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 5. 設定更新と完成状態保存
     * 検証契約: 管理表示のcache未読込時はOFFを返し、参照処理からAPI I/Oを行わない。
     */
    @Test
    void unloadedEnumSettingDoesNotReadRepository() {
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        PlayerSettingService service = new PlayerSettingService(repository, new PlayerSettingDefaults(), new PlayerSettingCache());
        assertEquals(MobSpawnerVisualMode.OFF, service.getSpawnerVisualMode(UUID.randomUUID()));
        verifyNoInteractions(repository);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 2. session 管理と warmup
     * 検証契約: 初期ロード完了は現在sessionに限り、新sessionと旧tokenの完了では未readyを維持する。
     */
    @Test
    void initialLoadCompletionDoesNotLeakIntoANewerSession() {
        UUID userId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        when(repository.findByUserId(userId)).thenReturn(List.of());
        PlayerSettingService service = new PlayerSettingService(repository, new PlayerSettingDefaults(), new PlayerSettingCache());
        long first = service.beginSession(userId);
        assertFalse(service.isInitialLoadComplete(userId));
        service.warmup(userId, first);
        assertTrue(service.isInitialLoadComplete(userId));
        long second = service.beginSession(userId);
        assertFalse(service.isInitialLoadComplete(userId));
        service.warmup(userId, first);
        assertFalse(service.isInitialLoadComplete(userId));
        service.warmup(userId, second);
        assertTrue(service.isInitialLoadComplete(userId));
        service.clear(userId);
        assertFalse(service.isInitialLoadComplete(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 5. 設定更新と完成状態保存
     * 検証契約: APIのenum JSON値をuser cacheへ読み込み、更新値を同じuserの通常保存sectionへ文字列として含める。
     */
    @Test
    void enumValueLoadsAndSerializesThroughCommonUserSnapshot() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID settingId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 0, 0);
        when(repository.findByUserId(userId)).thenReturn(List.of(new PlayerSettingModel(
            settingId, userId, PlayerSettingKey.SPAWNER_VISUAL_MODE.getCode(), "{\"value\":\"LIGHT\"}",
            4, now, now, userId, userId, false)));
        PlayerSettingService service = new PlayerSettingService(repository, new PlayerSettingDefaults(), new PlayerSettingCache());
        long token = service.beginSession(userId);
        service.warmup(userId, token);
        assertEquals(MobSpawnerVisualMode.LIGHT, service.getSpawnerVisualMode(userId));

        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(userId);
        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(player));
            assertTrue(service.updatePlayerSetting(new PlayerSettingChangeRequest(
                userId, PlayerSettingKey.SPAWNER_VISUAL_MODE, MobSpawnerVisualMode.OFF, userId), token).success());
            var payload = service.snapshotPlayerState(accountId).payload().getAsJsonObject();
            assertEquals(userId.toString(), payload.get("userId").getAsString());
            var saved = payload.getAsJsonArray("settings").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.get("settingKey").getAsString().equals(PlayerSettingKey.SPAWNER_VISUAL_MODE.getCode()))
                .findFirst().orElseThrow();
            assertEquals(settingId.toString(), saved.get("userSettingId").getAsString());
            assertEquals(4, saved.get("expectedVersion").getAsInt());
            assertEquals("OFF", JsonParser.parseString(saved.get("settingValueJson").getAsString())
                .getAsJsonObject().get("value").getAsString());
        }
    }
}

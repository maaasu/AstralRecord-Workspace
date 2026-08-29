package io.github.maaasu.astralRecord.feature.playersetting.service;

import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingEntry;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSettingServiceDropLogTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: DROP_LOG_DISPLAYをcache済みplayer選択値から返す。
     */
    @Test
    void dropLogDisplayUsesCachedPlayerChoice() {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            PlayerSettingKey.DROP_LOG_DISPLAY,
            new PlayerSettingEntry(null, PlayerSettingKey.DROP_LOG_DISPLAY, false, null)
        )));
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            cache
        );

        assertFalse(service.isDropLogDisplayEnabled(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: cache miss時のDROP_LOG_DISPLAYを既定trueにする。
     */
    @Test
    void dropLogDisplayDefaultsToEnabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertTrue(service.isDropLogDisplayEnabled(UUID.randomUUID()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: AUTO_SAVE_MESSAGEをcache済みplayer選択値から返す。
     */
    @Test
    void autoSaveMessageUsesCachedPlayerChoice() {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            PlayerSettingKey.AUTO_SAVE_MESSAGE,
            new PlayerSettingEntry(null, PlayerSettingKey.AUTO_SAVE_MESSAGE, false, null)
        )));
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            cache
        );

        assertFalse(service.isAutoSaveMessageEnabled(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: cache miss時のAUTO_SAVE_MESSAGEを既定falseにする。
     */
    @Test
    void autoSaveMessageDefaultsToDisabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertFalse(service.isAutoSaveMessageEnabled(UUID.randomUUID()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: ARMOR_DISPLAYをAPIなしでcache済みplayer選択値から返す。
     */
    @Test
    void armorDisplayUsesCachedPlayerChoice() {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            PlayerSettingKey.ARMOR_DISPLAY,
            new PlayerSettingEntry(null, PlayerSettingKey.ARMOR_DISPLAY, false, null)
        )));
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            cache
        );

        assertFalse(service.isArmorDisplayEnabled(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: cache miss時のARMOR_DISPLAYをAPIなしで既定trueにする。
     */
    @Test
    void armorDisplayDefaultsToEnabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertTrue(service.isArmorDisplayEnabled(UUID.randomUUID()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: OFF_HAND_DISPLAYをAPIなしでcache済みplayer選択値から返す。
     */
    @Test
    void offHandDisplayUsesCachedPlayerChoice() {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            PlayerSettingKey.OFF_HAND_DISPLAY,
            new PlayerSettingEntry(null, PlayerSettingKey.OFF_HAND_DISPLAY, false, null)
        )));
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            cache
        );

        assertFalse(service.isOffHandDisplayEnabled(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: cache miss時のOFF_HAND_DISPLAYをAPIなしで既定trueにする。
     */
    @Test
    void offHandDisplayDefaultsToEnabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertTrue(service.isOffHandDisplayEnabled(UUID.randomUUID()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: ACTION_RING_HOLD_SELECTをAPIなしでcache済みplayer選択値から返す。
     */
    @Test
    void actionRingHoldSelectUsesCachedPlayerChoice() {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            PlayerSettingKey.ACTION_RING_HOLD_SELECT,
            new PlayerSettingEntry(null, PlayerSettingKey.ACTION_RING_HOLD_SELECT, true, null)
        )));
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            cache
        );

        assertTrue(service.isActionRingHoldSelectEnabled(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: cache miss時のACTION_RING_HOLD_SELECTをAPIなしで既定falseにする。
     */
    @Test
    void actionRingHoldSelectDefaultsToDisabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertFalse(service.isActionRingHoldSelectEnabled(UUID.randomUUID()));
    }
}

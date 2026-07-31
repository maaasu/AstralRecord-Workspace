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

    @Test
    void dropLogDisplayDefaultsToEnabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertTrue(service.isDropLogDisplayEnabled(UUID.randomUUID()));
    }

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

    @Test
    void autoSaveMessageDefaultsToDisabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertFalse(service.isAutoSaveMessageEnabled(UUID.randomUUID()));
    }

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

    @Test
    void armorDisplayDefaultsToEnabledWithoutCachedSnapshot() {
        PlayerSettingService service = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertTrue(service.isArmorDisplayEnabled(UUID.randomUUID()));
    }
}

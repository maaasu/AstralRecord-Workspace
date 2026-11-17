package io.github.maaasu.astralRecord.feature.playersetting.service;

import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingEntry;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerSettingServiceDropLogTest {

    private static final List<BooleanSetting> SETTINGS = List.of(
        new BooleanSetting(PlayerSettingKey.DROP_LOG_DISPLAY, true,
            PlayerSettingService::isDropLogDisplayEnabled),
        new BooleanSetting(PlayerSettingKey.AUTO_SAVE_MESSAGE, false,
            PlayerSettingService::isAutoSaveMessageEnabled),
        new BooleanSetting(PlayerSettingKey.ARMOR_DISPLAY, true,
            PlayerSettingService::isArmorDisplayEnabled),
        new BooleanSetting(PlayerSettingKey.ACTION_RING_HOLD_SELECT, false,
            PlayerSettingService::isActionRingHoldSelectEnabled),
        new BooleanSetting(PlayerSettingKey.SKILL_TREE_COMPACT_DISPLAY, false,
            PlayerSettingService::isSkillTreeCompactDisplayEnabled)
    );

    /**
     * キャッシュがない場合は、各設定キーの既定値を返す。
     */
    @Test
    void usesDefaultsWithoutCachedSettings() {
        assertAll(SETTINGS.stream().map(setting -> () -> {
            PlayerSettingService service = newService(new PlayerSettingCache());
            assertEquals(setting.defaultValue(), setting.read().test(service, UUID.randomUUID()),
                setting.key().name());
        }));
    }

    /**
     * 保存済みの明示値は、各設定キーの既定値より優先される。
     */
    @Test
    void usesExplicitCachedSettings() {
        assertAll(SETTINGS.stream().map(setting -> () -> assertAll(
            setting.key().name(),
            () -> assertCachedValue(setting, false),
            () -> assertCachedValue(setting, true)
        )));
    }

    private static void assertCachedValue(BooleanSetting setting, boolean value) {
        UUID userId = UUID.randomUUID();
        PlayerSettingCache cache = new PlayerSettingCache();
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            setting.key(), new PlayerSettingEntry(null, setting.key(), value, null)
        )));

        assertEquals(value, setting.read().test(newService(cache), userId), setting.key().name());
    }

    private static PlayerSettingService newService(PlayerSettingCache cache) {
        return new PlayerSettingService(new PlayerSettingRepository(), new PlayerSettingDefaults(), cache);
    }

    private record BooleanSetting(
        PlayerSettingKey key,
        boolean defaultValue,
        BiPredicate<PlayerSettingService, UUID> read
    ) {
    }
}

package io.github.maaasu.astralRecord.feature.playersetting.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.playersetting.OptimisticLockConflictException;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingEntry;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingModel;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤー設定のロード・参照・更新を扱うサービスです。
 */
public final class PlayerSettingService {
    private final PlayerSettingRepository repository;
    private final PlayerSettingDefaults defaults;
    private final PlayerSettingCache cache;

    public PlayerSettingService(
        @NotNull PlayerSettingRepository repository,
        @NotNull PlayerSettingDefaults defaults,
        @NotNull PlayerSettingCache cache
    ) {
        this.repository = repository;
        this.defaults = defaults;
        this.cache = cache;
    }

    public @NotNull PlayerSettingSnapshot loadPlayerSettings(@NotNull UUID userId) {
        Map<PlayerSettingKey, PlayerSettingEntry> entries = createDefaultEntries();
        try {
            for (PlayerSettingModel model : repository.findByUserId(userId)) {
                PlayerSettingKey key = PlayerSettingKey.fromInput(model.getSettingKey());
                if (key == null) {
                    Logger.log(LogId.W_5311, userId, model.getSettingKey(), "unknown key");
                    continue;
                }
                Object value = parseJsonValue(key, model.getSettingValueJson(), userId);
                entries.put(key, new PlayerSettingEntry(
                    model.getUserSettingId(),
                    key,
                    value,
                    model.getVersion()
                ));
            }
        } catch (Exception e) {
            Logger.log(LogId.W_5310, userId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        PlayerSettingSnapshot snapshot = new PlayerSettingSnapshot(userId, entries);
        cache.put(snapshot);
        return snapshot;
    }

    public void warmup(@NotNull UUID userId) {
        loadPlayerSettings(userId);
    }

    public void clear(@NotNull UUID userId) {
        cache.remove(userId);
    }

    public @NotNull PlayerSettingSnapshot getSnapshot(@NotNull UUID userId) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot != null) {
            return snapshot;
        }
        return loadPlayerSettings(userId);
    }

    public @NotNull Object getPlayerSetting(@NotNull UUID userId, @NotNull PlayerSettingKey key) {
        PlayerSettingEntry entry = getSnapshot(userId).getEntry(key);
        if (entry != null) {
            return entry.getValue();
        }
        return defaults.resolveDefault(key);
    }

    public double getParticleDensityScale(@NotNull UUID userId) {
        Object value = getPlayerSetting(userId, PlayerSettingKey.PARTICLE_DENSITY);
        if (value instanceof ParticleDensity density) {
            return density.getDensityScale();
        }
        return ParticleDensity.NORMAL.getDensityScale();
    }

    /**
     * 指定プレイヤーでダメージログ表示が有効かを返します。
     *
     * @param userId 判定対象ユーザー ID
     * @return ダメージログ表示が有効な場合は {@code true}
     */
    public boolean isDamageLogDisplayEnabled(@NotNull UUID userId) {
        Object value = getPlayerSetting(userId, PlayerSettingKey.DAMAGE_LOG_DISPLAY);
        return value instanceof Boolean enabled ? enabled : (Boolean) PlayerSettingKey.DAMAGE_LOG_DISPLAY.getDefaultValue();
    }

    /**
     * 指定プレイヤーでレアドロップログ表示が有効かを返します。
     *
     * @param userId 判定対象ユーザー ID
     * @return レアドロップログ表示が有効な場合は {@code true}
     */
    public boolean isDropLogDisplayEnabled(@NotNull UUID userId) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot == null) {
            return (Boolean) PlayerSettingKey.DROP_LOG_DISPLAY.getDefaultValue();
        }
        PlayerSettingEntry entry = snapshot.getEntry(PlayerSettingKey.DROP_LOG_DISPLAY);
        Object value = entry == null ? null : entry.getValue();
        return value instanceof Boolean enabled ? enabled : (Boolean) PlayerSettingKey.DROP_LOG_DISPLAY.getDefaultValue();
    }

    /**
     * 指定プレイヤーでダメージ詳細メッセージが有効かを返します。
     *
     * @param userId 判定対象ユーザー ID
     * @return ダメージ詳細メッセージが有効な場合は {@code true}
     */
    public boolean isDamageLogMessageEnabled(@NotNull UUID userId) {
        Object value = getPlayerSetting(userId, PlayerSettingKey.DAMAGE_LOG_MESSAGE);
        return value instanceof Boolean enabled ? enabled : (Boolean) PlayerSettingKey.DAMAGE_LOG_MESSAGE.getDefaultValue();
    }

    public @NotNull UpdateResult updatePlayerSetting(@NotNull PlayerSettingChangeRequest request) {
        PlayerSettingSnapshot snapshot = cache.find(request.userId());
        if (snapshot == null) {
            Logger.log(LogId.W_5312, request.userId(), "snapshot not cached");
            snapshot = loadPlayerSettings(request.userId());
        }

        PlayerSettingEntry currentEntry = snapshot.getEntry(request.settingKey());
        if (currentEntry == null) {
            currentEntry = defaultEntry(request.settingKey());
        }

        try {
            PlayerSettingModel updatedModel;
            String valueJson = serializeValue(request.settingKey(), request.newValue());
            if (currentEntry.getUserSettingId() == null || currentEntry.getVersion() == null) {
                updatedModel = repository.create(
                    request.userId(),
                    request.settingKey().getCode(),
                    valueJson,
                    request.requestedBy()
                );
            } else {
                updatedModel = repository.update(
                    currentEntry.getUserSettingId(),
                    valueJson,
                    currentEntry.getVersion(),
                    request.requestedBy()
                );
                if (updatedModel == null) {
                    updatedModel = repository.create(
                        request.userId(),
                        request.settingKey().getCode(),
                        valueJson,
                        request.requestedBy()
                    );
                }
            }

            PlayerSettingEntry updatedEntry = new PlayerSettingEntry(
                updatedModel.getUserSettingId(),
                request.settingKey(),
                request.newValue(),
                updatedModel.getVersion()
            );
            cache.put(snapshot.withEntry(updatedEntry));
            return UpdateResult.success(request.settingKey(), request.newValue());
        } catch (OptimisticLockConflictException conflict) {
            PlayerSettingEntry latest = entryFromModel(conflict.getCurrent(), request.settingKey());
            cache.put(getSnapshot(request.userId()).withEntry(latest));
            return UpdateResult.conflict(
                request.settingKey(),
                latest.getValue(),
                PlayerMsgResource.format(PlayerSettingMsgId.P_5320.getId(), request.settingKey().getDisplayNameJa())
            );
        }
    }

    private @NotNull Map<PlayerSettingKey, PlayerSettingEntry> createDefaultEntries() {
        EnumMap<PlayerSettingKey, PlayerSettingEntry> entries = new EnumMap<>(PlayerSettingKey.class);
        for (PlayerSettingKey key : PlayerSettingKey.values()) {
            entries.put(key, defaultEntry(key));
        }
        return entries;
    }

    private @NotNull PlayerSettingEntry defaultEntry(@NotNull PlayerSettingKey key) {
        return new PlayerSettingEntry(null, key, defaults.resolveDefault(key), null);
    }

    private @NotNull PlayerSettingEntry entryFromModel(@NotNull PlayerSettingModel model, @NotNull PlayerSettingKey key) {
        return new PlayerSettingEntry(
            model.getUserSettingId(),
            key,
            parseJsonValue(key, model.getSettingValueJson(), model.getUserId()),
            model.getVersion()
        );
    }

    private @NotNull Object parseJsonValue(@NotNull PlayerSettingKey key, @NotNull String settingValueJson, @NotNull UUID userId) {
        try {
            JsonObject obj = JsonParser.parseString(settingValueJson).getAsJsonObject();
            if (key.isBooleanValue()) {
                return obj.get("enabled").getAsBoolean();
            }
            if (key.isParticleDensityValue()) {
                ParticleDensity density = ParticleDensity.fromInput(obj.get("value").getAsString());
                if (density != null) {
                    return density;
                }
            }
            throw new IllegalArgumentException("invalid value");
        } catch (Exception e) {
            Logger.log(LogId.W_5311, userId, key.getCode(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return defaults.resolveDefault(key);
        }
    }

    private @NotNull String serializeValue(@NotNull PlayerSettingKey key, @NotNull Object value) {
        JsonObject obj = new JsonObject();
        if (key.isBooleanValue()) {
            obj.addProperty("enabled", (Boolean) value);
            return obj.toString();
        }
        if (key.isParticleDensityValue()) {
            obj.addProperty("value", ((ParticleDensity) value).getCode());
            return obj.toString();
        }
        throw new IllegalArgumentException("Unsupported player setting key: " + key.getCode());
    }

    public record UpdateResult(
        boolean success,
        boolean conflict,
        @NotNull PlayerSettingKey key,
        @Nullable Object value,
        @Nullable String message
    ) {
        public static @NotNull UpdateResult success(@NotNull PlayerSettingKey key, @NotNull Object value) {
            return new UpdateResult(true, false, key, value, null);
        }

        public static @NotNull UpdateResult conflict(
            @NotNull PlayerSettingKey key,
            @NotNull Object value,
            @NotNull String message
        ) {
            return new UpdateResult(false, true, key, value, message);
        }
    }
}

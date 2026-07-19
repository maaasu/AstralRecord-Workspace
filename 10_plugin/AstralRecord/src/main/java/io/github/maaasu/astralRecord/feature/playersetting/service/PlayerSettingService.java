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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * プレイヤー設定のロード・参照・更新を扱うサービスです。
 */
public final class PlayerSettingService {
    private static final long NO_ACTIVE_SESSION = 0L;

    private final PlayerSettingRepository repository;
    private final PlayerSettingDefaults defaults;
    private final PlayerSettingCache cache;
    private final Object sessionMonitor = new Object();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final ConcurrentMap<UUID, Long> activeSessionTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UserOperationLock> operationLocks = new ConcurrentHashMap<>();

    public PlayerSettingService(
        @NotNull PlayerSettingRepository repository,
        @NotNull PlayerSettingDefaults defaults,
        @NotNull PlayerSettingCache cache
    ) {
        this.repository = repository;
        this.defaults = defaults;
        this.cache = cache;
    }

    /**
     * 現在のセッションに対して設定をロードします。
     *
     * @param userId ロード対象ユーザー ID
     * @return ロードした設定スナップショット
     */
    public @NotNull PlayerSettingSnapshot loadPlayerSettings(@NotNull UUID userId) {
        long sessionToken = captureSessionToken(userId);
        return withUserOperationLock(userId, () -> loadPlayerSettingsLocked(userId, sessionToken));
    }

    private @NotNull PlayerSettingSnapshot loadPlayerSettingsLocked(@NotNull UUID userId, long sessionToken) {
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
        publishIfSessionActive(snapshot, sessionToken);
        return snapshot;
    }

    /**
     * ログインセッションを開始し、後続の非同期処理で使用するトークンを返します。
     *
     * @param userId セッションを開始するユーザー ID
     * @return 新しいセッショントークン
     */
    public long beginSession(@NotNull UUID userId) {
        synchronized (sessionMonitor) {
            long sessionToken = nextSessionToken();
            activeSessionTokens.put(userId, sessionToken);
            cache.remove(userId);
            return sessionToken;
        }
    }

    /**
     * 現在有効なログインセッションのトークンを取得します。
     *
     * @param userId 対象ユーザー ID
     * @return 有効なセッションのトークン。未ログインの場合は {@code 0}
     */
    public long captureSessionToken(@NotNull UUID userId) {
        return activeSessionTokens.getOrDefault(userId, NO_ACTIVE_SESSION);
    }

    /**
     * 指定セッション向けに設定キャッシュを準備します。
     *
     * @param userId 対象ユーザー ID
     * @param sessionToken ログイン時に取得したセッショントークン
     */
    public void warmup(@NotNull UUID userId, long sessionToken) {
        withUserOperationLock(userId, () -> {
            loadPlayerSettingsLocked(userId, sessionToken);
            return null;
        });
    }

    /**
     * ログインセッションと設定キャッシュを破棄します。
     *
     * @param userId 対象ユーザー ID
     */
    public void clear(@NotNull UUID userId) {
        synchronized (sessionMonitor) {
            activeSessionTokens.remove(userId);
            cache.remove(userId);
        }
    }

    public @NotNull PlayerSettingSnapshot getSnapshot(@NotNull UUID userId) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot != null) {
            return snapshot;
        }
        long sessionToken = captureSessionToken(userId);
        return withUserOperationLock(userId, () -> {
            PlayerSettingSnapshot cachedSnapshot = cache.find(userId);
            if (cachedSnapshot != null) {
                return cachedSnapshot;
            }
            return loadPlayerSettingsLocked(userId, sessionToken);
        });
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

    /**
     * 指定プレイヤーで MSPT・Ping の診断表示が有効かを返します。
     *
     * @param userId 判定対象ユーザー ID
     * @return 診断表示が有効な場合は {@code true}
     */
    public boolean isPerformanceInfoDisplayEnabled(@NotNull UUID userId) {
        Object value = getPlayerSetting(userId, PlayerSettingKey.PERFORMANCE_INFO_DISPLAY);
        return value instanceof Boolean enabled
                ? enabled
                : (Boolean) PlayerSettingKey.PERFORMANCE_INFO_DISPLAY.getDefaultValue();
    }

    /**
     * 指定プレイヤーでオートセーブメッセージが有効かを返します。
     *
     * @param userId 判定対象ユーザー ID
     * @return オートセーブメッセージが有効な場合は {@code true}
     */
    public boolean isAutoSaveMessageEnabled(@NotNull UUID userId) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot == null) {
            return (Boolean) PlayerSettingKey.AUTO_SAVE_MESSAGE.getDefaultValue();
        }
        PlayerSettingEntry entry = snapshot.getEntry(PlayerSettingKey.AUTO_SAVE_MESSAGE);
        Object value = entry == null ? null : entry.getValue();
        return value instanceof Boolean enabled
                ? enabled
                : (Boolean) PlayerSettingKey.AUTO_SAVE_MESSAGE.getDefaultValue();
    }

    /**
     * 指定セッションのプレイヤー設定を更新します。
     *
     * <p>同一ユーザーの更新は直列化されます。呼び出し元はリポジトリ通信を Bukkit
     * メインスレッド外で実行してください。</p>
     *
     * @param request 設定変更要求
     * @param sessionToken 非同期処理の開始前に取得したセッショントークン
     * @return 更新結果
     */
    public @NotNull UpdateResult updatePlayerSetting(
        @NotNull PlayerSettingChangeRequest request,
        long sessionToken
    ) {
        return withUserOperationLock(
            request.userId(),
            () -> updatePlayerSettingLocked(request, sessionToken)
        );
    }

    private @NotNull UpdateResult updatePlayerSettingLocked(
        @NotNull PlayerSettingChangeRequest request,
        long sessionToken
    ) {
        if (!isSessionActive(request.userId(), sessionToken)) {
            return UpdateResult.staleSession(request.settingKey());
        }

        PlayerSettingSnapshot snapshot = cache.find(request.userId());
        if (snapshot == null) {
            Logger.log(LogId.W_5312, request.userId(), "snapshot not cached");
            snapshot = loadPlayerSettingsLocked(request.userId(), sessionToken);
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
            publishIfSessionActive(snapshot.withEntry(updatedEntry), sessionToken);
            return UpdateResult.success(request.settingKey(), request.newValue());
        } catch (OptimisticLockConflictException conflict) {
            PlayerSettingEntry latest = entryFromModel(conflict.getCurrent(), request.settingKey());
            publishIfSessionActive(snapshot.withEntry(latest), sessionToken);
            return UpdateResult.conflict(
                request.settingKey(),
                latest.getValue(),
                PlayerMsgResource.format(PlayerSettingMsgId.P_5320.getId(), request.settingKey().getDisplayNameJa())
            );
        }
    }

    private long nextSessionToken() {
        long sessionToken = sessionSequence.incrementAndGet();
        if (sessionToken != NO_ACTIVE_SESSION) {
            return sessionToken;
        }
        return sessionSequence.incrementAndGet();
    }

    private boolean isSessionActive(@NotNull UUID userId, long sessionToken) {
        return sessionToken != NO_ACTIVE_SESSION
            && activeSessionTokens.getOrDefault(userId, NO_ACTIVE_SESSION) == sessionToken;
    }

    private void publishIfSessionActive(
        @NotNull PlayerSettingSnapshot snapshot,
        long sessionToken
    ) {
        synchronized (sessionMonitor) {
            if (isSessionActive(snapshot.getUserId(), sessionToken)) {
                cache.put(snapshot);
            }
        }
    }

    private <T> T withUserOperationLock(@NotNull UUID userId, @NotNull Supplier<T> operation) {
        UserOperationLock operationLock = operationLocks.compute(userId, (ignored, existing) -> {
            UserOperationLock retained = existing == null ? new UserOperationLock() : existing;
            retained.references++;
            return retained;
        });
        operationLock.lock.lock();
        try {
            return operation.get();
        } finally {
            operationLock.lock.unlock();
            operationLocks.computeIfPresent(userId, (ignored, existing) -> {
                if (existing != operationLock) {
                    return existing;
                }
                operationLock.references--;
                return operationLock.references == 0 ? null : operationLock;
            });
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
        boolean staleSession,
        @NotNull PlayerSettingKey key,
        @Nullable Object value,
        @Nullable String message
    ) {
        public static @NotNull UpdateResult success(@NotNull PlayerSettingKey key, @NotNull Object value) {
            return new UpdateResult(true, false, false, key, value, null);
        }

        public static @NotNull UpdateResult conflict(
            @NotNull PlayerSettingKey key,
            @NotNull Object value,
            @NotNull String message
        ) {
            return new UpdateResult(false, true, false, key, value, message);
        }

        public static @NotNull UpdateResult staleSession(@NotNull PlayerSettingKey key) {
            return new UpdateResult(false, false, true, key, null, null);
        }
    }

    private static final class UserOperationLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}

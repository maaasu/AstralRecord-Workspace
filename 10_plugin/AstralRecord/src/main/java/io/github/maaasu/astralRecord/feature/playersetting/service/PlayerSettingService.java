package io.github.maaasu.astralRecord.feature.playersetting.service;

import com.google.gson.JsonElement;
import java.util.List;

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
import io.github.maaasu.astralRecord.feature.mutation.service.PendingStateStore;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final Executor asyncExecutor;
    private final Object sessionMonitor = new Object();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final ConcurrentMap<UUID, Long> activeSessionTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UserOperationLock> operationLocks = new ConcurrentHashMap<>();
    /** userごとの API delivery を一つにし、shutdown drain と通常再送を重複送信させない。 */
    private final ConcurrentMap<UUID, Object> deliveryLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, EnumMap<PlayerSettingKey, PendingSetting>> pendingSettings = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> deliveryScheduled = new ConcurrentHashMap<>();
    private final AtomicLong pendingRevisionSequence = new AtomicLong();
    private @Nullable PendingStateStore pendingStateStore;

    public PlayerSettingService(
        @NotNull PlayerSettingRepository repository,
        @NotNull PlayerSettingDefaults defaults,
        @NotNull PlayerSettingCache cache
    ) {
        this(repository, defaults, cache, Runnable::run);
    }

    /**
     * プレイヤー設定サービスを構築します。
     *
     * @param repository API 通信を行う repository
     * @param defaults key ごとの既定値
     * @param cache online session 用の snapshot cache
     * @param asyncExecutor API 送信と再送を実行する非同期 executor
     */
    public PlayerSettingService(
        @NotNull PlayerSettingRepository repository,
        @NotNull PlayerSettingDefaults defaults,
        @NotNull PlayerSettingCache cache,
        @NotNull Executor asyncExecutor
    ) {
        this.repository = repository;
        this.defaults = defaults;
        this.cache = cache;
        this.asyncExecutor = asyncExecutor;
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

        PlayerSettingSnapshot snapshot = overlayPending(new PlayerSettingSnapshot(userId, entries));
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
            if (!hasPendingSettings(userId)) {
                cache.remove(userId);
            }
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
        scheduleDelivery(userId, 0L);
    }

    /**
     * ログインセッションと設定キャッシュを破棄します。
     *
     * @param userId 対象ユーザー ID
     */
    public void clear(@NotNull UUID userId) {
        synchronized (sessionMonitor) {
            activeSessionTokens.remove(userId);
            if (!hasPendingSettings(userId)) {
                cache.remove(userId);
            }
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
     * 指定プレイヤーでバフ情報のサイドバー表示が有効かを返します。
     *
     * @param userId 判定対象ユーザー ID
     * @return バフ情報をサイドバーへ表示する場合は {@code true}
     */
    public boolean isBuffSidebarDisplayEnabled(@NotNull UUID userId) {
        Object value = getPlayerSetting(userId, PlayerSettingKey.BUFF_SIDEBAR_DISPLAY);
        return value instanceof Boolean enabled
                ? enabled
                : (Boolean) PlayerSettingKey.BUFF_SIDEBAR_DISPLAY.getDefaultValue();
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
     * 指定プレイヤーで防具の身体描画が有効かを、API 通信を行わずキャッシュから返します。
     *
     * <p>パケット送信スレッドから呼ばれるため、cache miss 時は既定値へ直ちに
     * fallback します。</p>
     *
     * @param userId 判定対象ユーザー ID
     * @return 防具の身体描画が有効な場合は {@code true}
     */
    public boolean isArmorDisplayEnabled(@NotNull UUID userId) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot == null) {
            return (Boolean) PlayerSettingKey.ARMOR_DISPLAY.getDefaultValue();
        }
        PlayerSettingEntry entry = snapshot.getEntry(PlayerSettingKey.ARMOR_DISPLAY);
        Object value = entry == null ? null : entry.getValue();
        return value instanceof Boolean enabled
            ? enabled
            : (Boolean) PlayerSettingKey.ARMOR_DISPLAY.getDefaultValue();
    }

    /**
     * 指定プレイヤーでアクションリング長押し選択が有効かを、API 通信を行わずキャッシュから返します。
     *
     * <p>クリック入力の処理中に呼ばれるため、cache miss 時は従来操作を維持する既定値 {@code false} へ
     * 直ちに fallback します。</p>
     *
     * @param userId 判定対象ユーザー ID
     * @return 長押しで選択を確定する場合は {@code true}
     */
    public boolean isActionRingHoldSelectEnabled(@NotNull UUID userId) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot == null) {
            return (Boolean) PlayerSettingKey.ACTION_RING_HOLD_SELECT.getDefaultValue();
        }
        PlayerSettingEntry entry = snapshot.getEntry(PlayerSettingKey.ACTION_RING_HOLD_SELECT);
        Object value = entry == null ? null : entry.getValue();
        return value instanceof Boolean enabled
            ? enabled
            : (Boolean) PlayerSettingKey.ACTION_RING_HOLD_SELECT.getDefaultValue();
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
            // Bukkit main thread の設定操作で API 読込を発生させない。join warmup の遅延中でも
            // 既定値から直ちにローカル反映し、取得結果は pending 値の metadata だけへ重ねる。
            snapshot = new PlayerSettingSnapshot(request.userId(), createDefaultEntries());
            publishIfSessionActive(snapshot, sessionToken);
        }

        PlayerSettingEntry currentEntry = snapshot.getEntry(request.settingKey());
        if (currentEntry == null) {
            currentEntry = defaultEntry(request.settingKey());
        }

        PlayerSettingEntry localEntry = new PlayerSettingEntry(
            currentEntry.getUserSettingId(), request.settingKey(), request.newValue(), currentEntry.getVersion()
        );
        publishIfSessionActive(snapshot.withEntry(localEntry), sessionToken);
        PendingSetting pending = new PendingSetting(
            request.settingKey(), request.newValue(), request.requestedBy(), pendingRevisionSequence.incrementAndGet()
        );
        pendingSettings.compute(request.userId(), (ignored, current) -> {
            EnumMap<PlayerSettingKey, PendingSetting> updated = current == null
                ? new EnumMap<>(PlayerSettingKey.class)
                : new EnumMap<>(current);
            updated.put(request.settingKey(), pending);
            return updated;
        });
        scheduleDelivery(request.userId(), 0L);
        return UpdateResult.success(request.settingKey(), request.newValue());
    }

    /**
     * 未送信の設定を非同期 write-behind に登録します。同一 user/key は最後の値だけを送信し、
     * API 応答は値をロールバックせず ID・version metadata のみを更新します。
     *
     * @param userId 送信対象ユーザー ID
     * @param delayMillis 再送までの待機時間
     */
    private void scheduleDelivery(@NotNull UUID userId, long delayMillis) {
        if (deliveryScheduled.putIfAbsent(userId, Boolean.TRUE) != null) {
            return;
        }
        try {
            java.util.concurrent.CompletableFuture.delayedExecutor(
                Math.max(0L, delayMillis),
                java.util.concurrent.TimeUnit.MILLISECONDS,
                asyncExecutor
            ).execute(() -> deliverPending(userId));
        } catch (Throwable schedulingFailure) {
            deliveryScheduled.remove(userId);
            Logger.log(LogId.W_5312, userId, schedulingFailure.getClass().getSimpleName());
        }
    }

    private void deliverPending(@NotNull UUID userId) {
        deliveryScheduled.remove(userId);
        withDeliveryLock(userId, () -> deliverPendingLocked(userId));
    }

    private void deliverPendingLocked(@NotNull UUID userId) {
        PendingSetting pending = nextPendingSetting(userId);
        if (pending == null) {
            return;
        }

        try {
            deliverOne(userId, pending);
        } catch (RuntimeException failure) {
            Logger.log(LogId.W_5312, userId, failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage());
            scheduleDelivery(userId, 1_000L);
            return;
        }
        scheduleDelivery(userId, 0L);
    }

    private @Nullable PendingSetting nextPendingSetting(@NotNull UUID userId) {
        return withUserOperationLock(userId, () -> {
            EnumMap<PlayerSettingKey, PendingSetting> entries = pendingSettings.get(userId);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            return entries.values().iterator().next();
        });
    }

    private void deliverOne(@NotNull UUID userId, @NotNull PendingSetting pending) {
        DeliveryContext context = withUserOperationLock(userId, () -> {
            PendingSetting currentPending = pendingSetting(userId, pending.key());
            if (currentPending != pending) {
                return null;
            }
            PlayerSettingSnapshot snapshot = cache.find(userId);
            PlayerSettingEntry currentEntry = snapshot == null ? defaultEntry(pending.key()) : snapshot.getEntry(pending.key());
            if (currentEntry == null) {
                currentEntry = defaultEntry(pending.key());
            }
            return new DeliveryContext(currentPending, currentEntry, serializeValue(pending.key(), pending.value()));
        });
        if (context == null) {
            return;
        }
        persistPendingSettings(userId);
        try {
            PlayerSettingModel updated = context.entry().getUserSettingId() == null || context.entry().getVersion() == null
                ? repository.create(userId, pending.key().getCode(), context.valueJson(), pending.requestedBy())
                : repository.update(
                    context.entry().getUserSettingId(),
                    context.valueJson(),
                    context.entry().getVersion(),
                    pending.requestedBy()
                );
            if (updated == null) {
                updated = repository.create(userId, pending.key().getCode(), context.valueJson(), pending.requestedBy());
            }
            PlayerSettingModel acknowledged = updated;
            boolean hasRemaining = withUserOperationLock(
                userId,
                () -> acknowledgeMetadata(userId, context.pending(), acknowledged)
            );
            // user状態のlockを解放してから、async writerだけがローカルファイルを更新する。
            if (hasRemaining) {
                persistPendingSettings(userId);
            } else {
                deletePersistedSettings(userId);
            }
        } catch (OptimisticLockConflictException conflict) {
            withUserOperationLock(userId, () -> {
                // 最新 version を取り込みつつ、ユーザーが最後に選んだ値は維持して再送する。
                applyLatestMetadata(userId, pending.key(), pending.value(), conflict.getCurrent());
                return null;
            });
            scheduleDelivery(userId, 0L);
        }
    }

    private boolean acknowledgeMetadata(
        @NotNull UUID userId,
        @NotNull PendingSetting delivered,
        @NotNull PlayerSettingModel updated
    ) {
        updateEntryMetadata(userId, delivered.key(), delivered.value(), updated);
        pendingSettings.computeIfPresent(userId, (ignored, entries) -> {
            EnumMap<PlayerSettingKey, PendingSetting> remaining = new EnumMap<>(entries);
            if (remaining.get(delivered.key()) == delivered) {
                remaining.remove(delivered.key());
            }
            return remaining.isEmpty() ? null : remaining;
        });
        return hasPendingSettings(userId);
    }

    private void applyLatestMetadata(
        @NotNull UUID userId,
        @NotNull PlayerSettingKey key,
        @NotNull Object desiredValue,
        @NotNull PlayerSettingModel latest
    ) {
        updateEntryMetadata(userId, key, desiredValue, latest);
    }

    private void updateEntryMetadata(
        @NotNull UUID userId,
        @NotNull PlayerSettingKey key,
        @NotNull Object desiredValue,
        @NotNull PlayerSettingModel model
    ) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot == null) {
            snapshot = new PlayerSettingSnapshot(userId, createDefaultEntries());
        }
        PlayerSettingEntry existing = snapshot.getEntry(key);
        Object currentValue = existing == null ? desiredValue : existing.getValue();
        cache.put(snapshot.withEntry(new PlayerSettingEntry(
            model.getUserSettingId(), key, currentValue, model.getVersion()
        )));
    }

    private @NotNull PlayerSettingSnapshot overlayPending(@NotNull PlayerSettingSnapshot snapshot) {
        EnumMap<PlayerSettingKey, PendingSetting> pending = pendingSettings.get(snapshot.getUserId());
        if (pending == null || pending.isEmpty()) {
            return snapshot;
        }
        PlayerSettingSnapshot overlaid = snapshot;
        for (PendingSetting entry : pending.values()) {
            PlayerSettingEntry remote = overlaid.getEntry(entry.key());
            overlaid = overlaid.withEntry(new PlayerSettingEntry(
                remote == null ? null : remote.getUserSettingId(),
                entry.key(),
                entry.value(),
                remote == null ? null : remote.getVersion()
            ));
        }
        return overlaid;
    }

    private @Nullable PendingSetting pendingSetting(@NotNull UUID userId, @NotNull PlayerSettingKey key) {
        EnumMap<PlayerSettingKey, PendingSetting> settings = pendingSettings.get(userId);
        return settings == null ? null : settings.get(key);
    }

    private boolean hasPendingSettings(@NotNull UUID userId) {
        EnumMap<PlayerSettingKey, PendingSetting> settings = pendingSettings.get(userId);
        return settings != null && !settings.isEmpty();
    }

    private void persistPendingSettings(@NotNull UUID userId) {
        PendingStateStore store = pendingStateStore;
        if (store == null) {
            return;
        }
        String payload = withUserOperationLock(userId, () -> serializePendingSettings(userId));
        if (payload == null) {
            store.delete(userId);
            return;
        }
        store.write(userId, payload);
    }

    private @Nullable String serializePendingSettings(@NotNull UUID userId) {
        EnumMap<PlayerSettingKey, PendingSetting> pending = pendingSettings.get(userId);
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        for (PendingSetting entry : pending.values()) {
            JsonObject value = new JsonObject();
            value.addProperty("key", entry.key().getCode());
            value.addProperty("valueJson", serializeValue(entry.key(), entry.value()));
            value.addProperty("requestedBy", entry.requestedBy().toString());
            value.addProperty("revision", entry.revision());
            entries.add(value);
        }
        root.add("entries", entries);
        return root.toString();
    }

    private void deletePersistedSettings(@NotNull UUID userId) {
        PendingStateStore store = pendingStateStore;
        if (store != null) {
            store.delete(userId);
        }
    }

    private void restorePendingSettings() {
        PendingStateStore store = pendingStateStore;
        if (store == null) {
            return;
        }
        for (UUID userId : store.subjects()) {
            try {
                String json = store.read(userId);
                if (json == null) {
                    continue;
                }
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                EnumMap<PlayerSettingKey, PendingSetting> restored = new EnumMap<>(PlayerSettingKey.class);
                for (JsonElement element : root.getAsJsonArray("entries")) {
                    JsonObject entry = element.getAsJsonObject();
                    PlayerSettingKey key = PlayerSettingKey.fromInput(entry.get("key").getAsString());
                    if (key == null) {
                        continue;
                    }
                    Object value = parseJsonValue(key, entry.get("valueJson").getAsString(), userId);
                    UUID requestedBy = UUID.fromString(entry.get("requestedBy").getAsString());
                    long revision = entry.has("revision") ? entry.get("revision").getAsLong()
                        : pendingRevisionSequence.incrementAndGet();
                    pendingRevisionSequence.accumulateAndGet(revision, Math::max);
                    restored.put(key, new PendingSetting(key, value, requestedBy, revision));
                }
                if (restored.isEmpty()) {
                    store.delete(userId);
                } else {
                    pendingSettings.put(userId, restored);
                    // restore と login warmup の完了順は保証しない。warmup済みなら直ちに
                    // 最後の希望値をoverlayしてdeliveryへ渡し、古いAPI値で画面を戻さない。
                    PlayerSettingSnapshot snapshot = cache.find(userId);
                    if (snapshot != null) {
                        cache.put(overlayPending(snapshot));
                        scheduleDelivery(userId, 0L);
                    }
                }
            } catch (RuntimeException failure) {
                Logger.log(LogId.W_5312, userId, failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * plugin disable 前に未送信値を async writer 上で保存・送信し、完了を返します。
     * API 送信に失敗しても dirty は削除せず、最後に PendingStateStore へ書き戻すため、
     * bootstrap はこの Future 完了後に writer を停止できます。
     *
     * @return 全 user の shutdown drain 完了 Future
     */
    public @NotNull CompletableFuture<Void> flushPendingWrites() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            asyncExecutor.execute(() -> {
                try {
                    List<UUID> users = List.copyOf(pendingSettings.keySet());
                    // delivery lock/API応答待ちに入る前に、全userの最新dirtyを durable にする。
                    for (UUID userId : users) {
                        persistPendingSettings(userId);
                    }
                    for (UUID userId : users) {
                        drainPendingWritesForShutdown(userId);
                    }
                    completion.complete(null);
                } catch (RuntimeException failure) {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            completion.completeExceptionally(schedulingFailure);
        }
        return completion;
    }

    private void drainPendingWritesForShutdown(@NotNull UUID userId) {
        withDeliveryLock(userId, () -> {
            int attemptsRemaining = Math.max(2, PlayerSettingKey.values().length * 3);
            while (attemptsRemaining-- > 0) {
                PendingSetting pending = nextPendingSetting(userId);
                if (pending == null) {
                    break;
                }
                try {
                    deliverOne(userId, pending);
                } catch (RuntimeException failure) {
                    Logger.log(LogId.W_5312, userId, failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage());
                    break;
                }
            }
            // 競合反復上限・通信失敗時も、再ログイン時に overlay できる値を残す。
            persistPendingSettings(userId);
        });
    }

    /**
     * user設定の未受領write-behindを復元するローカル保存先を設定します。
     * ファイル操作は delivery executor だけで行い、Bukkit main thread では実行しません。
     *
     * @param root `player-settings` 用の専用ディレクトリ
     */
    public void setPersistence(@NotNull Path root) {
        pendingStateStore = new PendingStateStore(root);
        try {
            asyncExecutor.execute(this::restorePendingSettings);
        } catch (Throwable failure) {
            Logger.log(LogId.W_5312, root.toString(), failure.getClass().getSimpleName());
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

    private void withDeliveryLock(@NotNull UUID userId, @NotNull Runnable operation) {
        Object lock = deliveryLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            operation.run();
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

    private record PendingSetting(
        @NotNull PlayerSettingKey key,
        @NotNull Object value,
        @NotNull UUID requestedBy,
        long revision
    ) {
    }

    private record DeliveryContext(
        @NotNull PendingSetting pending,
        @NotNull PlayerSettingEntry entry,
        @NotNull String valueJson
    ) {
    }
}

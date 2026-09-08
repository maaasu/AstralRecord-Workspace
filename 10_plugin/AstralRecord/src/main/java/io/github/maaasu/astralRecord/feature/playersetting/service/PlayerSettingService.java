package io.github.maaasu.astralRecord.feature.playersetting.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 初期 GET、cache 参照、player-state section の作成を扱う設定サービスです。 */
public final class PlayerSettingService {
    private static final long NO_ACTIVE_SESSION = 0L;
    private static final String SECTION_NAME = "playerSettings";
    private final PlayerSettingRepository repository;
    private final PlayerSettingDefaults defaults;
    private final PlayerSettingCache cache;
    private final Object sessionMonitor = new Object();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong revisionSequence = new AtomicLong();
    private final ConcurrentMap<UUID, Long> activeSessionTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UserOperationLock> operationLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> userIdsByAccount = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> accountIdsByUser = new ConcurrentHashMap<>();
    private volatile Consumer<UUID> localPlayerSaveRequester = ignored -> { };
    /** 通信失敗時にも disk へ退避せず memory に保持する dirty state。 */
    private final ConcurrentMap<UUID, EnumMap<PlayerSettingKey, PendingSetting>> pendingSettings = new ConcurrentHashMap<>();

    public PlayerSettingService(@NotNull PlayerSettingRepository repository, @NotNull PlayerSettingDefaults defaults,
                                @NotNull PlayerSettingCache cache) {
        this.repository = repository;
        this.defaults = defaults;
        this.cache = cache;
    }

    /** 独自 writer 廃止後も既存の生成箇所と互換にする。executor は使用しない。 */
    public PlayerSettingService(@NotNull PlayerSettingRepository repository, @NotNull PlayerSettingDefaults defaults,
                                @NotNull PlayerSettingCache cache, @NotNull Executor ignoredAsyncExecutor) {
        this(repository, defaults, cache);
    }

    /** 初期 GET の結果を current session の cache へ公開する。 */
    public @NotNull PlayerSettingSnapshot loadPlayerSettings(@NotNull UUID userId) {
        return withLock(userId, () -> loadPlayerSettingsLocked(userId, captureSessionToken(userId)));
    }

    private @NotNull PlayerSettingSnapshot loadPlayerSettingsLocked(@NotNull UUID userId, long token) {
        Map<PlayerSettingKey, PlayerSettingEntry> entries = createDefaultEntries();
        try {
            for (PlayerSettingModel model : repository.findByUserId(userId)) {
                PlayerSettingKey key = PlayerSettingKey.fromInput(model.getSettingKey());
                if (key == null) {
                    Logger.log(LogId.W_5311, userId, model.getSettingKey(), "unknown key");
                    continue;
                }
                entries.put(key, new PlayerSettingEntry(model.getUserSettingId(), key,
                    parseJsonValue(key, model.getSettingValueJson(), userId), model.getVersion()));
            }
        } catch (Exception exception) {
            Logger.log(LogId.W_5310, userId,
                exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
        PlayerSettingSnapshot snapshot = overlayPending(new PlayerSettingSnapshot(userId, entries));
        publishIfActive(snapshot, token);
        return snapshot;
    }

    public long beginSession(@NotNull UUID userId) {
        synchronized (sessionMonitor) {
            long token = nextSessionToken();
            activeSessionTokens.put(userId, token);
            if (!hasPending(userId)) cache.remove(userId);
            return token;
        }
    }

    public long captureSessionToken(@NotNull UUID userId) {
        return activeSessionTokens.getOrDefault(userId, NO_ACTIVE_SESSION);
    }

    /** 設定変更を account 単位の 200ms 集約保存へ接続します。 */
    public void setLocalPlayerSaveRequester(@NotNull Consumer<UUID> requester) {
        localPlayerSaveRequester = requester;
    }

    /** 初期 GET 専用。個別 POST/PUT と独自再送は行わない。 */
    public void warmup(@NotNull UUID userId, long token) {
        withLock(userId, () -> { loadPlayerSettingsLocked(userId, token); return null; });
    }

    public void clear(@NotNull UUID userId) {
        synchronized (sessionMonitor) {
            activeSessionTokens.remove(userId);
            if (!hasPending(userId)) {
                cache.remove(userId);
                forgetAccount(userId);
            }
        }
    }

    public @NotNull PlayerSettingSnapshot getSnapshot(@NotNull UUID userId) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        if (snapshot != null) return snapshot;
        return withLock(userId, () -> {
            PlayerSettingSnapshot cached = cache.find(userId);
            return cached == null ? loadPlayerSettingsLocked(userId, captureSessionToken(userId)) : cached;
        });
    }

    public @NotNull Object getPlayerSetting(@NotNull UUID userId, @NotNull PlayerSettingKey key) {
        PlayerSettingEntry entry = getSnapshot(userId).getEntry(key);
        return entry == null ? defaults.resolveDefault(key) : entry.getValue();
    }

    public double getParticleDensityScale(@NotNull UUID userId) {
        Object value = getPlayerSetting(userId, PlayerSettingKey.PARTICLE_DENSITY);
        return value instanceof ParticleDensity density ? density.getDensityScale() : ParticleDensity.NORMAL.getDensityScale();
    }
    public boolean isDamageLogDisplayEnabled(@NotNull UUID userId) { return booleanSetting(userId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, true); }
    public boolean isDamageLogMessageEnabled(@NotNull UUID userId) { return booleanSetting(userId, PlayerSettingKey.DAMAGE_LOG_MESSAGE, false); }
    public boolean isPerformanceInfoDisplayEnabled(@NotNull UUID userId) { return booleanSetting(userId, PlayerSettingKey.PERFORMANCE_INFO_DISPLAY, false); }
    public boolean isBuffSidebarDisplayEnabled(@NotNull UUID userId) { return booleanSetting(userId, PlayerSettingKey.BUFF_SIDEBAR_DISPLAY, false); }
    public boolean isDropLogDisplayEnabled(@NotNull UUID userId) { return cachedBooleanSetting(userId, PlayerSettingKey.DROP_LOG_DISPLAY, true); }
    public boolean isAutoSaveMessageEnabled(@NotNull UUID userId) { return cachedBooleanSetting(userId, PlayerSettingKey.AUTO_SAVE_MESSAGE, false); }
    public boolean isArmorDisplayEnabled(@NotNull UUID userId) { return cachedBooleanSetting(userId, PlayerSettingKey.ARMOR_DISPLAY, true); }
    public boolean isActionRingHoldSelectEnabled(@NotNull UUID userId) { return cachedBooleanSetting(userId, PlayerSettingKey.ACTION_RING_HOLD_SELECT, false); }

    /** Bukkit 操作時は cache と dirty state だけを即時更新する。 */
    public @NotNull UpdateResult updatePlayerSetting(@NotNull PlayerSettingChangeRequest request, long token) {
        UUID accountId = rememberOnlineAccount(request.userId());
        UpdateResult result = withLock(request.userId(), () -> {
            if (!isActive(request.userId(), token)) return UpdateResult.staleSession(request.settingKey());
            PlayerSettingSnapshot snapshot = cache.find(request.userId());
            if (snapshot == null) snapshot = new PlayerSettingSnapshot(request.userId(), createDefaultEntries());
            PlayerSettingEntry old = snapshot.getEntry(request.settingKey());
            if (old == null) old = defaultEntry(request.settingKey());
            cache.put(snapshot.withEntry(new PlayerSettingEntry(old.getUserSettingId(), request.settingKey(),
                request.newValue(), old.getVersion())));
            PendingSetting pending = new PendingSetting(request.settingKey(), revisionSequence.incrementAndGet());
            pendingSettings.compute(request.userId(), (ignored, previous) -> {
                EnumMap<PlayerSettingKey, PendingSetting> next = previous == null
                    ? new EnumMap<>(PlayerSettingKey.class) : new EnumMap<>(previous);
                next.put(request.settingKey(), pending);
                return next;
            });
            return UpdateResult.success(request.settingKey(), request.newValue());
        });
        if (result.success() && accountId != null) {
            localPlayerSaveRequester.accept(accountId);
        }
        return result;
    }

    /** dirty な完成状態を通常 save lane 用の section として返す。 */
    public @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        UUID userId = resolveUserId(accountId);
        if (userId == null) return null;
        CapturedSnapshot captured = withLock(userId, () -> capture(userId));
        if (captured == null) return null;
        JsonObject payload = new JsonObject();
        payload.addProperty("userId", userId.toString());
        payload.addProperty("clientRevision", captured.revision());
        JsonArray settings = new JsonArray();
        for (CapturedSetting setting : captured.settings().values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("userSettingId", setting.id().toString());
            entry.addProperty("settingKey", setting.key().getCode());
            entry.addProperty("settingValueJson", serializeValue(setting.key(), setting.value()));
            if (setting.expectedVersion() != null) entry.addProperty("expectedVersion", setting.expectedVersion());
            settings.add(entry);
        }
        payload.add("settings", settings);
        return new PlayerStateSection(SECTION_NAME, payload, ack -> acknowledge(captured, ack));
    }

    private @Nullable CapturedSnapshot capture(@NotNull UUID userId) {
        EnumMap<PlayerSettingKey, PendingSetting> dirty = pendingSettings.get(userId);
        if (dirty == null || dirty.isEmpty()) return null;
        PlayerSettingSnapshot complete = ensureComplete(cache.find(userId) == null
            ? new PlayerSettingSnapshot(userId, createDefaultEntries()) : cache.find(userId));
        Map<PlayerSettingKey, CapturedSetting> captured = new LinkedHashMap<>();
        PlayerSettingSnapshot identified = complete;
        for (PlayerSettingKey key : PlayerSettingKey.values()) {
            PlayerSettingEntry entry = complete.getEntry(key);
            UUID id = entry.getUserSettingId() == null ? UUID.randomUUID() : entry.getUserSettingId();
            if (!id.equals(entry.getUserSettingId())) {
                entry = new PlayerSettingEntry(id, key, entry.getValue(), entry.getVersion());
                identified = identified.withEntry(entry);
            }
            captured.put(key, new CapturedSetting(id, key, entry.getValue(), entry.getVersion()));
        }
        cache.put(identified);
        long revision = dirty.values().stream().mapToLong(PendingSetting::revision).max().orElseThrow();
        return new CapturedSnapshot(userId, revision, captured);
    }

    private void acknowledge(@NotNull CapturedSnapshot captured, @NotNull JsonElement acknowledgement) {
        if (!acknowledgement.isJsonObject()) throw new IllegalStateException("Player settings acknowledgement must be an object");
        JsonObject ack = acknowledgement.getAsJsonObject();
        if (!ack.has("clientRevision") || ack.get("clientRevision").getAsLong() != captured.revision()
            || !ack.has("settings") || !ack.get("settings").isJsonArray()) {
            throw new IllegalStateException("Player settings acknowledgement is invalid");
        }
        Map<PlayerSettingKey, AcknowledgedSetting> versions = parseAcknowledgement(ack.getAsJsonArray("settings"));
        if (versions.size() != PlayerSettingKey.values().length || !versions.keySet().containsAll(captured.settings().keySet())) {
            throw new IllegalStateException("Player settings acknowledgement is incomplete");
        }
        withLock(captured.userId(), () -> {
            PlayerSettingSnapshot current = cache.find(captured.userId());
            PlayerSettingSnapshot merged = ensureComplete(current == null
                ? new PlayerSettingSnapshot(captured.userId(), createDefaultEntries()) : current);
            for (Map.Entry<PlayerSettingKey, AcknowledgedSetting> entry : versions.entrySet()) {
                PlayerSettingEntry local = merged.getEntry(entry.getKey());
                Object value = local == null ? captured.settings().get(entry.getKey()).value() : local.getValue();
                merged = merged.withEntry(new PlayerSettingEntry(entry.getValue().id(), entry.getKey(), value, entry.getValue().version()));
            }
            cache.put(merged);
            pendingSettings.computeIfPresent(captured.userId(), (ignored, dirty) -> {
                EnumMap<PlayerSettingKey, PendingSetting> remaining = new EnumMap<>(dirty);
                remaining.entrySet().removeIf(entry -> entry.getValue().revision() <= captured.revision());
                return remaining.isEmpty() ? null : remaining;
            });
            if (!hasPending(captured.userId()) && !isActive(captured.userId(), captureSessionToken(captured.userId()))) {
                cache.remove(captured.userId());
                forgetAccount(captured.userId());
            }
            return null;
        });
    }

    private @NotNull Map<PlayerSettingKey, AcknowledgedSetting> parseAcknowledgement(@NotNull JsonArray array) {
        Map<PlayerSettingKey, AcknowledgedSetting> result = new EnumMap<>(PlayerSettingKey.class);
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalStateException("Player settings acknowledgement entry is invalid");
            JsonObject entry = element.getAsJsonObject();
            PlayerSettingKey key = PlayerSettingKey.fromInput(requiredString(entry, "settingKey"));
            if (key == null || !entry.has("version") || result.put(key, new AcknowledgedSetting(
                UUID.fromString(requiredString(entry, "userSettingId")), entry.get("version").getAsInt())) != null) {
                throw new IllegalStateException("Player settings acknowledgement contains invalid keys");
            }
        }
        return result;
    }

    private @NotNull String requiredString(@NotNull JsonObject object, @NotNull String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) throw new IllegalStateException("Player settings acknowledgement is missing " + name);
        return object.get(name).getAsString();
    }

    private boolean booleanSetting(UUID userId, PlayerSettingKey key, boolean fallback) {
        Object value = getPlayerSetting(userId, key);
        return value instanceof Boolean enabled ? enabled : fallback;
    }
    private boolean cachedBooleanSetting(UUID userId, PlayerSettingKey key, boolean fallback) {
        PlayerSettingSnapshot snapshot = cache.find(userId);
        PlayerSettingEntry entry = snapshot == null ? null : snapshot.getEntry(key);
        return entry != null && entry.getValue() instanceof Boolean enabled ? enabled : fallback;
    }
    private PlayerSettingSnapshot ensureComplete(PlayerSettingSnapshot source) {
        PlayerSettingSnapshot complete = source;
        for (PlayerSettingKey key : PlayerSettingKey.values()) if (complete.getEntry(key) == null) complete = complete.withEntry(defaultEntry(key));
        return complete;
    }
    private PlayerSettingSnapshot overlayPending(PlayerSettingSnapshot remote) {
        EnumMap<PlayerSettingKey, PendingSetting> dirty = pendingSettings.get(remote.getUserId());
        PlayerSettingSnapshot local = cache.find(remote.getUserId());
        if (dirty == null || local == null) return remote;
        PlayerSettingSnapshot result = remote;
        for (PlayerSettingKey key : dirty.keySet()) {
            PlayerSettingEntry localEntry = local.getEntry(key);
            PlayerSettingEntry remoteEntry = result.getEntry(key);
            if (localEntry != null) result = result.withEntry(new PlayerSettingEntry(
                remoteEntry == null ? localEntry.getUserSettingId() : remoteEntry.getUserSettingId(), key,
                localEntry.getValue(), remoteEntry == null ? localEntry.getVersion() : remoteEntry.getVersion()));
        }
        return result;
    }
    private Map<PlayerSettingKey, PlayerSettingEntry> createDefaultEntries() {
        Map<PlayerSettingKey, PlayerSettingEntry> entries = new EnumMap<>(PlayerSettingKey.class);
        for (PlayerSettingKey key : PlayerSettingKey.values()) entries.put(key, defaultEntry(key));
        return entries;
    }
    private PlayerSettingEntry defaultEntry(PlayerSettingKey key) { return new PlayerSettingEntry(null, key, defaults.resolveDefault(key), null); }
    private Object parseJsonValue(PlayerSettingKey key, String source, UUID userId) {
        try {
            JsonObject value = JsonParser.parseString(source).getAsJsonObject();
            if (key.isBooleanValue()) return value.get("enabled").getAsBoolean();
            if (key.isParticleDensityValue()) {
                ParticleDensity density = ParticleDensity.fromInput(value.get("value").getAsString());
                if (density != null) return density;
            }
            throw new IllegalArgumentException("invalid value");
        } catch (Exception exception) {
            Logger.log(LogId.W_5311, userId, key.getCode(), exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            return defaults.resolveDefault(key);
        }
    }
    private String serializeValue(PlayerSettingKey key, Object value) {
        JsonObject result = new JsonObject();
        if (key.isBooleanValue()) { result.addProperty("enabled", (Boolean) value); return result.toString(); }
        if (key.isParticleDensityValue()) { result.addProperty("value", ((ParticleDensity) value).getCode()); return result.toString(); }
        throw new IllegalArgumentException("Unsupported player setting key: " + key.getCode());
    }
    private boolean hasPending(UUID userId) { var dirty = pendingSettings.get(userId); return dirty != null && !dirty.isEmpty(); }
    private @Nullable UUID resolveUserId(@NotNull UUID accountId) {
        UUID mapped = userIdsByAccount.get(accountId);
        if (mapped != null) return mapped;
        return AstPlayerCache.getAll().stream()
            .filter(player -> accountId.equals(player.getAccount().getUuid()))
            .findFirst()
            .map(player -> {
                UUID userId = player.getUser().getUuid();
                rememberAccount(userId, accountId);
                return userId;
            })
            .orElse(null);
    }
    private @Nullable UUID rememberOnlineAccount(@NotNull UUID userId) {
        UUID mapped = accountIdsByUser.get(userId);
        if (mapped != null) return mapped;
        return AstPlayerCache.getAll().stream()
            .filter(player -> userId.equals(player.getUser().getUuid()))
            .findFirst()
            .map(player -> {
                UUID accountId = player.getAccount().getUuid();
                rememberAccount(userId, accountId);
                return accountId;
            })
            .orElse(null);
    }
    private void rememberAccount(@NotNull UUID userId, @NotNull UUID accountId) {
        UUID previousAccount = accountIdsByUser.put(userId, accountId);
        if (previousAccount != null && !previousAccount.equals(accountId)) {
            userIdsByAccount.remove(previousAccount, userId);
        }
        UUID previousUser = userIdsByAccount.put(accountId, userId);
        if (previousUser != null && !previousUser.equals(userId)) {
            accountIdsByUser.remove(previousUser, accountId);
        }
    }
    private void forgetAccount(@NotNull UUID userId) {
        UUID accountId = accountIdsByUser.remove(userId);
        if (accountId != null) userIdsByAccount.remove(accountId, userId);
    }
    private long nextSessionToken() { long token = sessionSequence.incrementAndGet(); return token == 0L ? sessionSequence.incrementAndGet() : token; }
    private boolean isActive(UUID userId, long token) { return token != 0L && activeSessionTokens.getOrDefault(userId, 0L) == token; }
    private void publishIfActive(PlayerSettingSnapshot snapshot, long token) {
        synchronized (sessionMonitor) { if (isActive(snapshot.getUserId(), token)) cache.put(snapshot); }
    }
    private <T> T withLock(UUID userId, Supplier<T> work) {
        UserOperationLock holder = operationLocks.compute(userId, (ignored, existing) -> { UserOperationLock lock = existing == null ? new UserOperationLock() : existing; lock.references++; return lock; });
        holder.lock.lock();
        try { return work.get(); }
        finally {
            holder.lock.unlock();
            operationLocks.computeIfPresent(userId, (ignored, existing) -> { if (existing != holder) return existing; holder.references--; return holder.references == 0 ? null : holder; });
        }
    }

    public record UpdateResult(boolean success, boolean conflict, boolean staleSession, @NotNull PlayerSettingKey key,
                               @Nullable Object value, @Nullable String message) {
        public static @NotNull UpdateResult success(@NotNull PlayerSettingKey key, @NotNull Object value) { return new UpdateResult(true, false, false, key, value, null); }
        public static @NotNull UpdateResult conflict(@NotNull PlayerSettingKey key, @NotNull Object value, @NotNull String message) { return new UpdateResult(false, true, false, key, value, message); }
        public static @NotNull UpdateResult staleSession(@NotNull PlayerSettingKey key) { return new UpdateResult(false, false, true, key, null, null); }
    }
    private static final class UserOperationLock { private final ReentrantLock lock = new ReentrantLock(); private int references; }
    private record PendingSetting(@NotNull PlayerSettingKey key, long revision) {}
    private record CapturedSetting(@NotNull UUID id, @NotNull PlayerSettingKey key, @NotNull Object value, @Nullable Integer expectedVersion) {}
    private record CapturedSnapshot(@NotNull UUID userId, long revision, @NotNull Map<PlayerSettingKey, CapturedSetting> settings) {}
    private record AcknowledgedSetting(@NotNull UUID id, int version) {}
}

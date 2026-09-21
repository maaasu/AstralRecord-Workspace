package io.github.maaasu.astralRecord.feature.history.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.history.model.ActivityPlayerSnapshot;
import io.github.maaasu.astralRecord.feature.history.repository.PlayerActivityHistoryRepository;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理画面専用の活動履歴を、ゲームプレイを待機させずに API へまとめて送ります。
 *
 * <p>履歴 API が利用できない場合はキューを上限まで再送し、上限を超えた低優先の履歴は捨てます。
 * このサービスの失敗はゲーム進行やトレード確定の成否に影響させません。</p>
 */
public final class PlayerActivityHistoryService {
    private static final int FLUSH_THRESHOLD = 100;
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_QUEUED_EVENTS = 10_000;
    private static final int MAX_MOB_DAMAGE_WINDOWS = 10_000;
    private static final long FLUSH_PERIOD_TICKS = 20L * 60L;
    private static final double MAX_MOVEMENT_SEGMENT_METERS = 32.0D;

    private final Plugin plugin;
    private final PlayerActivityHistoryRepository repository;
    private final ConcurrentLinkedDeque<ActivityEvent> queued = new ConcurrentLinkedDeque<>();
    private final AtomicInteger queuedCount = new AtomicInteger();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final Object damageLock = new Object();
    private final Map<MobDamageWindowKey, MobDamageAccumulator> mobDamageWindows = new HashMap<>();
    private ActivityBatch retryBatch;
    private BukkitTask flushTask;

    public PlayerActivityHistoryService(@NotNull Plugin plugin) {
        this(plugin, new PlayerActivityHistoryRepository());
    }

    PlayerActivityHistoryService(@NotNull Plugin plugin, @NotNull PlayerActivityHistoryRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /** 定期バッチ送信を開始します。 */
    public void start() {
        if (flushTask != null) return;
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, this::flushDueEvents, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS
        );
    }

    /** 停止時に新規送信を止めます。送信待ち履歴はプレイデータと異なり永続化しません。 */
    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushDamageWindows(Instant.now(), true);
    }

    /** ログイン確定後に、そのアカウントと接続 IP の対応を記録します。 */
    public void recordIpObservation(@NotNull ActivityPlayerSnapshot player, @NotNull String globalIp, @NotNull Instant observedAt) {
        if (!globalIp.isBlank()) enqueue(new IpObservationEvent(UUID.randomUUID(), observedAt, globalIp, player));
    }

    /** 一方向トレードの API 確定後に送信元・送信先・明細を記録します。 */
    public void recordTrade(
        @NotNull UUID eventId,
        @NotNull Instant completedAt,
        @NotNull ActivityPlayerSnapshot source,
        @NotNull ActivityPlayerSnapshot destination,
        @NotNull List<TradeItem> items,
        long gold
    ) {
        enqueue(new TradeEvent(eventId, completedAt, source, destination, List.copyOf(items), Math.max(0L, gold)));
    }

    /** ダンジョン踏破時に開始・終了・開始時参加者の移動概算を記録します。 */
    public void recordDungeonClear(@NotNull DungeonClearEvent event) {
        enqueue(event);
    }

    /** Mob がプレイヤーへ実際に HP ダメージを与えた結果を1分単位で集約します。 */
    public void recordMobDamage(
        @NotNull String mobId,
        @NotNull String mobName,
        @NotNull ActivityPlayerSnapshot victim,
        double damage,
        boolean lethal,
        @NotNull Instant occurredAt
    ) {
        double normalizedDamage = Math.max(0.0D, damage);
        if (normalizedDamage <= 0.0D) return;
        Instant windowStartedAt = occurredAt.truncatedTo(ChronoUnit.MINUTES);
        synchronized (damageLock) {
            MobDamageWindowKey key = new MobDamageWindowKey(mobId, victim.accountId(), windowStartedAt);
            MobDamageAccumulator accumulator = mobDamageWindows.get(key);
            if (accumulator == null) {
                // 履歴はゲームプレイの正本ではないため、異常な Mob 種別・被害者の組合せ増加時は新規窓を捨てる。
                if (mobDamageWindows.size() >= MAX_MOB_DAMAGE_WINDOWS) return;
                accumulator = new MobDamageAccumulator(mobName, victim);
                mobDamageWindows.put(key, accumulator);
            }
            accumulator.damage += normalizedDamage;
            accumulator.hitCount++;
        }
        if (lethal) enqueue(new MobPlayerDeathEvent(UUID.randomUUID(), occurredAt, mobId, mobName, victim));
    }

    /** 移動距離の不自然なワープ値を除外する際に共有する上限です。 */
    public static double maxMovementSegmentMeters() {
        return MAX_MOVEMENT_SEGMENT_METERS;
    }

    private void flushDueEvents() {
        flushDamageWindows(Instant.now(), false);
        flushQueuedEvents();
    }

    private void flushDamageWindows(@NotNull Instant now, boolean includeCurrentWindow) {
        Instant currentWindow = now.truncatedTo(ChronoUnit.MINUTES);
        List<Map.Entry<MobDamageWindowKey, MobDamageAccumulator>> ready = new ArrayList<>();
        synchronized (damageLock) {
            for (var entry : mobDamageWindows.entrySet()) {
                if (includeCurrentWindow || entry.getKey().windowStartedAt().isBefore(currentWindow)) ready.add(entry);
            }
            ready.forEach(entry -> mobDamageWindows.remove(entry.getKey()));
        }
        for (var entry : ready) {
            MobDamageWindowKey key = entry.getKey();
            MobDamageAccumulator value = entry.getValue();
            enqueue(new MobDamageSummaryEvent(
                UUID.randomUUID(), key.mobId(), value.mobName, key.windowStartedAt(),
                key.windowStartedAt().plus(1L, ChronoUnit.MINUTES), value.victim, value.damage, value.hitCount
            ));
        }
    }

    private void enqueue(@NotNull ActivityEvent event) {
        while (true) {
            int current = queuedCount.get();
            if (current >= MAX_QUEUED_EVENTS || !queuedCount.compareAndSet(current, current + 1)) {
                if (current >= MAX_QUEUED_EVENTS) return;
                continue;
            }
            queued.addLast(event);
            break;
        }
        if (queuedCount.get() >= FLUSH_THRESHOLD) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::flushQueuedEvents);
        }
    }

    private void flushQueuedEvents() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            ActivityBatch batch = retryBatch;
            if (batch == null) {
                List<ActivityEvent> events = new ArrayList<>(MAX_BATCH_SIZE);
                while (events.size() < MAX_BATCH_SIZE) {
                    ActivityEvent event = queued.pollFirst();
                    if (event == null) break;
                    queuedCount.decrementAndGet();
                    events.add(event);
                }
                if (events.isEmpty()) return;
                batch = new ActivityBatch(UUID.randomUUID(), List.copyOf(events));
            }
            try {
                repository.submit(batch);
                retryBatch = null;
            } catch (PlayerActivityHistoryRepository.HistorySubmissionException failure) {
                // 入力・認証などの4xxは同じ内容では成功しないため、当該バッチだけを捨てて次を処理する。
                if (failure.retryable()) {
                    retryBatch = batch;
                    return;
                }
                retryBatch = null;
            } catch (RuntimeException failure) {
                // DB保存後に応答だけ失われても API 側の batchId 冪等性で同じ結果を再取得できる。
                retryBatch = batch;
                return;
            }
        } finally {
            flushing.set(false);
        }
        if (queuedCount.get() >= FLUSH_THRESHOLD) flushQueuedEvents();
    }

    /** API の活動履歴バッチと同じ JSON 契約です。 */
    public record ActivityBatch(@NotNull UUID batchId, @NotNull List<ActivityEvent> events) {
        public @NotNull JsonObject toJson() {
            JsonObject body = new JsonObject();
            body.addProperty("batchId", batchId.toString());
            JsonArray ips = new JsonArray();
            JsonArray trades = new JsonArray();
            JsonArray clears = new JsonArray();
            JsonArray damage = new JsonArray();
            JsonArray deaths = new JsonArray();
            for (ActivityEvent event : events) {
                if (event instanceof IpObservationEvent value) ips.add(value.toJson());
                else if (event instanceof TradeEvent value) trades.add(value.toJson());
                else if (event instanceof DungeonClearEvent value) clears.add(value.toJson());
                else if (event instanceof MobDamageSummaryEvent value) damage.add(value.toJson());
                else if (event instanceof MobPlayerDeathEvent value) deaths.add(value.toJson());
            }
            body.add("ipObservations", ips);
            body.add("trades", trades);
            body.add("dungeonClears", clears);
            body.add("mobDamageSummaries", damage);
            body.add("mobPlayerDeaths", deaths);
            return body;
        }
    }

    public sealed interface ActivityEvent permits IpObservationEvent, TradeEvent, DungeonClearEvent,
        MobDamageSummaryEvent, MobPlayerDeathEvent { }

    public record TradeItem(@NotNull String itemId, @NotNull String itemName, long quantity) { }
    public record DungeonParticipant(@NotNull ActivityPlayerSnapshot player, Double distanceMeters, int movementSampleCount) { }

    public record IpObservationEvent(@NotNull UUID eventId, @NotNull Instant observedAt, @NotNull String globalIp,
                                     @NotNull ActivityPlayerSnapshot player) implements ActivityEvent {
        JsonObject toJson() { JsonObject json = event(eventId); json.addProperty("observedAt", observedAt.toString()); json.addProperty("globalIp", globalIp); json.add("player", PlayerActivityHistoryService.player(player)); return json; }
    }
    public record TradeEvent(@NotNull UUID eventId, @NotNull Instant completedAt, @NotNull ActivityPlayerSnapshot source,
                             @NotNull ActivityPlayerSnapshot destination, @NotNull List<TradeItem> items, long gold) implements ActivityEvent {
        JsonObject toJson() { JsonObject json = event(eventId); json.addProperty("completedAt", completedAt.toString()); json.add("source", player(source)); json.add("destination", player(destination)); JsonArray values = new JsonArray(); for (TradeItem item : items) { JsonObject value = new JsonObject(); value.addProperty("itemId", item.itemId()); value.addProperty("itemName", item.itemName()); value.addProperty("quantity", item.quantity()); values.add(value); } json.add("items", values); json.addProperty("gold", gold); return json; }
    }
    public record DungeonClearEvent(@NotNull UUID eventId, @NotNull String dungeonId, @NotNull String dungeonName,
                                    @NotNull Instant startedAt, @NotNull Instant clearedAt,
                                    @NotNull List<DungeonParticipant> participants) implements ActivityEvent {
        JsonObject toJson() { JsonObject json = event(eventId); json.addProperty("dungeonId", dungeonId); json.addProperty("dungeonName", dungeonName); json.addProperty("startedAt", startedAt.toString()); json.addProperty("clearedAt", clearedAt.toString()); JsonArray values = new JsonArray(); for (DungeonParticipant participant : participants) { JsonObject value = new JsonObject(); value.add("player", player(participant.player())); if (participant.distanceMeters() == null) value.add("distanceMeters", null); else value.addProperty("distanceMeters", participant.distanceMeters()); value.addProperty("movementSampleCount", participant.movementSampleCount()); values.add(value); } json.add("participants", values); return json; }
    }
    public record MobDamageSummaryEvent(@NotNull UUID eventId, @NotNull String mobId, @NotNull String mobName,
                                        @NotNull Instant windowStartedAt, @NotNull Instant windowEndedAt,
                                        @NotNull ActivityPlayerSnapshot victim, double damage, int hitCount) implements ActivityEvent {
        JsonObject toJson() { JsonObject json = event(eventId); json.addProperty("mobId", mobId); json.addProperty("mobName", mobName); json.addProperty("windowStartedAt", windowStartedAt.toString()); json.addProperty("windowEndedAt", windowEndedAt.toString()); json.add("victim", player(victim)); json.addProperty("damage", damage); json.addProperty("hitCount", hitCount); return json; }
    }
    public record MobPlayerDeathEvent(@NotNull UUID eventId, @NotNull Instant occurredAt, @NotNull String mobId,
                                      @NotNull String mobName, @NotNull ActivityPlayerSnapshot victim) implements ActivityEvent {
        JsonObject toJson() { JsonObject json = event(eventId); json.addProperty("occurredAt", occurredAt.toString()); json.addProperty("mobId", mobId); json.addProperty("mobName", mobName); json.add("victim", player(victim)); return json; }
    }

    private static JsonObject event(UUID eventId) { JsonObject json = new JsonObject(); json.addProperty("eventId", eventId.toString()); return json; }
    private static JsonObject player(ActivityPlayerSnapshot value) { JsonObject json = new JsonObject(); json.addProperty("userUuid", value.userUuid().toString()); json.addProperty("accountId", value.accountId().toString()); json.addProperty("mcid", value.mcid()); json.addProperty("accountName", value.accountName()); return json; }
    private record MobDamageWindowKey(String mobId, UUID victimAccountId, Instant windowStartedAt) { }
    private static final class MobDamageAccumulator { private final String mobName; private final ActivityPlayerSnapshot victim; private double damage; private int hitCount; private MobDamageAccumulator(String mobName, ActivityPlayerSnapshot victim) { this.mobName = mobName; this.victim = victim; } }
}

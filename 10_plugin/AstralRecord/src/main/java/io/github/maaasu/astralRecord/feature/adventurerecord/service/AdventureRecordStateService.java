package io.github.maaasu.astralRecord.feature.adventurerecord.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冒険記録の高頻度イベントをアカウント単位で集約し、完成スナップショットへ渡します。
 * API 応答を待たずローカルで加算し、同じ snapshot ID の冪等保存 ACK 後だけ捕捉済み差分を破棄します。
 */
public final class AdventureRecordStateService {
    private static final String SECTION_NAME = "adventureRecords";

    private final InventoryService inventoryService;
    private final Map<UUID, AccountDeltas> deltasByAccount = new ConcurrentHashMap<>();

    public AdventureRecordStateService(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** Mob 討伐をメモリ上の未保存差分へ即時加算し、200ms 集約保存を予約します。 */
    public void recordMobDefeat(
        @NotNull UUID accountId,
        @NotNull String mobId,
        @NotNull MobCategory category
    ) {
        String normalizedMobId = requireIdentifier(mobId, "mobId");
        if (category != MobCategory.ENEMY && category != MobCategory.BOSS) {
            throw new IllegalArgumentException("Unsupported adventure-record mob category: " + category);
        }
        AccountDeltas state = deltasByAccount.computeIfAbsent(accountId, ignored -> new AccountDeltas());
        synchronized (state) {
            state.mobDefeats.merge(normalizedMobId,
                new MobDefeatDelta(normalizedMobId, category, 1L),
                (current, ignored) -> new MobDefeatDelta(
                    current.mobId(), current.category(), Math.addExact(current.delta(), 1L)));
            state.revision = Math.addExact(state.revision, 1L);
        }
        inventoryService.queueLocalPlayerSave(accountId);
    }

    /** Dungeon 踏破をメモリ上の未保存差分へ即時加算し、200ms 集約保存を予約します。 */
    public void recordDungeonClear(@NotNull UUID accountId, @NotNull String dungeonId) {
        String normalizedDungeonId = requireIdentifier(dungeonId, "dungeonId");
        AccountDeltas state = deltasByAccount.computeIfAbsent(accountId, ignored -> new AccountDeltas());
        synchronized (state) {
            state.dungeonClears.merge(normalizedDungeonId, 1L, Math::addExact);
            state.revision = Math.addExact(state.revision, 1L);
        }
        inventoryService.queueLocalPlayerSave(accountId);
    }

    /** 未保存イベント差分を不変 section として捕捉します。 */
    public @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        AccountDeltas state = deltasByAccount.get(accountId);
        if (state == null) return null;

        long capturedRevision;
        Map<String, MobDefeatDelta> capturedMobDefeats;
        Map<String, Long> capturedDungeonClears;
        synchronized (state) {
            if (state.mobDefeats.isEmpty() && state.dungeonClears.isEmpty()) return null;
            capturedRevision = state.revision;
            capturedMobDefeats = Map.copyOf(state.mobDefeats);
            capturedDungeonClears = Map.copyOf(state.dungeonClears);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("clientRevision", capturedRevision);
        JsonArray mobDefeats = new JsonArray();
        capturedMobDefeats.values().stream()
            .sorted(Comparator.comparing(MobDefeatDelta::mobId, String.CASE_INSENSITIVE_ORDER))
            .forEach(delta -> {
                JsonObject row = new JsonObject();
                row.addProperty("mobId", delta.mobId());
                row.addProperty("mobCategory", delta.category().name());
                row.addProperty("delta", delta.delta());
                mobDefeats.add(row);
            });
        payload.add("mobDefeatDeltas", mobDefeats);
        JsonArray dungeonClears = new JsonArray();
        capturedDungeonClears.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .forEach(entry -> {
                JsonObject row = new JsonObject();
                row.addProperty("dungeonId", entry.getKey());
                row.addProperty("delta", entry.getValue());
                dungeonClears.add(row);
            });
        payload.add("dungeonClearDeltas", dungeonClears);
        return new PlayerStateSection(SECTION_NAME, payload, acknowledgement -> acknowledge(
            accountId, state, capturedRevision, capturedMobDefeats, capturedDungeonClears, acknowledgement));
    }

    private void acknowledge(
        @NotNull UUID accountId,
        @NotNull AccountDeltas capturedState,
        long capturedRevision,
        @NotNull Map<String, MobDefeatDelta> capturedMobDefeats,
        @NotNull Map<String, Long> capturedDungeonClears,
        @NotNull JsonElement acknowledgement
    ) {
        if (!acknowledgement.isJsonObject()) return;
        JsonObject ack = acknowledgement.getAsJsonObject();
        if (!ack.has("clientRevision") || ack.get("clientRevision").getAsLong() != capturedRevision
            || !ack.has("mobDefeats") || !ack.get("mobDefeats").isJsonArray()
            || !ack.has("dungeonClears") || !ack.get("dungeonClears").isJsonArray()
            || !containsMobAcknowledgements(ack.getAsJsonArray("mobDefeats"), capturedMobDefeats)
            || !containsDungeonAcknowledgements(ack.getAsJsonArray("dungeonClears"), capturedDungeonClears)) {
            return;
        }

        synchronized (capturedState) {
            if (deltasByAccount.get(accountId) != capturedState
                || capturedRevision <= capturedState.acknowledgedRevision) return;
            subtractMobDefeats(capturedState.mobDefeats, capturedMobDefeats);
            subtractDungeonClears(capturedState.dungeonClears, capturedDungeonClears);
            capturedState.acknowledgedRevision = capturedRevision;
            if (capturedState.mobDefeats.isEmpty() && capturedState.dungeonClears.isEmpty()) {
                deltasByAccount.remove(accountId, capturedState);
            }
        }
    }

    private boolean containsMobAcknowledgements(
        @NotNull JsonArray rows,
        @NotNull Map<String, MobDefeatDelta> captured
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) return false;
            JsonObject row = element.getAsJsonObject();
            if (!row.has("mobId") || !row.has("defeatCount")) return false;
            String id = row.get("mobId").getAsString();
            if (counts.put(id.toLowerCase(java.util.Locale.ROOT), row.get("defeatCount").getAsLong()) != null) {
                return false;
            }
        }
        return captured.values().stream().allMatch(delta ->
            counts.getOrDefault(delta.mobId().toLowerCase(java.util.Locale.ROOT), 0L) >= delta.delta());
    }

    private boolean containsDungeonAcknowledgements(
        @NotNull JsonArray rows,
        @NotNull Map<String, Long> captured
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) return false;
            JsonObject row = element.getAsJsonObject();
            if (!row.has("dungeonId") || !row.has("clearCount")) return false;
            String id = row.get("dungeonId").getAsString();
            if (counts.put(id.toLowerCase(java.util.Locale.ROOT), row.get("clearCount").getAsLong()) != null) {
                return false;
            }
        }
        return captured.entrySet().stream().allMatch(entry ->
            counts.getOrDefault(entry.getKey().toLowerCase(java.util.Locale.ROOT), 0L) >= entry.getValue());
    }

    private void subtractMobDefeats(
        @NotNull Map<String, MobDefeatDelta> current,
        @NotNull Map<String, MobDefeatDelta> captured
    ) {
        captured.forEach((id, saved) -> current.computeIfPresent(id, (ignored, value) -> {
            long remaining = value.delta() - saved.delta();
            return remaining > 0L ? new MobDefeatDelta(value.mobId(), value.category(), remaining) : null;
        }));
    }

    private void subtractDungeonClears(
        @NotNull Map<String, Long> current,
        @NotNull Map<String, Long> captured
    ) {
        captured.forEach((id, saved) -> current.computeIfPresent(id, (ignored, value) -> {
            long remaining = value - saved;
            return remaining > 0L ? remaining : null;
        }));
    }

    private static @NotNull String requireIdentifier(@NotNull String value, @NotNull String name) {
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static final class AccountDeltas {
        private final Map<String, MobDefeatDelta> mobDefeats = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, Long> dungeonClears = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private long revision;
        private long acknowledgedRevision = -1L;
    }

    private record MobDefeatDelta(@NotNull String mobId, @NotNull MobCategory category, long delta) {
    }
}

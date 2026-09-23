package io.github.maaasu.astralRecord.feature.skill.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * スキルバインドプリセットの取得と保存を扱います。
 */
public final class SkillBindPresetService {
    private static final int PRESET_COUNT = SkillBindPreset.PRESET_COUNT;
    private static final int DEFAULT_UNLOCKED_PRESET_COUNT = 3;

    private final Plugin plugin;
    private final SkillBindPresetRepository repository;
    private final Map<UUID, Integer> selectedPresetIndexes = new ConcurrentHashMap<>();
    private final Map<UUID, List<SkillBindPreset>> presetsByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, AccountSessionState> sessionStates = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Integer>> persistedPresetVersions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> releasedPlayerStates = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> retainedInitialLoads = ConcurrentHashMap.newKeySet();
    private @Nullable InventoryService localStatePersistence;

    public SkillBindPresetService(@NotNull Plugin plugin, @NotNull SkillBindPresetRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /**
     * プリセット変更を account のローカル状態保存へ接続します。
     *
     * @param localStatePersistence state lock と保存キューを提供するインベントリサービス
     */
    public void setLocalStatePersistence(@NotNull InventoryService localStatePersistence) {
        this.localStatePersistence = localStatePersistence;
    }

    /**
     * アカウントのプリセット一覧を取得し、所持する解放トークンを表示上の解放状態へ反映します。
     * トークン由来の解放状態はAPI保存用キャッシュへ書き戻しません。
     *
     * @param accountId アカウント ID
     * @return 1 から {@link SkillBindPreset#PRESET_COUNT} までの実効解放状態を含むプリセット一覧
     */
    public @NotNull List<SkillBindPreset> getPresets(@NotNull UUID accountId) {
        List<SkillBindPreset> presets = rawPresets(accountId);
        long remainingUnlocks = Math.min(
            PRESET_COUNT - DEFAULT_UNLOCKED_PRESET_COUNT,
            ownedCurrencyAmount(accountId, ItemService.SKILL_PRESET_UNLOCK_TOKEN_ITEM_ID)
        );
        if (remainingUnlocks <= 0L) {
            return presets;
        }
        List<SkillBindPreset> unlocked = new ArrayList<>(presets.size());
        for (SkillBindPreset preset : presets) {
            if (!preset.isUnlocked() && remainingUnlocks > 0L) {
                unlocked.add(preset.withUnlocked(true));
                remainingUnlocks--;
            } else {
                unlocked.add(preset);
            }
        }
        return unlocked;
    }

    /**
     * 所持するアクティブ枠解放トークンを、初期9枠へ加算して上限適用した枠数を返します。
     *
     * @param accountId 対象アカウントID
     * @return 現在利用できる発動スキル枠数
     */
    public int activeSkillSlotCount(@NotNull UUID accountId) {
        long unlockTokens = ownedCurrencyAmount(accountId, ItemService.ACTIVE_SKILL_SLOT_UNLOCK_TOKEN_ITEM_ID);
        return (int) Math.min(
            SkillBindPreset.ACTIVE_SLOT_COUNT,
            SkillBindPreset.DEFAULT_ACTIVE_SLOT_COUNT + unlockTokens
        );
    }

    /**
     * 基礎・ステータス由来のパッシブ枠数へ所持トークンを加え、最大12枠へ制限します。
     *
     * @param accountId 対象アカウントID
     * @param baseSlotCount トークン加算前に利用可能なパッシブ枠数
     * @return 現在利用できるパッシブ枠数
     */
    public int passiveSkillSlotCount(@NotNull UUID accountId, int baseSlotCount) {
        long unlockTokens = ownedCurrencyAmount(accountId, ItemService.PASSIVE_SKILL_SLOT_UNLOCK_TOKEN_ITEM_ID);
        int normalizedBase = Math.max(0, Math.min(SkillBindPreset.PASSIVE_SLOT_COUNT, baseSlotCount));
        return (int) Math.min(SkillBindPreset.PASSIVE_SLOT_COUNT, normalizedBase + unlockTokens);
    }

    private @NotNull List<SkillBindPreset> rawPresets(@NotNull UUID accountId) {
        List<SkillBindPreset> cached = presetsByAccount.get(accountId);
        return cached == null ? fallbackPresets(accountId) : new ArrayList<>(cached);
    }

    private long ownedCurrencyAmount(@NotNull UUID accountId, @NotNull String itemId) {
        InventoryService inventoryService = localStatePersistence;
        return inventoryService == null ? 0L : inventoryService.getCurrencyAmount(accountId, itemId);
    }

    public boolean hasLoadedPresets(@NotNull UUID accountId) {
        return presetsByAccount.containsKey(accountId);
    }

    /**
     * セッションを失効させ、未保存プリセットは完全ACKまで保持します。
     *
     * @param accountId アカウント ID
     */
    public synchronized void invalidate(@NotNull UUID accountId) {
        AccountSessionState state = sessionStates.computeIfAbsent(accountId, ignored -> new AccountSessionState());
        synchronized (state) {
            state.generation++;
        }
        retainedInitialLoads.remove(accountId);
        releasedPlayerStates.add(accountId);
        if (!state.dirty) evictReleasedPlayerState(accountId);
    }

    /** 保存不能の復旧時に、指定 account の未保存スキルプリセットを保存せず破棄します。 */
    public synchronized void discardAccountState(@NotNull UUID accountId) {
        selectedPresetIndexes.remove(accountId);
        presetsByAccount.remove(accountId);
        sessionStates.remove(accountId);
        persistedPresetVersions.remove(accountId);
        releasedPlayerStates.remove(accountId);
        retainedInitialLoads.remove(accountId);
    }

    /** 保存済みかつ退出済みの状態を破棄します。呼出元は本serviceのmonitorを保持します。 */
    private void evictReleasedPlayerState(UUID accountId) {
        if (!releasedPlayerStates.remove(accountId)) return;
        presetsByAccount.remove(accountId);
        selectedPresetIndexes.remove(accountId);
        persistedPresetVersions.remove(accountId);
        sessionStates.remove(accountId);
    }

    /**
     * 未保存プリセットを優先して読み込み、applyまで保持状態の破棄を抑止します。
     * @param accountId 対象アカウント
     * @return 保持状態、またはAPI初期状態
     */
    public @NotNull List<SkillBindPreset> loadInitialPresets(@NotNull UUID accountId) {
        synchronized (this) {
            AccountSessionState state = sessionStates.get(accountId);
            if (state != null && (state.dirty || retainedInitialLoads.contains(accountId))) {
                retainedInitialLoads.add(accountId);
                releasedPlayerStates.remove(accountId);
                return getPresets(accountId);
            }
        }
        try {
            List<SkillBindPreset> presets = new ArrayList<>(repository.findByAccountId(accountId));
            return normalizePresets(accountId, presets);
        } catch (Exception exception) {
            Logger.log(LogId.E_5803, exception, "skill_bind_load:" + accountId);
        }
        return fallbackPresets(accountId);
    }

    /**
     * 保持中のローカル変更を優先し、それ以外は初期プリセットと選択中番号を公開します。
     *
     * @param accountId アカウント ID
     * @param presets APIから取得したプリセット一覧
     */
    public synchronized void applyInitialPresets(
        @NotNull UUID accountId,
        @NotNull List<SkillBindPreset> presets
    ) {
        releasedPlayerStates.remove(accountId);
        AccountSessionState retained = sessionStates.get(accountId);
        if (retainedInitialLoads.remove(accountId) || (retained != null && retained.dirty)) return;
        List<SkillBindPreset> normalized = normalizePresets(accountId, presets);
        presetsByAccount.put(accountId, normalized);
        selectedPresetIndexes.put(
            accountId,
            normalized.stream()
                .filter(SkillBindPreset::isSelected)
                .mapToInt(SkillBindPreset::getPresetIndex)
                .findFirst()
                .orElse(1)
        );
        AccountSessionState state = new AccountSessionState();
        sessionStates.put(accountId, state);
        synchronized (state) {
            state.dirty = false;
            state.revision = 0L;
        }
        Map<Integer, Integer> versions = new ConcurrentHashMap<>();
        normalized.forEach(preset -> versions.put(preset.getPresetIndex(), preset.getVersion()));
        persistedPresetVersions.put(accountId, versions);
    }

    private @NotNull List<SkillBindPreset> fallbackPresets(@NotNull UUID accountId) {
        List<SkillBindPreset> fallback = new ArrayList<>(PRESET_COUNT);
        for (int index = 1; index <= PRESET_COUNT; index++) {
            fallback.add(new SkillBindPreset(
                null,
                accountId,
                index,
                List.of(),
                SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID,
                List.of(),
                index <= 3,
                false,
                0
            ));
        }
        return fallback;
    }

    /**
     * 現在選択中として扱うプリセット番号を返します。
     *
     * @param accountId アカウント ID
     * @return 選択中プリセット番号
     */
    public int selectedPresetIndex(@NotNull UUID accountId) {
        return selectedPresetIndexes.getOrDefault(accountId, 1);
    }

    /**
     * 現在選択中として扱うプリセット番号を更新します。
     *
     * @param accountId アカウント ID
     * @param presetIndex プリセット番号
     * @apiNote メモリ上の選択状態は即時更新し、永続化は非同期で行います。
     */
    public void selectPreset(@NotNull UUID accountId, int presetIndex) {
        int normalizedPresetIndex = Math.max(1, Math.min(PRESET_COUNT, presetIndex));
        mutateLocal(accountId, () -> selectedPresetIndexes.put(accountId, normalizedPresetIndex));
    }

    /**
     * 指定プリセットを保存します。
     *
     * @param accountId アカウント ID
     * @param presetIndex プリセット番号
     * @param activeSkillSlots 発動系スロット
     * @param leftClickSkillId 左クリックバインド
     * @param passiveSkillSlots パッシブ系スロット
     * @param updatedBy 更新者
     * @return 保存後プリセット
     */
    public boolean saveAsync(
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        String leftClickSkillId,
        @NotNull List<String> passiveSkillSlots,
        @NotNull UUID updatedBy,
        @NotNull Consumer<SkillBindPreset> onSuccess,
        @NotNull Runnable onFailure
    ) {
        int normalizedPresetIndex = Math.max(1, Math.min(PRESET_COUNT, presetIndex));
        List<String> activeSnapshot = Collections.unmodifiableList(new ArrayList<>(activeSkillSlots));
        String leftClickSnapshot = leftClickSkillId == null || leftClickSkillId.isBlank() ? null : leftClickSkillId.trim();
        List<String> passiveSnapshot = Collections.unmodifiableList(new ArrayList<>(passiveSkillSlots));
        SkillBindPreset[] saved = new SkillBindPreset[1];
        try {
            mutateLocal(accountId, () -> {
                SkillBindPreset existing = rawPresets(accountId).get(normalizedPresetIndex - 1);
                SkillBindPreset local = new SkillBindPreset(existing.getPresetId(), accountId,
                    normalizedPresetIndex, activeSnapshot, leftClickSnapshot, passiveSnapshot,
                    existing.isUnlocked(), false, existing.getVersion() + 1,
                    normalizedPresetIndex == selectedPresetIndex(accountId));
                presetsByAccount.compute(accountId, (ignored, current) -> mergePreset(accountId, current, local));
                saved[0] = local;
            });
            onSuccess.accept(saved[0]);
            return true;
        } catch (RuntimeException exception) {
            onFailure.run();
            return false;
        }
    }

    /**
     * 左クリックバインド未導入の呼び出し元向けに、武器通常攻撃を既定値として保存します。
     */
    public boolean saveAsync(
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        @NotNull List<String> passiveSkillSlots,
        @NotNull UUID updatedBy,
        @NotNull Consumer<SkillBindPreset> onSuccess,
        @NotNull Runnable onFailure
    ) {
        return saveAsync(
            accountId, presetIndex, activeSkillSlots, SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID,
            passiveSkillSlots, updatedBy, onSuccess, onFailure
        );
    }

    /**
     * 現在のローカル確定済みプリセットを player-state section として取得します。
     * 呼出元は account の state lock を保持している必要があります。
     *
     * @param accountId 対象アカウントID
     * @return dirty でない場合は {@code null}
     */
    public synchronized @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        AccountSessionState state = sessionStates.get(accountId);
        if (state == null) {
            return null;
        }
        final long capturedRevision;
        synchronized (state) {
            if (!state.dirty) {
                return null;
            }
            capturedRevision = state.revision;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("clientRevision", capturedRevision);
        payload.addProperty("selectedPresetIndex", selectedPresetIndex(accountId));
        JsonArray presets = new JsonArray();
        java.util.Set<Integer> capturedPresetIndexes = new java.util.LinkedHashSet<>();
        Map<Integer, Integer> capturedVersions = new java.util.LinkedHashMap<>();
        for (SkillBindPreset preset : rawPresets(accountId)) {
            capturedPresetIndexes.add(preset.getPresetIndex());
            capturedVersions.put(preset.getPresetIndex(), preset.getVersion());
            JsonObject value = new JsonObject();
            value.addProperty("presetIndex", preset.getPresetIndex());
            value.add("activeSkillSlots", slotArray(preset.getActiveSkillSlots()));
            if (preset.getLeftClickSkillId() == null) {
                value.add("leftClickSkillId", com.google.gson.JsonNull.INSTANCE);
            } else {
                value.addProperty("leftClickSkillId", preset.getLeftClickSkillId());
            }
            value.add("passiveSkillSlots", slotArray(preset.getPassiveSkillSlots()));
            Integer expectedVersion = persistedPresetVersions
                .getOrDefault(accountId, Map.of()).get(preset.getPresetIndex());
            // version=0 はAPI未保存行のローカル表現なので、新規行として送信します。
            value.add("expectedVersion", expectedVersion == null || expectedVersion < 1
                ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(expectedVersion));
            value.addProperty("targetVersion", Math.max(1, preset.getVersion()));
            presets.add(value);
        }
        payload.add("presets", presets);
        return new PlayerStateSection("skillBindPresets", payload,
            acknowledged -> acknowledgeSnapshot(accountId, state, capturedRevision,
                capturedVersions, capturedPresetIndexes, acknowledged));
    }

    private void mutateLocal(@NotNull UUID accountId, @NotNull Runnable mutation) {
        InventoryService persistence = localStatePersistence;
        if (persistence == null) {
            throw new IllegalStateException("Local state persistence is not configured.");
        }
        persistence.executeLocalPlayerMutation(accountId, () -> {
            synchronized (this) {
                mutation.run();
                AccountSessionState state = sessionStates.computeIfAbsent(accountId, ignored -> new AccountSessionState());
                synchronized (state) {
                    state.dirty = true;
                    state.revision++;
                }
                return null;
            }
        });
        persistence.queueLocalPlayerSave(accountId);
    }

    private synchronized void acknowledgeSnapshot(@NotNull UUID accountId, AccountSessionState capturedState,
        long capturedRevision, Map<Integer, Integer> capturedVersions,
        @NotNull java.util.Set<Integer> capturedPresetIndexes, @NotNull JsonElement acknowledged) {
        if (sessionStates.get(accountId) != capturedState
            || capturedRevision <= capturedState.acknowledgedRevision) return;
        if (!acknowledged.isJsonObject()) {
            throw new IllegalStateException("skillBindPresets acknowledgement must be an object");
        }
        try {
            JsonObject metadata = acknowledged.getAsJsonObject();
            if (!metadata.has("clientRevision") || metadata.get("clientRevision").getAsLong() != capturedRevision
                || !metadata.has("entries") || !metadata.get("entries").isJsonArray()) {
                throw new IllegalStateException("skillBindPresets acknowledgement metadata is incomplete");
            }
            Map<Integer, Integer> received = new java.util.LinkedHashMap<>();
            for (JsonElement entry : metadata.getAsJsonArray("entries")) {
                JsonObject value = entry.getAsJsonObject();
                int index = value.get("presetIndex").getAsInt();
                if (!value.has("version") || value.get("version").isJsonNull()
                    || received.put(index, value.get("version").getAsInt()) != null) {
                    throw new IllegalStateException("skillBindPresets acknowledgement entry is invalid");
                }
            }
            if (!received.keySet().equals(capturedPresetIndexes)) {
                throw new IllegalStateException("skillBindPresets acknowledgement IDs do not match the snapshot");
            }
            persistedPresetVersions.computeIfAbsent(accountId, ignored -> new ConcurrentHashMap<>()).putAll(received);
            List<SkillBindPreset> current = rawPresets(accountId);
            presetsByAccount.put(accountId, current.stream().map(preset -> new SkillBindPreset(
                preset.getPresetId(), accountId, preset.getPresetIndex(), preset.getActiveSkillSlots(),
                preset.getLeftClickSkillId(), preset.getPassiveSkillSlots(), preset.isUnlocked(), preset.isSaved(),
                received.get(preset.getPresetIndex()) + preset.getVersion() - capturedVersions.get(preset.getPresetIndex()),
                preset.isSelected())).toList());
        } catch (RuntimeException malformedAck) {
            throw new IllegalStateException("Invalid skillBindPresets acknowledgement", malformedAck);
        }
        AccountSessionState state = sessionStates.get(accountId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.acknowledgedRevision = capturedRevision;
            if (state.revision == capturedRevision) {
                state.dirty = false;
                evictReleasedPlayerState(accountId);
            }
        }
    }

    private @NotNull JsonArray slotArray(@NotNull List<String> slots) {
        JsonArray values = new JsonArray();
        for (String slot : slots) {
            if (slot == null || slot.isBlank()) {
                values.add(com.google.gson.JsonNull.INSTANCE);
            } else {
                values.add(slot);
            }
        }
        return values;
    }

    private @NotNull List<SkillBindPreset> normalizePresets(
        @NotNull UUID accountId,
        @NotNull List<SkillBindPreset> presets
    ) {
        List<SkillBindPreset> normalized = new ArrayList<>(presets.subList(0, Math.min(PRESET_COUNT, presets.size())));
        List<SkillBindPreset> fallback = fallbackPresets(accountId);
        while (normalized.size() < PRESET_COUNT) {
            normalized.add(fallback.get(normalized.size()));
        }
        return List.copyOf(normalized);
    }

    private @NotNull List<SkillBindPreset> mergePreset(
        @NotNull UUID accountId,
        List<SkillBindPreset> current,
        @NotNull SkillBindPreset saved
    ) {
        List<SkillBindPreset> merged = current == null
            ? fallbackPresets(accountId)
            : new ArrayList<>(current);
        int index = Math.max(1, Math.min(PRESET_COUNT, saved.getPresetIndex())) - 1;
        while (merged.size() < PRESET_COUNT) {
            merged.add(fallbackPresets(accountId).get(merged.size()));
        }
        merged.set(index, saved);
        return List.copyOf(merged.subList(0, PRESET_COUNT));
    }

    private static final class AccountSessionState {
        private long acknowledgedRevision = -1L;
        private long generation;
        private long revision;
        private boolean dirty;
    }
}

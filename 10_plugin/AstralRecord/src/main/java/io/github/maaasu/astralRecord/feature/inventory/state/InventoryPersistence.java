package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryDraft;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * インベントリ状態と API 永続層の橋渡しを行うクラスです。
 * <p>
 * <ul>
 *   <li>{@link #load(UUID)}: アカウントロード時に API からインベントリ・entry・ロードアウトを取得し、
 *       {@link PlayerInventoryState} を構築します。</li>
 *   <li>{@link #save(PlayerInventoryState, SaveTrigger)}: dirty な state を API へ反映します。
 *       オートセーブ / ログアウト時に呼び出されます。</li>
 *   <li>{@link #saveNow(PlayerInventoryState)}: マーケット成立など、即時整合性が必要なケース向けの内部処理。
 *       {@link io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator} の保存キューから
 *       同期的に呼び出され、結果が確定するまでキューの後続処理へ進みません。</li>
 * </ul>
 *
 * 永続化失敗時はログを warn で残し、{@link PlayerInventoryState#restoreDirty()} で次回再試行できる状態に戻します。
 */
public final class InventoryPersistence {

    private final InventoryRepository inventoryRepository;
    private final EquipmentLoadoutRepository equipmentLoadoutRepository;
    private final ItemService itemService;
    /** アカウントID → 直前に保存済みの装備ロードアウトスロット (キー: SlotKey, 値: 装備インスタンスID)。 */
    private final Map<UUID, Map<SlotKey, UUID>> lastPersistedLoadoutSlots = new ConcurrentHashMap<>();

    /**
     * 永続層との同期処理を構築します。
     *
     * @param inventoryRepository インベントリ repository
     * @param equipmentLoadoutRepository 装備ロードアウト repository
     * @param itemService 装備耐久値の dirty flush に使うアイテムサービス
     */
    public InventoryPersistence(
        @NotNull InventoryRepository inventoryRepository,
        @NotNull EquipmentLoadoutRepository equipmentLoadoutRepository,
        @NotNull ItemService itemService
    ) {
        this.inventoryRepository = inventoryRepository;
        this.equipmentLoadoutRepository = equipmentLoadoutRepository;
        this.itemService = itemService;
    }

    // ---------------------------------------------------------------
    // load
    // ---------------------------------------------------------------

    /**
     * アカウントの全インベントリ・entry・装備ロードアウトを API から取得し、
     * {@link PlayerInventoryState} を構築します。
     * <p>
     * 本メソッドは HTTP 通信を伴うため、Bukkit メインスレッド外で呼び出してください。
     *
     * @param accountId 対象アカウントID
     * @return 構築済み state
     */
    public @NotNull PlayerInventoryState load(@NotNull UUID accountId) {
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        try {
            // 過去に itemId を併記せず保存された装備 entry を、マーケット照合前に API 正本で補正する。
            inventoryRepository.repairEquipmentEntryItemIds(accountId);
            List<InventoryModel> inventories = inventoryRepository.findByAccountId(accountId);
            for (InventoryModel inventory : inventories) {
                state.putInventory(inventory);
                List<InventoryEntryModel> entries = inventoryRepository.findEntries(inventory.getInventoryId());
                state.replaceEntriesFromLoad(inventory.getInventoryId(), entries);
            }
            List<EquipmentLoadoutModel> loadouts = equipmentLoadoutRepository
                .findByAccountId(accountId, InventoryProfile.GAME);
            for (EquipmentLoadoutModel loadout : loadouts) {
                state.putLoadout(loadout);
            }
            Set<String> equipmentInstanceIds = new HashSet<>();
            for (InventoryModel inventory : state.snapshotInventories()) {
                state.snapshotEntries(inventory.getInventoryId()).stream()
                    .filter(entry -> !entry.isDeleted())
                    .filter(entry -> entry.getInstanceId() != null)
                    .filter(entry -> "EQUIPMENT".equalsIgnoreCase(entry.getItemCategory()))
                    .map(entry -> entry.getInstanceId().toString())
                    .forEach(equipmentInstanceIds::add);
            }
            loadouts.stream()
                .flatMap(loadout -> loadout.getSlots().stream())
                .filter(slot -> !slot.isDeleted())
                .map(slot -> slot.getEquipmentInstanceId().toString())
                .forEach(equipmentInstanceIds::add);
            // APIから取得したloadoutをcleanup前に保存し、owner不一致slotを次回saveのdelete diffへ残す。
            Map<SlotKey, UUID> persistedLoadoutSlots = snapshotLoadoutSlots(state);
            ItemService.EquipmentPreloadResult preloadResult =
                itemService.preloadEquipmentInstances(equipmentInstanceIds);
            if (preloadResult == ItemService.EquipmentPreloadResult.UNAVAILABLE) {
                Logger.warn(LogId.W_5252, accountId, preloadResult);
            }
            // owner不一致はpartial preload後に別IDが通信失敗しても確定情報として除去する。
            // nullは全preloadが完了した場合だけ404確定として扱い、UNAVAILABLEでは保持する。
            for (String instanceId : equipmentInstanceIds) {
                var loaded = itemService.findLoadedEquipmentInstanceById(instanceId);
                boolean unavailableOwner = loaded != null
                    && !loaded.getAccountId().equalsIgnoreCase(accountId.toString());
                boolean confirmedMissing = loaded == null
                    && preloadResult != ItemService.EquipmentPreloadResult.UNAVAILABLE;
                if (unavailableOwner || confirmedMissing) {
                    state.discardUnavailableEquipmentInstance(UUID.fromString(instanceId));
                    itemService.evictEquipmentInstanceFromCache(instanceId);
                }
            }
            lastPersistedLoadoutSlots.put(accountId, persistedLoadoutSlots);
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5252, accountId, e.getMessage());
        }
        return state;
    }

    // ---------------------------------------------------------------
    // save
    // ---------------------------------------------------------------

    /**
     * dirty な state を API へ反映します。dirty でなければ何も行いません。
     * 通信失敗時は dirty フラグを戻し、次回オートセーブで再試行できる状態に保ちます。
     *
     * @param state 対象 state
     * @param trigger 保存契機
     * @return 実際に save 処理を走らせた場合 true
     */
    public boolean save(@NotNull PlayerInventoryState state, @NotNull SaveTrigger trigger) {
        return save(state, trigger, null);
    }

    private boolean save(
        @NotNull PlayerInventoryState state,
        @NotNull SaveTrigger trigger,
        @Nullable Map<UUID, List<InventoryEntryModel>> persistedEntries
    ) {
        UUID accountId = state.getAccountId();
        boolean inventoryDirty = state.takeAndClearDirty();
        boolean durabilityDirty = itemService.hasDirtyEquipmentDurability(accountId);
        if (!inventoryDirty && !durabilityDirty) {
            return false;
        }
        boolean allOk = true;
        try {
            if (inventoryDirty) {
                for (InventoryModel inventory : state.snapshotDirtyMetadataInventories()) {
                    if (!inventory.isEnabled() || inventory.isDeleted()) {
                        continue;
                    }
                    try {
                        InventoryModel updated = inventoryRepository.updateMetadata(
                            inventory.getInventoryId(),
                            inventory.getMetadataJson(),
                            accountId
                        );
                        state.putInventory(updated);
                        state.clearMetadataDirty(inventory.getInventoryId());
                    } catch (RuntimeException e) {
                        Logger.warn(LogId.W_5252, inventory.getInventoryId(), e.getMessage());
                        allOk = false;
                    }
                }

                for (InventoryModel inventory : state.snapshotInventories()) {
                    if (!inventory.isEnabled() || inventory.isDeleted()) {
                        continue;
                    }
                    List<InventoryEntryModel> entries = state.snapshotEntries(inventory.getInventoryId());
                    List<InventoryEntryDraft> drafts = entries.stream()
                        .filter(e -> !e.isDeleted())
                        .map(InventoryPersistence::toDraft)
                        .toList();
                    InventoryModel targetInventory = inventory;
                    try {
                        List<InventoryEntryModel> persisted = inventoryRepository.replaceEntries(
                            targetInventory.getInventoryId(),
                            drafts,
                            accountId
                        );
                        state.acknowledgePersistedEntries(targetInventory.getInventoryId(), entries, persisted);
                        capturePersistedEntries(persistedEntries, targetInventory.getInventoryId(), persisted);
                    } catch (InventoryApiException e) {
                        if (e.getStatusCode() == 409) {
                            try {
                                List<InventoryEntryModel> authoritative = inventoryRepository.findEntries(
                                    targetInventory.getInventoryId()
                                );
                                if (state.replaceEntriesFromAuthoritativeSnapshotIfUnchanged(
                                    targetInventory.getInventoryId(),
                                    entries,
                                    authoritative
                                )) {
                                    capturePersistedEntries(
                                        persistedEntries,
                                        targetInventory.getInventoryId(),
                                        authoritative
                                    );
                                    continue;
                                }
                            } catch (RuntimeException recoveryFailure) {
                                logInventorySyncFailure(
                                    accountId,
                                    targetInventory.getInventoryId(),
                                    trigger,
                                    entries.size(),
                                    recoveryFailure
                                );
                                allOk = false;
                                continue;
                            }
                        }
                        if (e.getStatusCode() != 404) {
                            logInventorySyncFailure(accountId, targetInventory.getInventoryId(), trigger, entries.size(), e);
                            allOk = false;
                            continue;
                        }

                        InventoryModel replacement;
                        try {
                            replacement = recoverMissingInventory(state, targetInventory, accountId, trigger);
                        } catch (RuntimeException recoveryFailure) {
                            logInventorySyncFailure(
                                accountId,
                                targetInventory.getInventoryId(),
                                trigger,
                                entries.size(),
                                recoveryFailure
                            );
                            allOk = false;
                            continue;
                        }
                        if (replacement == null) {
                            logInventorySyncFailure(accountId, targetInventory.getInventoryId(), trigger, entries.size(), e);
                            allOk = false;
                            continue;
                        }

                        targetInventory = replacement;
                        entries = state.snapshotEntries(targetInventory.getInventoryId());
                        drafts = entries.stream()
                            .filter(entry -> !entry.isDeleted())
                            .map(InventoryPersistence::toDraft)
                            .toList();
                        try {
                            List<InventoryEntryModel> persisted = inventoryRepository.replaceEntries(
                                targetInventory.getInventoryId(),
                                drafts,
                                accountId
                            );
                            state.acknowledgePersistedEntries(targetInventory.getInventoryId(), entries, persisted);
                            capturePersistedEntries(persistedEntries, targetInventory.getInventoryId(), persisted);
                        } catch (RuntimeException retryFailure) {
                            logInventorySyncFailure(
                                accountId,
                                targetInventory.getInventoryId(),
                                trigger,
                                entries.size(),
                                retryFailure
                            );
                            allOk = false;
                        }
                    } catch (RuntimeException e) {
                        logInventorySyncFailure(accountId, targetInventory.getInventoryId(), trigger, entries.size(), e);
                        allOk = false;
                    }
                }

                try {
                    saveLoadoutSlotsDiff(state);
                } catch (RuntimeException e) {
                    Logger.warn(LogId.W_5253, accountId, e.getMessage());
                    allOk = false;
                }
            }

            if (durabilityDirty) {
                try {
                    if (!itemService.flushDirtyEquipmentDurability(accountId)) {
                        allOk = false;
                    }
                } catch (RuntimeException e) {
                    Logger.warn(LogId.W_5252, accountId, e.getMessage());
                    allOk = false;
                }
            }
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5252, accountId, e.getMessage());
            allOk = false;
        }

        if (!allOk && inventoryDirty) {
            state.restoreDirty();
        }
        return true;
    }

    /**
     * 次回保存へ持ち越されたインベントリまたは装備耐久度の変更があるかを返します。
     *
     * @param state 判定対象 state
     * @return 未保存変更が残っている場合は {@code true}
     */
    public boolean hasPendingChanges(@NotNull PlayerInventoryState state) {
        return state.isDirty() || itemService.hasDirtyEquipmentDurability(state.getAccountId());
    }

    /**
     * 保存コーディネーターから、即時整合性が必要な場面で同期的に保存します。
     * <p>
     * dirty フラグを強制的に立ててから {@link #save(PlayerInventoryState, SaveTrigger)} を呼ぶため、
     * 直前にゲームロジックが state を変更していない場合でも安全に呼び出せます。
     * 通信失敗時は warn ログを残し dirty を維持します。
     *
     * @param state 対象 state
     * @return 通信が成功して反映された場合 true
     */
    public boolean saveNow(@NotNull PlayerInventoryState state) {
        state.markDirty();
        save(state, SaveTrigger.IMMEDIATE);
        return !hasPendingChanges(state);
    }

    /**
     * 外部原子操作の直前状態を保存し、API が実際に永続化した entry を baseline として返します。
     * <p>
     * 保存中にローカル変更が入った場合、{@link PlayerInventoryState#acknowledgePersistedEntries(UUID, List, List)}
     * はその変更を保持して dirty を残します。この場合は不安定な snapshot を baseline にせず {@code null}
     * を返し、呼び出し側が同じ account lane 内で再保存します。
     *
     * @param state 対象 state
     * @return 全 inventory の保存済み entry。通信失敗または保存中変更が残る場合は {@code null}
     */
    public @Nullable PersistedInventoryBaseline saveNowWithBaseline(
        @NotNull PlayerInventoryState state
    ) {
        Map<UUID, List<InventoryEntryModel>> persistedEntries = new LinkedHashMap<>();
        state.markDirty();
        save(state, SaveTrigger.IMMEDIATE, persistedEntries);
        if (hasPendingChanges(state)) {
            return null;
        }
        return new PersistedInventoryBaseline(state.getAccountId(), persistedEntries);
    }

    private static void capturePersistedEntries(
        @Nullable Map<UUID, List<InventoryEntryModel>> target,
        @NotNull UUID inventoryId,
        @NotNull List<InventoryEntryModel> persisted
    ) {
        if (target != null) {
            target.put(inventoryId, List.copyOf(persisted));
        }
    }

    private void saveLoadoutSlotsDiff(@NotNull PlayerInventoryState state) {
        UUID accountId = state.getAccountId();
        EquipmentLoadoutModel active = state.findActiveLoadout(InventoryProfile.GAME);
        Map<SlotKey, UUID> current = new HashMap<>();
        if (active != null) {
            for (EquipmentLoadoutSlotModel slot : active.getSlots()) {
                if (slot.isDeleted()) {
                    continue;
                }
                current.put(new SlotKey(slot.getSlotType(), slot.getSlotIndex()), slot.getEquipmentInstanceId());
            }
        }

        Map<SlotKey, UUID> previous = lastPersistedLoadoutSlots.getOrDefault(accountId, Map.of());
        if (active == null) {
            // active ロードアウトが消えた → 旧スロットを削除のみ
            for (SlotKey key : previous.keySet()) {
                equipmentLoadoutRepository.deleteSlot(
                    inferLoadoutIdForDelete(state, accountId),
                    key.slotType(),
                    key.slotIndex(),
                    accountId
                );
            }
            lastPersistedLoadoutSlots.put(accountId, Map.of());
            return;
        }

        Set<SlotKey> toDelete = new HashSet<>(previous.keySet());
        toDelete.removeAll(current.keySet());
        for (SlotKey key : toDelete) {
            equipmentLoadoutRepository.deleteSlot(
                active.getEquipmentLoadoutId(),
                key.slotType(),
                key.slotIndex(),
                accountId
            );
        }
        for (Map.Entry<SlotKey, UUID> entry : current.entrySet()) {
            UUID previousInstance = previous.get(entry.getKey());
            if (previousInstance != null && previousInstance.equals(entry.getValue())) {
                continue;
            }
            equipmentLoadoutRepository.upsertSlot(
                active.getEquipmentLoadoutId(),
                entry.getKey().slotType(),
                entry.getKey().slotIndex(),
                entry.getValue(),
                accountId
            );
        }
        lastPersistedLoadoutSlots.put(accountId, current);
    }

    private @NotNull UUID inferLoadoutIdForDelete(@NotNull PlayerInventoryState state, @NotNull UUID accountId) {
        for (EquipmentLoadoutModel loadout : state.snapshotLoadouts(InventoryProfile.GAME)) {
            return loadout.getEquipmentLoadoutId();
        }
        return accountId;
    }

    private static @NotNull Map<SlotKey, UUID> snapshotLoadoutSlots(@NotNull PlayerInventoryState state) {
        EquipmentLoadoutModel active = state.findActiveLoadout(InventoryProfile.GAME);
        Map<SlotKey, UUID> snapshot = new HashMap<>();
        if (active == null) {
            return snapshot;
        }
        for (EquipmentLoadoutSlotModel slot : active.getSlots()) {
            if (slot.isDeleted()) {
                continue;
            }
            snapshot.put(new SlotKey(slot.getSlotType(), slot.getSlotIndex()), slot.getEquipmentInstanceId());
        }
        return snapshot;
    }

    /**
     * 状態破棄時 (プレイヤー退出後など) に内部スナップショットも削除します。
     *
     * @param accountId 対象アカウントID
     */
    public void clearAccount(@NotNull UUID accountId) {
        lastPersistedLoadoutSlots.remove(accountId);
        itemService.clearEquipmentState(accountId);
    }

    /**
     * オートセーブタスクが Bukkit Scheduler を停止する前に呼び出します。
     * 現状追加処理は不要ですが、将来の非同期キューに備えてフックを残しています。
     *
     * @param timeoutMs 最大待機時間（未使用）
     */
    public void awaitShutdown(long timeoutMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static @NotNull InventoryEntryDraft toDraft(@NotNull InventoryEntryModel entry) {
        return new InventoryEntryDraft(
            entry.getSlotIndex(),
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            entry.getQuantity(),
            entry.getMetadataJson(),
            entry.getInventoryEntryId(),
            entry.getUpdatedAt()
        );
    }

    /**
     * API 側で消失した inventory を同じ account/profile/type の正本へ再結合します。
     *
     * @param state 対象 state
     * @param missing 消失した inventory
     * @param accountId account ID
     * @param trigger 保存契機
     * @return 再結合先。復旧できない場合は null
     */
    private @Nullable InventoryModel recoverMissingInventory(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel missing,
        @NotNull UUID accountId,
        @NotNull SaveTrigger trigger
    ) {
        InventoryModel replacement = inventoryRepository.findByAccountId(accountId).stream()
            .filter(candidate -> candidate.isEnabled() && !candidate.isDeleted())
            .filter(candidate -> candidate.getInventoryType() == missing.getInventoryType())
            .filter(candidate -> candidate.getInventoryProfile().equalsIgnoreCase(missing.getInventoryProfile()))
            .findFirst()
            .orElse(null);
        if (replacement == null) {
            InventoryProfile profile = InventoryProfile.fromCode(missing.getInventoryProfile());
            if (profile == null) {
                return null;
            }
            replacement = inventoryRepository.create(
                accountId,
                missing.getInventoryType(),
                missing.getSlotCapacity(),
                accountId,
                profile,
                missing.getMetadataJson()
            );
        }
        if (replacement.getInventoryId().equals(missing.getInventoryId())) {
            return null;
        }
        state.replaceInventoryReference(missing.getInventoryId(), replacement);
        Logger.warn(
            LogId.W_5259,
            accountId,
            missing.getInventoryId(),
            replacement.getInventoryId(),
            trigger
        );
        return replacement;
    }

    /**
     * インベントリ同期失敗の HTTP 情報と保存契機を詳細ログへ出します。
     *
     * @param accountId account ID
     * @param inventoryId 対象 inventory ID
     * @param trigger 保存契機
     * @param entryCount ローカル entry 件数
     * @param failure 失敗原因
     */
    private void logInventorySyncFailure(
        @NotNull UUID accountId,
        @NotNull UUID inventoryId,
        @NotNull SaveTrigger trigger,
        int entryCount,
        @NotNull Throwable failure
    ) {
        int statusCode = failure instanceof InventoryApiException apiFailure
            ? apiFailure.getStatusCode()
            : -1;
        String responseBody = failure instanceof InventoryApiException apiFailure
            ? apiFailure.getResponseBody()
            : "<not-http>";
        Logger.warn(
            LogId.W_5258,
            accountId,
            inventoryId,
            trigger,
            entryCount,
            statusCode,
            responseBody
        );
        Logger.warn(LogId.W_5252, inventoryId, failureReason(failure));
    }

    private static @NotNull String failureReason(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** ロードアウトスロットを (slotType, slotIndex) で一意化するキー。 */
    private record SlotKey(@NotNull String slotType, int slotIndex) {
        private SlotKey(@NotNull String slotType, int slotIndex) {
            this.slotType = slotType.toUpperCase(java.util.Locale.ROOT);
            this.slotIndex = slotIndex;
        }
    }

    /** 保存契機。 */
    public enum SaveTrigger {
        AUTO,
        LOGOUT,
        PLUGIN_DISABLE,
        IMMEDIATE,
    }

    /**
     * 外部原子操作の直前に API が保存済みと確認した inventory entry 群です。
     * ローカル current snapshot とは分離し、操作後の三者マージでのみ使用します。
     *
     * @param accountId baseline を所有する account
     * @param entriesByInventoryId inventory ごとの保存済み entry
     */
    public record PersistedInventoryBaseline(
        @NotNull UUID accountId,
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventoryId
    ) {
        public PersistedInventoryBaseline {
            Map<UUID, List<InventoryEntryModel>> copied = new LinkedHashMap<>();
            entriesByInventoryId.forEach((inventoryId, entries) ->
                copied.put(inventoryId, List.copyOf(entries))
            );
            entriesByInventoryId = Map.copyOf(copied);
        }

        /** 指定 entry ID の保存済み行を返します。 */
        public @Nullable InventoryEntryModel findEntry(@NotNull UUID inventoryEntryId) {
            for (List<InventoryEntryModel> entries : entriesByInventoryId.values()) {
                for (InventoryEntryModel entry : entries) {
                    if (!entry.isDeleted() && entry.getInventoryEntryId().equals(inventoryEntryId)) {
                        return entry;
                    }
                }
            }
            return null;
        }

        /** 指定 inventory の保存済み行を返します。 */
        public @NotNull List<InventoryEntryModel> entries(@NotNull UUID inventoryId) {
            return entriesByInventoryId.getOrDefault(inventoryId, List.of());
        }
    }

    /**
     * 内部の登録キーで型を持ちたい場合のために、SlotKey のヘルパを公開します。
     *
     * @param slotType スロット種別
     * @param slotIndex slot_index
     * @return ロードアウト Repository に渡せる正規化済みキー
     */
    public static @NotNull String normalizeSlotType(@Nullable String slotType) {
        return slotType == null ? "" : slotType.toUpperCase(java.util.Locale.ROOT);
    }
}

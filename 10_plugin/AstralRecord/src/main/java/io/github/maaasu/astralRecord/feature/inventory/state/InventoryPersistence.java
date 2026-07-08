package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryDraft;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
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
 *   <li>{@link #saveNow(PlayerInventoryState)}: マーケット成立など、即時整合性が必要なケース向けの拡張点。
 *       同期的に save() を実行し、結果が確定するまで呼び出し元に戻りません。</li>
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
            lastPersistedLoadoutSlots.put(accountId, snapshotLoadoutSlots(state));
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
                    try {
                        inventoryRepository.replaceEntries(inventory.getInventoryId(), drafts, accountId);
                    } catch (RuntimeException e) {
                        Logger.warn(LogId.W_5252, inventory.getInventoryId(), e.getMessage());
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
     * マーケット成立など、即時整合性が必要な場面で同期的に保存します。
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
        return !state.isDirty();
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
        itemService.clearDirtyEquipmentDurability(accountId);
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
            entry.getMetadataJson()
        );
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

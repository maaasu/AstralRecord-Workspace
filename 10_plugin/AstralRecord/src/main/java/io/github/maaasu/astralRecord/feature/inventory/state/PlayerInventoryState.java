package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.status.model.StatusDefaults;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1 アカウント分のインベントリ・装備ロードアウト状態を保持する単一の真実源です。
 * <p>
 * 旧実装では {@code inventoryCache} / {@code entryWriteBuffer} / {@code hotbarEntryCache}
 * / {@code equipmentLoadoutCache} に状態を分散していましたが、それらを 1 インスタンスに統合し、
 * 全変更操作を本クラスの同期メソッドで処理することで二重持ちと書き込みレースを構造的に排除します。
 * <p>
 * 永続化は本クラスでは行いません。{@link #markDirty()} で「変更あり」を立て、
 * 60 秒間隔のオートセーブまたはログアウト処理が {@code InventoryPersistence} 経由で API へ反映します。
 */
public final class PlayerInventoryState {

    private final UUID accountId;
    /** インベントリ本体（profile 毎・種別毎に最大1件）。 */
    private final List<InventoryModel> inventories = new ArrayList<>();
    /** インベントリ毎の entry 一覧。 */
    private final Map<UUID, List<InventoryEntryModel>> entriesByInventoryId = new HashMap<>();
    /** 装備ロードアウト一覧（active 1 件 + 非アクティブ）。 */
    private final List<EquipmentLoadoutModel> loadouts = new ArrayList<>();
    /** metadataJson の API 保存が必要な inventoryId。 */
    private final Set<UUID> dirtyMetadataInventoryIds = new HashSet<>();

    /** GUI 表示中のインベントリ種別。所持品統合後は BAG 固定。非永続。 */
    private @NotNull InventoryType displayedType = InventoryType.BAG;
    /** BAG 表示の先頭行（0 始まり）。非永続。 */
    private int bagScrollRow;
    /** 現在のステータスで利用可能な BAG 論理スロット数。非永続。 */
    private int bagSlotCapacity = (int) StatusDefaults.INVENTORY_SLOTS;
    /** ホットバー選択中スロット（DB slot_index 1〜9 / オフハンド 10）。非永続。 */
    private @Nullable Integer selectedHotbarSlot;
    /** ホットバーショートカット表示モード。非永続。 */
    private boolean hotbarShortcutMode;

    /** 変更ありフラグ。next autosave で永続化を試みる。 */
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /**
     * アカウント単位の状態を初期化します。
     *
     * @param accountId 対象アカウントID
     */
    public PlayerInventoryState(@NotNull UUID accountId) {
        this.accountId = accountId;
    }

    public @NotNull UUID getAccountId() {
        return accountId;
    }

    // ---------------------------------------------------------------
    // dirty flag
    // ---------------------------------------------------------------

    /**
     * 状態変更があったことを記録します。次回オートセーブで永続化が試行されます。
     */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * 現在の dirty 状態を取得しつつクリアします。永続化呼び出し側で利用してください。
     *
     * @return 直前まで dirty だった場合 true
     */
    public boolean takeAndClearDirty() {
        return dirty.getAndSet(false);
    }

    /**
     * 永続化に失敗した場合、変更を失わないため dirty フラグを戻します。
     */
    public void restoreDirty() {
        dirty.set(true);
    }

    public boolean isDirty() {
        return dirty.get();
    }

    // ---------------------------------------------------------------
    // inventories
    // ---------------------------------------------------------------

    /**
     * 全インベントリの不変スナップショットを返します。
     *
     * @return プロファイル・種別を問わない全インベントリ
     */
    public synchronized @NotNull List<InventoryModel> snapshotInventories() {
        return List.copyOf(inventories);
    }

    /**
     * 指定 inventoryId のインベントリを返します。
     *
     * @param inventoryId 対象UUID
     * @return 該当インベントリ。なければ null
     */
    public synchronized @Nullable InventoryModel findInventoryById(@NotNull UUID inventoryId) {
        for (InventoryModel inventory : inventories) {
            if (inventory.getInventoryId().equals(inventoryId)) {
                return inventory;
            }
        }
        return null;
    }

    /**
     * 指定 profile / 種別のインベントリを返します。
     *
     * @param profile プロファイル
     * @param inventoryType 種別
     * @return 該当インベントリ。なければ null
     */
    public synchronized @Nullable InventoryModel findInventory(
        @NotNull InventoryProfile profile,
        @NotNull InventoryType inventoryType
    ) {
        for (InventoryModel inventory : inventories) {
            if (profile.getCode().equalsIgnoreCase(inventory.getInventoryProfile())
                && inventory.getInventoryType() == inventoryType) {
                return inventory;
            }
        }
        return null;
    }

    /**
     * インベントリを追加または同一 inventoryId のものを置換します。entry リストは初期化されません。
     *
     * @param inventory 追加・置換するインベントリ
     */
    public synchronized void putInventory(@NotNull InventoryModel inventory) {
        inventories.removeIf(cached -> cached.getInventoryId().equals(inventory.getInventoryId()));
        inventories.add(inventory);
    }

    /**
     * 指定インベントリの metadataJson を更新します。
     *
     * @param inventoryId 対象UUID
     * @param metadataJson 新しい metadata（null で消去）
     * @param updatedBy 更新者
     * @return 更新後インベントリ。対象が無ければ null
     */
    public synchronized @Nullable InventoryModel updateInventoryMetadata(
        @NotNull UUID inventoryId,
        @Nullable String metadataJson,
        @NotNull UUID updatedBy
    ) {
        for (int i = 0; i < inventories.size(); i++) {
            InventoryModel cached = inventories.get(i);
            if (!cached.getInventoryId().equals(inventoryId)) {
                continue;
            }
            InventoryModel updated = new InventoryModel(
                cached.getInventoryId(),
                cached.getAccountId(),
                cached.getInventoryType(),
                cached.getInventoryProfile(),
                cached.getSlotCapacity(),
                cached.isEnabled(),
                metadataJson,
                cached.getCreatedAt(),
                java.time.LocalDateTime.now(),
                cached.getCreatedBy(),
                updatedBy,
                cached.isDeleted()
            );
            inventories.set(i, updated);
            dirtyMetadataInventoryIds.add(inventoryId);
            markDirty();
            return updated;
        }
        return null;
    }

    /**
     * metadataJson の保存が必要な inventory のスナップショットを返します。
     *
     * @return metadataJson が未保存の inventory 一覧
     */
    public synchronized @NotNull List<InventoryModel> snapshotDirtyMetadataInventories() {
        if (dirtyMetadataInventoryIds.isEmpty()) {
            return List.of();
        }
        List<InventoryModel> result = new ArrayList<>();
        for (InventoryModel inventory : inventories) {
            if (dirtyMetadataInventoryIds.contains(inventory.getInventoryId())) {
                result.add(inventory);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 指定 inventory の metadataJson dirty 状態を解除します。
     *
     * @param inventoryId 保存済みとして扱う inventoryId
     */
    public synchronized void clearMetadataDirty(@NotNull UUID inventoryId) {
        dirtyMetadataInventoryIds.remove(inventoryId);
    }

    // ---------------------------------------------------------------
    // entries
    // ---------------------------------------------------------------

    /**
     * 指定インベントリの有効 entry 一覧（コピー）を返します。
     *
     * @param inventoryId 対象UUID
     * @return entry 一覧。未登録の場合は空
     */
    public synchronized @NotNull List<InventoryEntryModel> snapshotEntries(@NotNull UUID inventoryId) {
        List<InventoryEntryModel> entries = entriesByInventoryId.get(inventoryId);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * 指定インベントリの entry 一覧を一括差し替えます。永続化対象とするため dirty フラグを立てます。
     *
     * @param inventoryId 対象UUID
     * @param entries 新しい entry 一覧
     */
    public synchronized void replaceEntries(
        @NotNull UUID inventoryId,
        @NotNull List<InventoryEntryModel> entries
    ) {
        entriesByInventoryId.put(inventoryId, new ArrayList<>(entries));
        markDirty();
    }

    /**
     * ロードによる初期反映用。dirty フラグを立てずに entry をそのまま保持します。
     *
     * @param inventoryId 対象UUID
     * @param entries 取得済み entry 一覧
     */
    public synchronized void replaceEntriesFromLoad(
        @NotNull UUID inventoryId,
        @NotNull List<InventoryEntryModel> entries
    ) {
        entriesByInventoryId.put(inventoryId, new ArrayList<>(entries));
    }

    /**
     * 一括保存の応答を、送信後に変更されていないentryだけへ反映します。
     * 保存待ち中に生じた移動・数量変更は上書きせず、APIが採番した更新時刻だけを安全に取り込みます。
     */
    public synchronized void acknowledgePersistedEntries(
        @NotNull UUID inventoryId,
        @NotNull List<InventoryEntryModel> submitted,
        @NotNull List<InventoryEntryModel> persisted
    ) {
        Map<UUID, InventoryEntryModel> submittedById = new HashMap<>();
        for (InventoryEntryModel entry : submitted) {
            submittedById.put(entry.getInventoryEntryId(), entry);
        }
        Map<UUID, InventoryEntryModel> persistedById = new HashMap<>();
        for (InventoryEntryModel entry : persisted) {
            persistedById.put(entry.getInventoryEntryId(), entry);
        }
        List<InventoryEntryModel> current = entriesByInventoryId.get(inventoryId);
        if (current == null) return;
        for (int index = 0; index < current.size(); index++) {
            InventoryEntryModel currentEntry = current.get(index);
            InventoryEntryModel submittedEntry = submittedById.get(currentEntry.getInventoryEntryId());
            InventoryEntryModel persistedEntry = persistedById.get(currentEntry.getInventoryEntryId());
            if (submittedEntry != null && persistedEntry != null && currentEntry.equals(submittedEntry)) {
                current.set(index, persistedEntry);
            }
        }
    }

    /**
     * APIから再取得した単一entryだけを反映し、無関係な並行変更は保持します。
     *
     * @return entry が消費または移動したため前の並びを前詰めする必要がある inventory ID。不要なら {@code null}
     */
    public synchronized @Nullable UUID reconcileAuthoritativeEntry(
        @NotNull UUID inventoryEntryId,
        @Nullable InventoryEntryModel authoritative
    ) {
        UUID previousInventoryId = null;
        for (Map.Entry<UUID, List<InventoryEntryModel>> inventoryEntries : entriesByInventoryId.entrySet()) {
            boolean removed = inventoryEntries.getValue().removeIf(
                entry -> entry.getInventoryEntryId().equals(inventoryEntryId)
            );
            if (removed && previousInventoryId == null) {
                previousInventoryId = inventoryEntries.getKey();
            }
        }
        if (authoritative != null && !authoritative.isDeleted()) {
            entriesByInventoryId
                .computeIfAbsent(authoritative.getInventoryId(), ignored -> new ArrayList<>())
                .add(authoritative);
        }
        if (previousInventoryId == null
            || (authoritative != null && !authoritative.isDeleted()
                && previousInventoryId.equals(authoritative.getInventoryId()))) {
            return null;
        }
        return previousInventoryId;
    }

    /**
     * inventoryId の entry リストエントリを削除します。
     *
     * @param inventoryId 対象UUID
     * @return 削除前の entry 一覧（無ければ空）
     */
    public synchronized @NotNull List<InventoryEntryModel> removeEntries(@NotNull UUID inventoryId) {
        List<InventoryEntryModel> removed = entriesByInventoryId.remove(inventoryId);
        return removed == null ? List.of() : List.copyOf(removed);
    }

    // ---------------------------------------------------------------
    // loadouts
    // ---------------------------------------------------------------

    /**
     * 指定プロファイルのロードアウト一覧スナップショットを返します。
     *
     * @param profile プロファイル
     * @return ロードアウト一覧
     */
    public synchronized @NotNull List<EquipmentLoadoutModel> snapshotLoadouts(@NotNull InventoryProfile profile) {
        List<EquipmentLoadoutModel> result = new ArrayList<>();
        for (EquipmentLoadoutModel loadout : loadouts) {
            if (profile.getCode().equalsIgnoreCase(loadout.getLoadoutProfile())) {
                result.add(loadout);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 指定プロファイルのアクティブロードアウトを返します。
     *
     * @param profile プロファイル
     * @return アクティブなロードアウト。なければ null
     */
    public synchronized @Nullable EquipmentLoadoutModel findActiveLoadout(@NotNull InventoryProfile profile) {
        for (EquipmentLoadoutModel loadout : loadouts) {
            if (loadout.isActive()
                && !loadout.isDeleted()
                && profile.getCode().equalsIgnoreCase(loadout.getLoadoutProfile())) {
                return loadout;
            }
        }
        return null;
    }

    /**
     * ロードアウトを追加または同一IDのものを置換します。
     *
     * @param loadout 追加・置換するロードアウト
     */
    public synchronized void putLoadout(@NotNull EquipmentLoadoutModel loadout) {
        loadouts.removeIf(cached -> cached.getEquipmentLoadoutId().equals(loadout.getEquipmentLoadoutId()));
        loadouts.add(loadout);
    }

    /**
     * アクティブロードアウトのスロット (slotType + slotIndex) を upsert します。
     * <p>
     * equipmentInstanceId が null の場合はスロットを削除（解除）します。
     *
     * @param profile プロファイル
     * @param slotType スロット種別 (HEAD/CHEST/LEGS/FEET/ACCESSORY)
     * @param slotIndex slot_index
     * @param equipmentInstanceId 装備インスタンスID。null で解除
     * @param updatedBy 更新者
     */
    public synchronized void upsertActiveLoadoutSlot(
        @NotNull InventoryProfile profile,
        @NotNull String slotType,
        int slotIndex,
        @Nullable UUID equipmentInstanceId,
        @NotNull UUID updatedBy
    ) {
        EquipmentLoadoutModel active = findActiveLoadout(profile);
        if (active == null) {
            return;
        }
        List<EquipmentLoadoutSlotModel> currentSlots = active.getSlots();
        List<EquipmentLoadoutSlotModel> nextSlots = new ArrayList<>(currentSlots.size() + 1);
        boolean replaced = false;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (EquipmentLoadoutSlotModel slot : currentSlots) {
            if (slot.getSlotType().equalsIgnoreCase(slotType) && slot.getSlotIndex() == slotIndex) {
                replaced = true;
                if (equipmentInstanceId == null) {
                    continue; // 削除
                }
                nextSlots.add(new EquipmentLoadoutSlotModel(
                    slot.getEquipmentLoadoutSlotId(),
                    slot.getEquipmentLoadoutId(),
                    slot.getSlotType(),
                    slot.getSlotIndex(),
                    equipmentInstanceId,
                    slot.getCreatedAt(),
                    now,
                    slot.getCreatedBy(),
                    updatedBy,
                    false
                ));
                continue;
            }
            nextSlots.add(slot);
        }
        if (!replaced && equipmentInstanceId != null) {
            nextSlots.add(new EquipmentLoadoutSlotModel(
                UUID.randomUUID(),
                active.getEquipmentLoadoutId(),
                slotType,
                slotIndex,
                equipmentInstanceId,
                now,
                now,
                updatedBy,
                updatedBy,
                false
            ));
        }
        EquipmentLoadoutModel updated = new EquipmentLoadoutModel(
            active.getEquipmentLoadoutId(),
            active.getAccountId(),
            active.getLoadoutProfile(),
            active.getLoadoutName(),
            active.getSortOrder(),
            active.isActive(),
            active.getMetadataJson(),
            nextSlots,
            active.getCreatedAt(),
            now,
            active.getCreatedBy(),
            updatedBy,
            active.isDeleted()
        );
        putLoadout(updated);
        markDirty();
    }

    // ---------------------------------------------------------------
    // GUI 状態（非永続）
    // ---------------------------------------------------------------

    public synchronized @NotNull InventoryType getDisplayedType() {
        return displayedType;
    }

    public synchronized void setDisplayedType(@NotNull InventoryType displayedType) {
        this.displayedType = displayedType;
    }

    public synchronized int getBagScrollRow() {
        return bagScrollRow;
    }

    public synchronized boolean setBagScrollRow(int bagScrollRow) {
        int normalized = Math.max(0, bagScrollRow);
        if (this.bagScrollRow == normalized) {
            return false;
        }
        this.bagScrollRow = normalized;
        return true;
    }

    /**
     * 現在のステータスに基づく BAG の利用可能スロット数を更新します。
     * 容量外の entry は保持され、空いた後の新規追加先には選ばれません。
     *
     * @param bagSlotCapacity 0 以上の利用可能スロット数
     * @return 値が変化した場合 true
     */
    public synchronized boolean setBagSlotCapacity(int bagSlotCapacity) {
        int normalized = Math.max(0, bagSlotCapacity);
        if (this.bagSlotCapacity == normalized) return false;
        this.bagSlotCapacity = normalized;
        return true;
    }

    /** 現在のステータスで利用可能な BAG 論理スロット数を返します。 */
    public synchronized int getBagSlotCapacity() { return bagSlotCapacity; }

    public synchronized @Nullable Integer getSelectedHotbarSlot() {
        return selectedHotbarSlot;
    }

    public synchronized void setSelectedHotbarSlot(@Nullable Integer selectedHotbarSlot) {
        this.selectedHotbarSlot = selectedHotbarSlot;
    }

    public synchronized boolean isHotbarShortcutMode() {
        return hotbarShortcutMode;
    }

    public synchronized boolean setHotbarShortcutMode(boolean on) {
        if (this.hotbarShortcutMode == on) {
            return false;
        }
        this.hotbarShortcutMode = on;
        return true;
    }
}

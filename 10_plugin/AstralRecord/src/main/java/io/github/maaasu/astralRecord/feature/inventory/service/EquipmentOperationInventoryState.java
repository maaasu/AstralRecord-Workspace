package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 装備操作中に inventory から一時退避した entry を、取得元の state へ戻す補助処理です。
 */
public final class EquipmentOperationInventoryState {

    private EquipmentOperationInventoryState() {
    }

    /**
     * 退避元 entry を同じ state へ復元します。
     * <p>
     * 元スロットが使用済みの場合は、同じ inventory、BAG、HOTBAR の順に空きスロットを探します。
     * 同じ entry またはインスタンスがすでに存在する場合は、復元済みとして扱います。
     *
     * @param state 退避元のプレイヤー inventory state
     * @param heldEntry 復元する entry
     * @return 復元済み、または復元に成功した場合 {@code true}
     */
    public static boolean restoreEntry(
        @NotNull PlayerInventoryState state,
        @Nullable InventoryEntryModel heldEntry
    ) {
        if (heldEntry == null || !state.getAccountId().equals(accountIdOf(state, heldEntry))) {
            return heldEntry == null;
        }
        synchronized (state) {
            if (containsEntry(state, heldEntry)) {
                return true;
            }
            InventoryModel original = state.findInventoryById(heldEntry.getInventoryId());
            List<InventoryModel> candidates = restoreCandidates(state, original);
            for (InventoryModel candidate : candidates) {
                Integer slot = availableSlot(state, candidate, heldEntry, candidate == original);
                if (slot == null) {
                    continue;
                }
                List<InventoryEntryModel> entries = activeEntries(state, candidate.getInventoryId());
                entries.add(copyForInventory(heldEntry, candidate.getInventoryId(), slot));
                state.replaceEntries(candidate.getInventoryId(), entries);
                return true;
            }
            return false;
        }
    }

    /**
     * 破壊された装備 entry を、退避元 state からだけ削除します。
     *
     * @param state 対象 state
     * @param heldEntry 破壊対象として退避していた entry
     * @return entry が存在しない、または削除できた場合 {@code true}
     */
    public static boolean removeEntry(
        @NotNull PlayerInventoryState state,
        @Nullable InventoryEntryModel heldEntry
    ) {
        if (heldEntry == null) {
            return true;
        }
        synchronized (state) {
            boolean found = false;
            for (InventoryModel inventory : state.snapshotInventories()) {
                List<InventoryEntryModel> current = state.snapshotEntries(inventory.getInventoryId());
                List<InventoryEntryModel> remaining = current.stream()
                    .filter(entry -> !sameEntry(entry, heldEntry))
                    .toList();
                if (remaining.size() == current.size()) {
                    continue;
                }
                state.replaceEntries(inventory.getInventoryId(), remaining);
                found = true;
            }
            return found || !containsEntry(state, heldEntry);
        }
    }

    /**
     * 支払い前スナップショットを、別ログイン世代の state を参照せず退避元 state へ復元します。
     *
     * @param state 復元対象 state
     * @param snapshot 支払い前スナップショット
     * @return 同一アカウントの state へ復元できた場合 {@code true}
     */
    public static boolean restoreSnapshot(
        @NotNull PlayerInventoryState state,
        @Nullable InventoryService.InventoryStateSnapshot snapshot
    ) {
        if (snapshot == null || !state.getAccountId().equals(snapshot.accountId())) {
            return false;
        }
        synchronized (state) {
            for (InventoryModel inventory : state.snapshotInventories()) {
                state.replaceEntries(
                    inventory.getInventoryId(),
                    snapshot.entriesByInventoryId().getOrDefault(inventory.getInventoryId(), List.of())
                );
            }
            state.setDisplayedType(snapshot.displayedType());
            state.restoreDirty();
            return true;
        }
    }

    private static @NotNull UUID accountIdOf(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel entry
    ) {
        InventoryModel inventory = state.findInventoryById(entry.getInventoryId());
        return inventory == null ? new UUID(0L, 0L) : inventory.getAccountId();
    }

    private static boolean containsEntry(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel heldEntry
    ) {
        return state.snapshotInventories().stream()
            .flatMap(inventory -> state.snapshotEntries(inventory.getInventoryId()).stream())
            .anyMatch(entry -> sameEntry(entry, heldEntry));
    }

    private static boolean sameEntry(
        @NotNull InventoryEntryModel current,
        @NotNull InventoryEntryModel heldEntry
    ) {
        if (current.isDeleted()) {
            return false;
        }
        if (current.getInventoryEntryId().equals(heldEntry.getInventoryEntryId())) {
            return true;
        }
        return heldEntry.getInstanceId() != null
            && heldEntry.getInstanceId().equals(current.getInstanceId());
    }

    private static @NotNull List<InventoryModel> restoreCandidates(
        @NotNull PlayerInventoryState state,
        @Nullable InventoryModel original
    ) {
        List<InventoryModel> candidates = new ArrayList<>();
        // 装備操作 GUI から戻す装備は、元がホットバーでも BAG を優先する。
        addCandidate(state, candidates, InventoryType.BAG);
        if (original != null
            && original.getInventoryType() != InventoryType.HOTBAR
            && original.isEnabled()
            && !original.isDeleted()) {
            candidates.add(original);
        }
        addCandidate(state, candidates, InventoryType.HOTBAR);
        return candidates;
    }

    private static void addCandidate(
        @NotNull PlayerInventoryState state,
        @NotNull List<InventoryModel> candidates,
        @NotNull InventoryType type
    ) {
        state.snapshotInventories().stream()
            .filter(inventory -> inventory.getInventoryType() == type)
            .filter(InventoryModel::isEnabled)
            .filter(inventory -> !inventory.isDeleted())
            .filter(inventory -> !candidates.contains(inventory))
            .findFirst()
            .ifPresent(candidates::add);
    }

    private static @Nullable Integer availableSlot(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull InventoryEntryModel heldEntry,
        boolean preferOriginal
    ) {
        int capacity = inventory.getInventoryType() == InventoryType.BAG
            ? state.getBagSlotCapacity()
            : NormalInventoryLayout.effectiveCapacity(inventory.getInventoryType(), inventory.getSlotCapacity());
        Set<Integer> used = new HashSet<>();
        for (InventoryEntryModel entry : state.snapshotEntries(inventory.getInventoryId())) {
            Integer slot = entry.getSlotIndex();
            if (!entry.isDeleted() && slot != null && slot > 0 && slot <= capacity) {
                used.add(slot);
            }
        }
        Integer originalSlot = heldEntry.getSlotIndex();
        if (preferOriginal
            && originalSlot != null
            && originalSlot > 0
            && originalSlot <= capacity
            && !used.contains(originalSlot)) {
            return originalSlot;
        }
        return NormalInventoryLayout.findNextFreeSlot(used, capacity);
    }

    private static @NotNull List<InventoryEntryModel> activeEntries(
        @NotNull PlayerInventoryState state,
        @NotNull UUID inventoryId
    ) {
        return new ArrayList<>(state.snapshotEntries(inventoryId).stream()
            .filter(entry -> !entry.isDeleted())
            .toList());
    }

    private static @NotNull InventoryEntryModel copyForInventory(
        @NotNull InventoryEntryModel source,
        @NotNull UUID inventoryId,
        int slotIndex
    ) {
        return new InventoryEntryModel(
            source.getInventoryEntryId(),
            inventoryId,
            slotIndex,
            source.getItemCategory(),
            source.getItemId(),
            source.getInstanceType(),
            source.getInstanceId(),
            source.getQuantity(),
            source.getMetadataJson(),
            source.getCreatedAt(),
            source.getUpdatedAt(),
            source.getCreatedBy(),
            source.getUpdatedBy(),
            false
        );
    }
}

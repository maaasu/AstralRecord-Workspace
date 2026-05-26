package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryDraft;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * インベントリ entry の表示キャッシュと API 一括同期を管理します。
 */
final class InventoryEntryWriteBuffer {
    private final InventoryRepository inventoryRepository;
    private final Map<UUID, CompletableFuture<InventoryModel>> pendingInventoryCreates;
    private final Map<UUID, UUID> persistedInventoryIds;
    private final Set<CompletableFuture<?>> pendingWriteTasks;
    private final Map<UUID, List<InventoryEntryModel>> entryCache = new ConcurrentHashMap<>();
    private final Set<UUID> pendingEntryReplaces = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refreshingEntries = ConcurrentHashMap.newKeySet();

    InventoryEntryWriteBuffer(
        @NotNull InventoryRepository inventoryRepository,
        @NotNull Map<UUID, CompletableFuture<InventoryModel>> pendingInventoryCreates,
        @NotNull Map<UUID, UUID> persistedInventoryIds,
        @NotNull Set<CompletableFuture<?>> pendingWriteTasks
    ) {
        this.inventoryRepository = inventoryRepository;
        this.pendingInventoryCreates = pendingInventoryCreates;
        this.persistedInventoryIds = persistedInventoryIds;
        this.pendingWriteTasks = pendingWriteTasks;
    }

    /**
     * 表示用キャッシュからインベントリ内の entry 一覧を取得します。
     *
     * @param inventoryId 対象インベントリID
     * @return キャッシュ済み entry 一覧
     */
    @NotNull List<InventoryEntryModel> getCachedEntries(@NotNull UUID inventoryId) {
        return entryCache.getOrDefault(inventoryId, List.of());
    }

    void refreshEntriesAsync(@NotNull UUID inventoryId) {
        if (pendingEntryReplaces.contains(inventoryId)) {
            return;
        }
        if (!refreshingEntries.add(inventoryId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshEntries(inventoryId);
            } catch (RuntimeException e) {
                Logger.warn(LogId.W_5252, inventoryId, e.getMessage());
            } finally {
                refreshingEntries.remove(inventoryId);
            }
        });
    }

    void refreshEntries(@NotNull UUID inventoryId) {
        if (pendingEntryReplaces.contains(inventoryId)) {
            return;
        }
        List<InventoryEntryModel> refreshed = new ArrayList<>(inventoryRepository.findEntries(inventoryId));
        entryCache.put(inventoryId, List.copyOf(refreshed));
    }

    /**
     * entry 一覧を即時キャッシュ反映し、API へ一括置換として非同期同期します。
     *
     * @param inventoryId 対象インベントリID
     * @param drafts 置換後 entry 一覧
     * @param updatedBy 更新者ID
     * @return 表示用に即時反映した楽観 entry 一覧
     */
    @NotNull List<InventoryEntryModel> replaceEntriesOptimistically(
        @NotNull UUID inventoryId,
        @NotNull List<InventoryEntryDraft> drafts,
        @NotNull UUID updatedBy
    ) {
        UUID resolvedInventoryId = persistedInventoryIds.getOrDefault(inventoryId, inventoryId);
        pendingEntryReplaces.add(resolvedInventoryId);
        List<InventoryEntryModel> optimistic = drafts.stream()
            .map(draft -> createEntryModel(UUID.randomUUID(), resolvedInventoryId, draft, updatedBy))
            .toList();
        putEntriesInCache(resolvedInventoryId, optimistic);

        CompletableFuture<InventoryModel> pendingInventory = pendingInventoryCreates.get(resolvedInventoryId);
        java.util.concurrent.atomic.AtomicReference<UUID> persistedInventoryId =
            new java.util.concurrent.atomic.AtomicReference<>(resolvedInventoryId);
        CompletableFuture<List<InventoryEntryModel>> replaceFuture = pendingInventory == null
            ? CompletableFuture.supplyAsync(() -> inventoryRepository.replaceEntries(resolvedInventoryId, drafts, updatedBy))
            : pendingInventory.thenApply(savedInventory -> {
                UUID savedInventoryId = savedInventory.getInventoryId();
                persistedInventoryId.set(savedInventoryId);
                pendingEntryReplaces.add(savedInventoryId);
                return inventoryRepository.replaceEntries(savedInventoryId, drafts, updatedBy);
            });

        trackWriteTask(replaceFuture.thenAccept(saved -> {
            pendingEntryReplaces.remove(resolvedInventoryId);
            UUID savedInventoryId = persistedInventoryId.get();
            pendingEntryReplaces.remove(savedInventoryId);
            if (saved != null) {
                UUID responseInventoryId = saved.stream()
                    .findFirst()
                    .map(InventoryEntryModel::getInventoryId)
                    .orElse(savedInventoryId);
                pendingEntryReplaces.remove(responseInventoryId);
                putEntriesInCache(responseInventoryId, saved);
            }
        }).exceptionally(error -> {
            pendingEntryReplaces.remove(resolvedInventoryId);
            pendingEntryReplaces.remove(persistedInventoryId.get());
            Logger.warn(LogId.W_5252, resolvedInventoryId, error.getMessage());
            return null;
        }));

        return optimistic;
    }

    void putEntriesInCache(@NotNull UUID inventoryId, @NotNull List<InventoryEntryModel> entries) {
        entryCache.put(inventoryId, List.copyOf(entries));
    }

    List<InventoryEntryModel> removeEntriesFromCache(@NotNull UUID inventoryId) {
        List<InventoryEntryModel> removed = entryCache.remove(inventoryId);
        return removed == null ? List.of() : removed;
    }

    private void trackWriteTask(@NotNull CompletableFuture<?> future) {
        pendingWriteTasks.add(future);
        future.whenComplete((ignored, error) -> pendingWriteTasks.remove(future));
    }

    private @NotNull InventoryEntryModel createEntryModel(
        @NotNull UUID entryId,
        @NotNull UUID inventoryId,
        @NotNull InventoryEntryDraft draft,
        @NotNull UUID actorId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            inventoryId,
            draft.getSlotIndex(),
            draft.getItemCategory(),
            draft.getItemId(),
            draft.getInstanceType(),
            draft.getInstanceId(),
            draft.getQuantity(),
            draft.getMetadataJson(),
            now,
            now,
            actorId,
            actorId,
            false
        );
    }
}

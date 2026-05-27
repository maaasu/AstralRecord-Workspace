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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * インベントリ entry の表示キャッシュと API 一括同期を管理します。
 * <p>
 * 書き込み戦略:
 * <ol>
 *   <li>cache は呼び出し直後に楽観反映する（プレイヤーの操作は即時に見える）</li>
 *   <li>API 書き込みは {@link InventoryWriteCoalescer} で 1 秒の窓に集約する</li>
 *   <li>flush 時、inventoryId 単位の write チェーンで API 呼び出しを直列化する</li>
 *   <li>API 応答は最新書き込みシーケンスに一致する場合のみ cache を書き戻す</li>
 * </ol>
 */
final class InventoryEntryWriteBuffer {
    private final InventoryRepository inventoryRepository;
    private final Map<UUID, CompletableFuture<InventoryModel>> pendingInventoryCreates;
    private final Map<UUID, UUID> persistedInventoryIds;
    private final Set<CompletableFuture<?>> pendingWriteTasks;
    private final InventoryWriteCoalescer entryCoalescer;
    private final Map<UUID, List<InventoryEntryModel>> entryCache = new ConcurrentHashMap<>();
    private final Set<UUID> pendingEntryReplaces = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refreshingEntries = ConcurrentHashMap.newKeySet();
    /** inventoryId 単位に API 書き込みを直列化するためのチェーン末尾。 */
    private final Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    /** inventoryId 単位の書き込みシーケンス番号。古い書き込み応答での上書きを防ぐ。 */
    private final Map<UUID, AtomicLong> writeSequence = new ConcurrentHashMap<>();

    InventoryEntryWriteBuffer(
        @NotNull InventoryRepository inventoryRepository,
        @NotNull Map<UUID, CompletableFuture<InventoryModel>> pendingInventoryCreates,
        @NotNull Map<UUID, UUID> persistedInventoryIds,
        @NotNull Set<CompletableFuture<?>> pendingWriteTasks,
        @NotNull InventoryWriteCoalescer entryCoalescer
    ) {
        this.inventoryRepository = inventoryRepository;
        this.pendingInventoryCreates = pendingInventoryCreates;
        this.persistedInventoryIds = persistedInventoryIds;
        this.pendingWriteTasks = pendingWriteTasks;
        this.entryCoalescer = entryCoalescer;
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
     * entry 一覧を即時キャッシュ反映し、API への一括置換を coalescer で集約します。
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
        List<InventoryEntryDraft> snapshotDrafts = List.copyOf(drafts);
        List<InventoryEntryModel> optimistic = snapshotDrafts.stream()
            .map(draft -> createEntryModel(UUID.randomUUID(), resolvedInventoryId, draft, updatedBy))
            .toList();
        putEntriesInCache(resolvedInventoryId, optimistic);

        ReplacePayload payload = new ReplacePayload(snapshotDrafts, updatedBy);
        entryCoalescer.submit(
            resolvedInventoryId,
            payload,
            latest -> executeReplaceEntries(resolvedInventoryId, latest)
        );

        return optimistic;
    }

    /**
     * coalescer の flush から呼び出され、実 API 書き込みを直列化・シーケンス検査つきで発行します。
     */
    private void executeReplaceEntries(@NotNull UUID resolvedInventoryId, @NotNull ReplacePayload payload) {
        List<InventoryEntryDraft> drafts = payload.drafts();
        UUID updatedBy = payload.updatedBy();

        AtomicLong seqCounter = writeSequence.computeIfAbsent(resolvedInventoryId, key -> new AtomicLong());
        long mySeq = seqCounter.incrementAndGet();

        CompletableFuture<InventoryModel> pendingInventory = pendingInventoryCreates.get(resolvedInventoryId);
        AtomicReference<UUID> persistedInventoryId = new AtomicReference<>(resolvedInventoryId);

        // 直前の同一 inventoryId 書き込み完了後にだけ次の API リクエストを発行する。
        // 直列化により、後発の楽観反映が古い書き込み応答で上書きされる事故を防ぐ。
        CompletableFuture<Void> previousChain = writeChains
            .getOrDefault(resolvedInventoryId, CompletableFuture.completedFuture(null))
            .exceptionally(error -> null);

        CompletableFuture<List<InventoryEntryModel>> replaceFuture = previousChain.thenComposeAsync(ignored -> {
            if (pendingInventory == null) {
                return CompletableFuture.completedFuture(
                    inventoryRepository.replaceEntries(resolvedInventoryId, drafts, updatedBy)
                );
            }
            return pendingInventory.thenApply(savedInventory -> {
                UUID savedInventoryId = savedInventory.getInventoryId();
                persistedInventoryId.set(savedInventoryId);
                pendingEntryReplaces.add(savedInventoryId);
                return inventoryRepository.replaceEntries(savedInventoryId, drafts, updatedBy);
            });
        });

        CompletableFuture<Void> writeCompletion = replaceFuture.handle((saved, error) -> {
            boolean isLatest = seqCounter.get() == mySeq;
            UUID savedInventoryId = persistedInventoryId.get();
            if (error != null) {
                Logger.warn(LogId.W_5252, resolvedInventoryId, error.getMessage());
                if (isLatest) {
                    pendingEntryReplaces.remove(resolvedInventoryId);
                    pendingEntryReplaces.remove(savedInventoryId);
                }
                return null;
            }
            if (!isLatest) {
                // 後続の書き込みが既に発行されているため、API 応答でキャッシュを上書きしない。
                // pendingEntryReplaces は最新の書き込み完了時にまとめて解除する。
                return null;
            }
            pendingEntryReplaces.remove(resolvedInventoryId);
            pendingEntryReplaces.remove(savedInventoryId);
            if (saved != null) {
                UUID responseInventoryId = saved.stream()
                    .findFirst()
                    .map(InventoryEntryModel::getInventoryId)
                    .orElse(savedInventoryId);
                pendingEntryReplaces.remove(responseInventoryId);
                putEntriesInCache(responseInventoryId, saved);
            }
            return null;
        });

        writeChains.put(resolvedInventoryId, writeCompletion);
        writeCompletion.whenComplete((ignored, error) -> writeChains.compute(
            resolvedInventoryId,
            (key, current) -> current == writeCompletion ? null : current
        ));
        trackWriteTask(writeCompletion);
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

    /** coalescer に詰める書き込みペイロード。 */
    private record ReplacePayload(@NotNull List<InventoryEntryDraft> drafts, @NotNull UUID updatedBy) {
    }
}

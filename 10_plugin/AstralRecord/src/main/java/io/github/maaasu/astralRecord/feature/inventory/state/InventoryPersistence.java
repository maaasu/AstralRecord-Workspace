package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateAcknowledgementException;
import io.github.maaasu.astralRecord.feature.mutation.repository.PlayerStateRepository;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final List<java.util.function.Function<UUID, PlayerStateSection>> stateParticipants = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<UUID, PlayerStateSnapshot> pendingSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Set<UUID>>> persistedEntryIds = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, java.time.LocalDateTime>> persistedEntryVersions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> persistedInventoryIds = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> persistedLoadoutIds = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerInventoryState> liveStates = new ConcurrentHashMap<>();
    private final Set<UUID> blockedSnapshots = ConcurrentHashMap.newKeySet();
    private final Set<UUID> acknowledgementBlockedSnapshots = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> snapshotAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retryNotBefore = new ConcurrentHashMap<>();
    private final Map<UUID, Object> snapshotSaveLocks = new ConcurrentHashMap<>();
    private final PlayerStateRepository playerStateRepository;

    /**
     * 永続層との同期処理を構築します。
     *
     * @param inventoryRepository インベントリ repository
     * @param equipmentLoadoutRepository 装備ロードアウト repository
     * @param itemService 装備状態を同じ snapshot に含めるアイテムサービス
     * @param playerStateRepository 完成状態を一括保存する repository
     */
    public InventoryPersistence(
        @NotNull InventoryRepository inventoryRepository,
        @NotNull EquipmentLoadoutRepository equipmentLoadoutRepository,
        @NotNull ItemService itemService,
        @NotNull PlayerStateRepository playerStateRepository
    ) {
        this.inventoryRepository = inventoryRepository;
        this.equipmentLoadoutRepository = equipmentLoadoutRepository;
        this.itemService = itemService;
        this.playerStateRepository = playerStateRepository;
    }

    /**
     * 起動中に、所持品と同時保存する機能を登録します。
     * @param participant stateロック内でdirty状態を捕捉し、変更がない場合nullを返す処理
     */
    public void registerStateParticipant(@NotNull java.util.function.Function<UUID, PlayerStateSection> participant) {
        stateParticipants.add(participant);
    }

    /**
     * 保存契約の不整合を検出し、そのアカウントの追加経済操作を止めているか返します。
     * @param accountId 対象アカウント
     * @return 通信の一時障害ではなく、確定的な保存拒否の場合true
     */
    public boolean isPlayerStateBlocked(@NotNull UUID accountId) {
        PlayerStateSnapshot snapshot = pendingSnapshots.get(accountId);
        return snapshot != null && blockedSnapshots.contains(snapshot.snapshotId);
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
        Set<UUID> loadedInventoryIds = ConcurrentHashMap.newKeySet();
        Set<UUID> loadedLoadoutIds = ConcurrentHashMap.newKeySet();
        persistedInventoryIds.put(accountId, loadedInventoryIds);
        persistedLoadoutIds.put(accountId, loadedLoadoutIds);
        try {
            List<InventoryModel> inventories = inventoryRepository.findByAccountId(accountId);
            Map<UUID, Set<UUID>> loadedEntryIds = new HashMap<>();
            Map<UUID, java.time.LocalDateTime> loadedEntryVersions = new HashMap<>();
            for (InventoryModel inventory : inventories) {
                state.putInventory(inventory);
                loadedInventoryIds.add(inventory.getInventoryId());
                List<InventoryEntryModel> entries = inventoryRepository.findEntries(inventory.getInventoryId());
                state.replaceEntriesFromLoad(inventory.getInventoryId(), entries);
                entries.stream().filter(entry -> !entry.isDeleted()).forEach(entry ->
                    loadedEntryVersions.put(entry.getInventoryEntryId(), entry.getUpdatedAt()));
                loadedEntryIds.put(inventory.getInventoryId(), entries.stream().filter(entry -> !entry.isDeleted())
                    .map(InventoryEntryModel::getInventoryEntryId).collect(java.util.stream.Collectors.toCollection(HashSet::new)));
            }
            persistedEntryIds.put(accountId, loadedEntryIds);
            persistedEntryVersions.put(accountId, loadedEntryVersions);
            List<EquipmentLoadoutModel> loadouts = equipmentLoadoutRepository
                .findByAccountId(accountId, InventoryProfile.GAME);
            for (EquipmentLoadoutModel loadout : loadouts) {
                state.putLoadout(loadout);
                loadedLoadoutIds.add(loadout.getEquipmentLoadoutId());
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
                boolean unavailableMaster = loaded != null
                    && itemService.isMasterDataLoaded()
                    && itemService.findLoadedById(loaded.getItemId()) == null;
                if (unavailableOwner || confirmedMissing || unavailableMaster) {
                    state.discardUnavailableEquipmentInstance(UUID.fromString(instanceId));
                    itemService.evictEquipmentInstanceFromCache(instanceId);
                }
            }
            boolean discardedUnavailableEquipment = state.isDirty();
            boolean discardedUnavailableNormalItems = discardUnavailableNormalItemEntries(state);
            if (discardedUnavailableEquipment || discardedUnavailableNormalItems) {
                save(state, SaveTrigger.IMMEDIATE);
            }
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5252, accountId, e.getMessage());
        }
        return state;
    }

    /**
     * 現在のアイテムマスタに存在しない通常 entry を削除します。
     * <p>
     * マスタキャッシュが未ロードの場合は、外部障害で全 entry を誤削除しないため何もしません。
     *
     * @param state 読み込み済みインベントリ state
     * @return entry を削除した場合は {@code true}
     */
    private boolean discardUnavailableNormalItemEntries(@NotNull PlayerInventoryState state) {
        if (!itemService.isMasterDataLoaded()) {
            return false;
        }
        Set<String> unavailableItemIds = new HashSet<>();
        for (InventoryModel inventory : state.snapshotInventories()) {
            for (InventoryEntryModel entry : state.snapshotEntries(inventory.getInventoryId())) {
                if (entry.isDeleted()
                    || entry.getInstanceId() != null
                    || entry.getItemId() == null
                    || entry.getItemId().isBlank()) {
                    continue;
                }
                if (itemService.findLoadedById(entry.getItemId()) == null) {
                    unavailableItemIds.add(entry.getItemId().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return state.discardUnavailableItemMasterEntries(unavailableItemIds);
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
        return savePlayerState(state, null);
    }

    private boolean save(
        @NotNull PlayerInventoryState state,
        @NotNull SaveTrigger trigger,
        @Nullable Map<UUID, List<InventoryEntryModel>> persistedEntries
    ) {
        return savePlayerState(state, persistedEntries);
    }

    /**
     * 次回保存へ持ち越されたインベントリまたは装備耐久度の変更があるかを返します。
     *
     * @param state 判定対象 state
     * @return 未保存変更が残っている場合は {@code true}
     */
    public boolean hasPendingChanges(@NotNull PlayerInventoryState state) {
        synchronized (state) {
            return state.isDirty() || pendingSnapshots.containsKey(state.getAccountId())
                || itemService.hasDirtyEquipmentDurability(state.getAccountId())
                || !itemService.snapshotDirtyEquipmentState(state.getAccountId()).isEmpty()
                || stateParticipants.stream().anyMatch(participant -> participant.apply(state.getAccountId()) != null);
        }
    }

    private boolean savePlayerState(PlayerInventoryState state, Map<UUID, List<InventoryEntryModel>> baselineTarget) {
        synchronized (snapshotSaveLocks.computeIfAbsent(state.getAccountId(), ignored -> new Object())) {
            return savePlayerStateLocked(state, baselineTarget);
        }
    }

    private boolean savePlayerStateLocked(PlayerInventoryState state, Map<UUID, List<InventoryEntryModel>> baselineTarget) {
        UUID accountId = state.getAccountId();
        liveStates.put(accountId, state);
        PlayerStateSnapshot snapshot = pendingSnapshots.get(accountId);
        if (snapshot == null) {
            synchronized (state) {
                snapshot = captureState(state, false);
                if (snapshot == null) return false;
                state.takeAndClearDirty();
                pendingSnapshots.put(accountId, snapshot);
            }
        }
        if (blockedSnapshots.contains(snapshot.snapshotId)) return true;
        Long retryAt = retryNotBefore.get(accountId);
        if (retryAt != null && System.nanoTime() - retryAt < 0L) return true;
        try {
            JsonObject ack = saveAndResolveAcknowledgement(snapshot);
            var entryVersions = PlayerStateSnapshot.versions(ack, "entries", "inventoryEntryId");
            synchronized (state) {
                 state.acknowledgeSnapshotVersions(snapshot.inventories,
                     PlayerStateSnapshot.versions(ack, "inventories", "inventoryId"),
                    PlayerStateSnapshot.versions(ack, "loadouts", "equipmentLoadoutId"), entryVersions);
                Map<String, String> equipmentVersions = new HashMap<>();
                for (var element : ack.getAsJsonArray("equipment")) {
                    var row = element.getAsJsonObject();
                    equipmentVersions.put(row.get("equipmentInstanceId").getAsString(), row.get("updatedAt").getAsString());
                }
                itemService.acknowledgeEquipmentState(accountId, snapshot.equipment, equipmentVersions);
                persistedInventoryIds.computeIfAbsent(accountId, ignored -> ConcurrentHashMap.newKeySet())
                    .addAll(snapshot.inventories.stream().map(InventoryModel::getInventoryId).toList());
                persistedLoadoutIds.computeIfAbsent(accountId, ignored -> ConcurrentHashMap.newKeySet())
                    .addAll(snapshot.loadoutIds);
                for (PlayerStateSection section : snapshot.sections) {
                    try {
                        section.acknowledge().accept(ack.get(section.name()));
                    } catch (PlayerStateAcknowledgementException invalid) {
                        throw invalid;
                    } catch (RuntimeException invalid) {
                        throw new PlayerStateAcknowledgementException(invalid);
                    }
                }
                Map<UUID, Set<UUID>> savedIds = new HashMap<>(persistedEntryIds.getOrDefault(accountId, Map.of()));
                Map<UUID, java.time.LocalDateTime> savedVersions = new HashMap<>(persistedEntryVersions.getOrDefault(accountId, Map.of()));
                snapshot.entries.keySet().forEach(id -> savedIds.getOrDefault(id, Set.of()).forEach(savedVersions::remove));
                snapshot.entries.forEach((inventoryId, rows) -> savedIds.put(inventoryId, rows.stream()
                    .map(InventoryEntryModel::getInventoryEntryId).collect(java.util.stream.Collectors.toCollection(HashSet::new))));
                persistedEntryIds.put(accountId, savedIds);
                snapshot.entries.values().forEach(rows -> rows.forEach(row -> savedVersions.put(row.getInventoryEntryId(), entryVersions.get(row.getInventoryEntryId()))));
                 persistedEntryVersions.put(accountId, savedVersions);
                 if (baselineTarget != null) baselineTarget.putAll(snapshot.baseline(ack).entriesByInventoryId());
            }
            pendingSnapshots.remove(accountId, snapshot);
            blockedSnapshots.remove(snapshot.snapshotId);
            acknowledgementBlockedSnapshots.remove(snapshot.snapshotId);
            snapshotAttempts.remove(accountId);
            retryNotBefore.remove(accountId);
        } catch (RuntimeException failure) {
            if (failure instanceof PlayerStateAcknowledgementException) {
                blockedSnapshots.add(snapshot.snapshotId);
                acknowledgementBlockedSnapshots.add(snapshot.snapshotId);
            } else if (failure instanceof InventoryApiException api && api.getStatusCode() >= 400 && api.getStatusCode() < 500
                    && api.getStatusCode() != 408 && api.getStatusCode() != 425
                    && api.getStatusCode() != 429) {
                blockedSnapshots.add(snapshot.snapshotId);
            }
            int attempt = snapshotAttempts.merge(accountId, 1, Integer::sum);
            retryNotBefore.put(accountId, System.nanoTime() + TimeUnit.SECONDS.toNanos(
                Math.min(30L, 1L << Math.min(5, attempt - 1))));
            Logger.warn(LogId.W_5252, accountId, failureReason(failure));
        }
        return true;
    }

    /** HTTP 200 の ACK が壊れていた場合だけ、保存済み台帳の固定 ACK を一度照会します。 */
    private JsonObject saveAndResolveAcknowledgement(PlayerStateSnapshot snapshot) {
        JsonObject acknowledgement;
        try {
            acknowledgement = playerStateRepository.saveSnapshot(snapshot.payload);
        } catch (PlayerStateAcknowledgementException invalid) {
            return recoverAcknowledgement(snapshot, invalid);
        }
        try {
            snapshot.validateAck(acknowledgement);
            return acknowledgement;
        } catch (RuntimeException invalid) {
            return recoverAcknowledgement(snapshot, new PlayerStateAcknowledgementException(invalid));
        }
    }

    private JsonObject recoverAcknowledgement(
        PlayerStateSnapshot snapshot,
        PlayerStateAcknowledgementException invalidAcknowledgement
    ) {
        JsonObject recovered;
        try {
            recovered = playerStateRepository.findCompletedSnapshot(snapshot.snapshotId, snapshot.accountId);
        } catch (RuntimeException lookupFailure) {
            invalidAcknowledgement.addSuppressed(lookupFailure);
            throw invalidAcknowledgement;
        }
        if (recovered == null) throw invalidAcknowledgement;
        try {
            snapshot.validateAck(recovered);
            return recovered;
        } catch (RuntimeException recoveredInvalid) {
            invalidAcknowledgement.addSuppressed(recoveredInvalid);
            throw invalidAcknowledgement;
        }
    }

    /** state monitor内で捕捉する。API・ファイルI/Oを呼ばない。 */
    private PlayerStateSnapshot captureState(PlayerInventoryState state, boolean includePendingInventories) {
        UUID accountId = state.getAccountId();
        List<PlayerStateSection> sections = stateParticipants.stream().map(participant -> participant.apply(accountId))
            .filter(java.util.Objects::nonNull).toList();
        var equipment = itemService.snapshotDirtyEquipmentState(accountId);
        if (!state.isDirty() && equipment.isEmpty() && sections.isEmpty() && !includePendingInventories) return null;
        return new PlayerStateSnapshot(state, equipment, sections, persistedEntryIds.getOrDefault(accountId, Map.of()),
            persistedEntryVersions.getOrDefault(accountId, Map.of()),
            persistedInventoryIds.getOrDefault(accountId, Set.of()),
            persistedLoadoutIds.getOrDefault(accountId, Set.of()),
            itemService.snapshotPendingEquipmentCreationIds(accountId), includePendingInventories);
    }

    /**
     * 外部取引後の正本entryを、次回完成状態保存の期待集合へ反映します。
     * @param state 所有state。呼出元はこのmonitorを保持する
     * @param entryId 照合したentry ID
     * @param authoritative 正本行。消滅・他者移管の場合null
     */
    public void acknowledgeExternalEntry(@NotNull PlayerInventoryState state, @NotNull UUID entryId,
                                        @Nullable InventoryEntryModel authoritative) {
        synchronized (state) {
            Map<UUID, Set<UUID>> known = persistedEntryIds.computeIfAbsent(state.getAccountId(), ignored -> new HashMap<>());
            known.values().forEach(ids -> ids.remove(entryId));
            Map<UUID, java.time.LocalDateTime> versions = persistedEntryVersions.computeIfAbsent(state.getAccountId(), ignored -> new HashMap<>());
            versions.remove(entryId);
            if (authoritative != null && !authoritative.isDeleted()
                && state.findInventoryById(authoritative.getInventoryId()) != null) {
                known.computeIfAbsent(authoritative.getInventoryId(), ignored -> new HashSet<>()).add(entryId);
                versions.put(entryId, authoritative.getUpdatedAt());
            }
        }
    }

    /**
     * 外部取引後に全件取得した通貨inventoryの期待集合を更新します。
     * @param state 所有state
     * @param inventoryId 全件取得したinventory
     * @param authoritative API正本全行。ローカル差分合成前の値
     */
    public void acknowledgeExternalInventory(@NotNull PlayerInventoryState state, @NotNull UUID inventoryId,
                                            @NotNull List<InventoryEntryModel> authoritative) {
        synchronized (state) {
            Map<UUID, java.time.LocalDateTime> versions = persistedEntryVersions.computeIfAbsent(state.getAccountId(), ignored -> new HashMap<>());
            persistedEntryIds.getOrDefault(state.getAccountId(), Map.of()).getOrDefault(inventoryId, Set.of())
                .forEach(versions::remove);
            authoritative.stream().filter(entry -> !entry.isDeleted()).forEach(entry ->
                versions.put(entry.getInventoryEntryId(), entry.getUpdatedAt()));
            persistedEntryIds.computeIfAbsent(state.getAccountId(), ignored -> new HashMap<>()).put(inventoryId,
                authoritative.stream().filter(entry -> !entry.isDeleted()).map(InventoryEntryModel::getInventoryEntryId)
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new)));
        }
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
        synchronized (snapshotSaveLocks.computeIfAbsent(state.getAccountId(), ignored -> new Object())) {
            boolean previousPending = pendingSnapshots.containsKey(state.getAccountId());
            state.markDirty();
            savePlayerStateLocked(state, null);
            if (pendingSnapshots.containsKey(state.getAccountId())) return false;
            // 先行便だけのACKを今回の保存成功と取り違えない。
            if (previousPending) savePlayerStateLocked(state, null);
            // 捕捉後のdirtyは次便の仕事。今回ACK済みの操作失敗ではない。
            return !pendingSnapshots.containsKey(state.getAccountId());
        }
    }

    /**
     * 重要操作の完成状態を保存します。通信失敗または確定した保存失敗は後から再送せず、
     * {@code false} を返して呼び出し側の補償処理へ渡します。SQL 完了後に ACK だけを確定できない場合は
     * snapshot を保持して例外を投げ、二重適用を避けるため補償処理を実行させません。
     * 先行する通常保存が残っていない状態で、account 保存 lane から呼び出してください。
     */
    public boolean saveCriticalNow(@NotNull PlayerInventoryState state) {
        UUID accountId = state.getAccountId();
        synchronized (snapshotSaveLocks.computeIfAbsent(accountId, ignored -> new Object())) {
            state.markDirty();
            savePlayerStateLocked(state, null);
            PlayerStateSnapshot failed = pendingSnapshots.get(accountId);
            if (failed == null) return true;
            if (acknowledgementBlockedSnapshots.contains(failed.snapshotId)) {
                throw new PlayerStateAcknowledgementException(
                    new IllegalStateException("Critical snapshot acknowledgement is unresolved"));
            }
            pendingSnapshots.remove(accountId, failed);
            blockedSnapshots.remove(failed.snapshotId);
            acknowledgementBlockedSnapshots.remove(failed.snapshotId);
            snapshotAttempts.remove(accountId);
            retryNotBefore.remove(accountId);
            return false;
        }
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

    /**
     * 状態破棄時 (プレイヤー退出後など) に内部スナップショットも削除します。
     *
     * @param accountId 対象アカウントID
     */
    public void clearAccount(@NotNull UUID accountId) {
        synchronized (snapshotSaveLocks.computeIfAbsent(accountId, ignored -> new Object())) {
            PlayerInventoryState state = liveStates.get(accountId);
            if (pendingSnapshots.containsKey(accountId) || state != null && hasPendingChanges(state)) return;
            liveStates.remove(accountId);
            persistedEntryIds.remove(accountId);
            persistedEntryVersions.remove(accountId);
            persistedInventoryIds.remove(accountId);
            persistedLoadoutIds.remove(accountId);
            itemService.clearEquipmentState(accountId);
        }
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

    private static @NotNull String failureReason(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
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

}

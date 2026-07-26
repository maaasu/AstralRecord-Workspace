package io.github.maaasu.astralRecord.feature.inventory.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.model.GoldCurrencyCalculator;
import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryDraft;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.model.ItemRarity;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRequirementService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.GameModeChangeGuard;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.storage.model.StorageSortDirection;
import io.github.maaasu.astralRecord.feature.storage.model.StorageSortKey;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * インベントリ機能のビジネスロジックを担うサービス。
 * <p>
 * すべてのインベントリ・装備ロードアウト状態は {@link PlayerInventoryState} に集約され、
 * 本サービスはその state を読み書きするだけで API 通信は行いません。
 * API への反映は {@link InventorySaveCoordinator} のアカウント別保存キューを経由し、
 * 60 秒間隔のオートセーブ、即時保存、ログアウト保存を直列に実行します。
 */
public class InventoryService {
    private static final InventoryProfile DEFAULT_PROFILE = InventoryProfile.GAME;
    private static final String DEFAULT_LOADOUT_NAME = "Default";
    private static final String SLOT_TYPE_HEAD = "HEAD";
    private static final String SLOT_TYPE_CHEST = "CHEST";
    private static final String SLOT_TYPE_LEGS = "LEGS";
    private static final String SLOT_TYPE_FEET = "FEET";
    private static final String SLOT_TYPE_ACCESSORY = "ACCESSORY";
    private static final String STORAGE_ACQUIRED_AT_KEY = "acquiredAt";
    private static final String TOOL_SNAPSHOT_BUILDER_KEY = "builder";
    private static final String TOOL_SNAPSHOT_ADMIN_KEY = "admin";

    private final InventoryRepository inventoryRepository;
    private final EquipmentLoadoutRepository equipmentLoadoutRepository;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final ItemReferenceResolver itemReferenceResolver;
    private final InventoryItemStackResolver itemStackResolver;
    private final InventorySnapshotCodec snapshotCodec;
    private final HotbarRenderer hotbarRenderer;
    private final PlayerInventoryStateRegistry stateRegistry;
    private final InventoryPersistence persistence;
    private final InventorySaveCoordinator saveCoordinator;
    private final InventoryClickGuard clickGuard = new InventoryClickGuard();

    /**
     * インベントリサービスを構築します。
     *
     * @param inventoryRepository インベントリ API リポジトリ（ensureInventory の同期作成・loadout 削除で使用）
     * @param equipmentLoadoutRepository 装備ロードアウト API リポジトリ（ensureActiveLoadout の同期作成で使用）
     * @param itemService アイテム定義サービス
     * @param itemStackFactory ItemStack 生成ヘルパ
     * @param stateRegistry プレイヤー state レジストリ
     * @param persistence 永続化サービス
     * @param saveCoordinator アカウント別保存コーディネーター
     */
    public InventoryService(
        InventoryRepository inventoryRepository,
        EquipmentLoadoutRepository equipmentLoadoutRepository,
        ItemService itemService,
        ItemStackFactory itemStackFactory,
        PlayerInventoryStateRegistry stateRegistry,
        InventoryPersistence persistence,
        InventorySaveCoordinator saveCoordinator
    ) {
        this.inventoryRepository = inventoryRepository;
        this.equipmentLoadoutRepository = equipmentLoadoutRepository;
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
        this.itemStackResolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        this.snapshotCodec = new InventorySnapshotCodec();
        this.hotbarRenderer = new HotbarRenderer(itemStackResolver);
        this.stateRegistry = stateRegistry;
        this.persistence = persistence;
        this.saveCoordinator = saveCoordinator;
    }

    /**
     * クリック連打抑止用のクールタイム管理を返します。
     *
     * @return InventoryClickGuard インスタンス
     */
    public @NotNull InventoryClickGuard getClickGuard() {
        return clickGuard;
    }

    /**
     * プレイヤー退出時などにクリッククールタイム情報を破棄します。
     *
     * @param accountId 対象アカウントID
     */
    public void clearClickGuard(@NotNull UUID accountId) {
        clickGuard.clear(accountId);
    }

    /**
     * ロード処理などの構成に使用する永続化サービスを返します。
     * 即時保存は永続化サービスを直接呼ばず {@link #saveNow(UUID)} を使用してください。
     *
     * @return InventoryPersistence
     */
    public @NotNull InventoryPersistence getPersistence() {
        return persistence;
    }

    /**
     * プレイヤー state レジストリを返します。
     *
     * @return PlayerInventoryStateRegistry
     */
    public @NotNull PlayerInventoryStateRegistry getStateRegistry() {
        return stateRegistry;
    }

    /**
     * ステータス再計算後の BAG 利用可能スロット数を反映します。
     * 容量外 entry は削除せずに表示し続けますが、新規追加先にはしません。
     *
     * @param astPlayer 対象プレイヤー
     * @param slotCount ステータスで確定した所持可能スロット数
     */
    public void applyBagSlotCapacity(@NotNull AstPlayer astPlayer, double slotCount) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) return;
        int capacity = Math.max(0, (int) Math.floor(slotCount));
        if (state.setBagSlotCapacity(capacity)) {
            applyInventoryToGuiInternal(astPlayer, InventoryType.BAG, false);
        }
    }

    // ---------------------------------------------------------------
    // state helpers
    // ---------------------------------------------------------------

    private @Nullable PlayerInventoryState getState(@NotNull UUID accountId) {
        return stateRegistry.get(accountId);
    }

    private @NotNull PlayerInventoryState requireState(@NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            throw new IllegalStateException("Inventory state not loaded for account " + accountId);
        }
        return state;
    }

    public List<InventoryModel> getInventories(@NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        return state == null ? List.of() : state.snapshotInventories();
    }

    public @Nullable InventoryModel getInventory(@NotNull UUID inventoryId) {
        for (PlayerInventoryState state : stateRegistry.all()) {
            InventoryModel inventory = state.findInventoryById(inventoryId);
            if (inventory != null) {
                return inventory;
            }
        }
        return null;
    }

    public @NotNull List<InventoryEntryModel> getEntries(@NotNull UUID inventoryId) {
        for (PlayerInventoryState state : stateRegistry.all()) {
            if (state.findInventoryById(inventoryId) != null) {
                return state.snapshotEntries(inventoryId);
            }
        }
        return List.of();
    }

    /**
     * 指定 profile / 種別のインベントリを取得し、未存在の場合は API へ同期的に作成します。
     * <p>
     * 通信失敗時は例外を投げます。呼び出し側は state が読み込まれているプレイヤーに対してのみ使用してください。
     *
     * @param state 対象 state
     * @param inventoryType 種別
     * @param slotCapacity スロット数（nullable）
     * @param createdBy 作成者
     * @param profile プロファイル
     * @return 既存または新規作成したインベントリ
     */
    private @NotNull InventoryModel ensureInventory(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryType inventoryType,
        @Nullable Integer slotCapacity,
        @NotNull UUID createdBy,
        @NotNull InventoryProfile profile
    ) {
        InventoryModel cached = state.findInventory(profile, inventoryType);
        if (cached != null) {
            return cached;
        }
        InventoryModel created = inventoryRepository.create(
            state.getAccountId(),
            inventoryType,
            slotCapacity,
            createdBy,
            profile,
            null
        );
        state.putInventory(created);
        state.replaceEntriesFromLoad(created.getInventoryId(), List.of());
        return created;
    }

    private @NotNull InventoryModel ensureInventory(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryType inventoryType
    ) {
        return ensureInventory(
            state,
            inventoryType,
            resolveSlotCapacity(inventoryType),
            state.getAccountId(),
            DEFAULT_PROFILE
        );
    }

    // ---------------------------------------------------------------
    // add items
    // ---------------------------------------------------------------

    /**
     * 通常インベントリへアイテムを追加します。
     * <p>
     * 追加先は model のカテゴリに応じて自動判定し、対応するインベントリ種別へ entry を追加します。
     * EQUIPMENT / RUNE は API でインスタンスを生成（同期）した後、entry を state に追加します。
     * 永続化は次回オートセーブで行われます。
     *
     * @param astPlayer 追加対象プレイヤー
     * @param model 追加アイテム定義
     * @param amount 追加希望個数（1未満は1として扱う）
     * @return 実際に追加できた個数
     */
    public int addItemToNormalInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemModel model,
        int amount
    ) {
        return addItemToNormalInventory(astPlayer, model, amount, "command");
    }

    /**
     * 通常インベントリへアイテムを追加します。
     *
     * @param astPlayer 追加先プレイヤー
     * @param model     追加するアイテム
     * @param amount    追加数
     * @param source    インスタンス生成元
     * @return 実際に追加できた数
     */
    public int addItemToNormalInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemModel model,
        int amount,
        @NotNull String source
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return 0;
        }
        int safeAmount = Math.max(1, amount);
        InventoryType inventoryType = resolveTargetInventoryType(model);
        InventoryModel targetInventory = ensureInventory(state, inventoryType);
        Set<Integer> usedSlots = collectUsedSlots(state, targetInventory);

        int granted = switch (ItemCategory.fromApiValue(model.getCategory())) {
            case EQUIPMENT -> addInstanceItems(state, targetInventory, model, safeAmount, InventoryInstanceType.EQUIPMENT, usedSlots, source);
            case RUNE -> addInstanceItems(state, targetInventory, model, safeAmount, InventoryInstanceType.RUNE, usedSlots, source);
            default -> addStackedItems(state, targetInventory, model, safeAmount, usedSlots);
        };
        if (granted > 0) {
            autoSwitchDisplayedInventory(astPlayer, inventoryType);
        }
        return granted;
    }

    public int addPreparedInstanceToNormalInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemModel model,
        @NotNull InventoryInstanceType instanceType,
        @NotNull UUID instanceId
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return 0;
        }

        InventoryType inventoryType = resolveTargetInventoryType(model);
        InventoryModel targetInventory = ensureInventory(state, inventoryType);
        Set<Integer> usedSlots = collectUsedSlots(state, targetInventory);
        Integer slot = findNextFreeSlot(targetInventory, usedSlots);
        if (slot == null) {
            return 0;
        }

        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(targetInventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .toList());
        entries.add(newEntry(
            targetInventory.getInventoryId(),
            slot,
            model.getCategory(),
            null,
            instanceType.getCode(),
            instanceId,
            1L,
            null,
            state.getAccountId()
        ));
        state.replaceEntries(targetInventory.getInventoryId(), entries);
        autoSwitchDisplayedInventory(astPlayer, inventoryType);
        return 1;
    }

    /**
     * API I/O 済みのインスタンスを含む報酬を、1 回のローカル変更として通常インベントリへ追加します。
     * 装備品とルーンについて、このメソッド内ではインスタンス生成 API を呼び出しません。
     *
     * @param astPlayer 追加対象プレイヤー
     * @param rewards 事前解決済みの報酬
     * @return 追加した entry の差分。全量を追加できない場合は {@code null}
     */
    public @Nullable InventoryGrantReceipt addPreparedRewardsToNormalInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull List<PreparedInventoryReward> rewards
    ) {
        if (rewards.isEmpty()) {
            return new InventoryGrantReceipt(astPlayer.getAccount().getUuid(), List.of());
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }

        Map<UUID, List<InventoryEntryModel>> beforeEntries = new LinkedHashMap<>();
        InventoryType beforeDisplayedType;
        Set<InventoryType> changedTypes = new HashSet<>();
        synchronized (state) {
            beforeDisplayedType = state.getDisplayedType();
            for (PreparedInventoryReward reward : rewards) {
                if (reward.amount() <= 0) {
                    continue;
                }
                InventoryType inventoryType = resolveTargetInventoryType(reward.model());
                InventoryModel targetInventory = ensureInventory(state, inventoryType);
                if (inventoryType == InventoryType.CURRENCY) {
                    normalizeCurrencyEntries(state, targetInventory);
                }
            }
            for (InventoryModel inventory : state.snapshotInventories()) {
                beforeEntries.put(inventory.getInventoryId(), state.snapshotEntries(inventory.getInventoryId()));
            }

            boolean succeeded = true;
            Set<UUID> preparedInstanceIds = new HashSet<>();
            for (PreparedInventoryReward reward : rewards) {
                if (reward.amount() <= 0) {
                    continue;
                }
                ItemCategory category = ItemCategory.fromApiValue(reward.model().getCategory());
                InventoryType inventoryType = resolveTargetInventoryType(reward.model());
                InventoryModel targetInventory = ensureInventory(state, inventoryType);
                changedTypes.add(inventoryType);

                if (category == ItemCategory.EQUIPMENT || category == ItemCategory.RUNE) {
                    InventoryInstanceType expectedType = category == ItemCategory.EQUIPMENT
                        ? InventoryInstanceType.EQUIPMENT
                        : InventoryInstanceType.RUNE;
                    if (reward.instances().size() != reward.amount()
                        || reward.instances().stream().anyMatch(instance -> instance.instanceType() != expectedType)) {
                        succeeded = false;
                        break;
                    }
                    for (PreparedInventoryInstance instance : reward.instances()) {
                        if (!preparedInstanceIds.add(instance.instanceId())
                            || !addPreparedInstanceEntry(
                            state,
                            targetInventory,
                            reward.model(),
                            instance.instanceType(),
                            instance.instanceId()
                        )) {
                            succeeded = false;
                            break;
                        }
                    }
                } else {
                    if (!reward.instances().isEmpty()) {
                        succeeded = false;
                        break;
                    }
                    Set<Integer> usedSlots = collectUsedSlots(state, targetInventory);
                    succeeded = addStackedItems(
                        state,
                        targetInventory,
                        reward.model(),
                        reward.amount(),
                        usedSlots
                    ) == reward.amount();
                }
                if (!succeeded) {
                    break;
                }
            }

            if (!succeeded) {
                restoreImmediateGrantState(state, beforeEntries, beforeDisplayedType);
                return null;
            }

            List<InventoryGrantMutation> mutations = collectGrantMutations(state, beforeEntries);
            for (InventoryType changedType : changedTypes) {
                autoSwitchDisplayedInventory(astPlayer, changedType);
            }
            return new InventoryGrantReceipt(state.getAccountId(), mutations);
        }
    }

    /**
     * 指定した受取票に含まれる増加分だけを現在の state から取り除きます。
     * 同じ entry に後から追加された数量や、無関係な entry は保持します。
     *
     * @param receipt 補償対象の受取票
     * @return 全差分を補償できた場合は {@code true}
     */
    public boolean rollbackPreparedRewards(@NotNull InventoryGrantReceipt receipt) {
        if (receipt.mutations().isEmpty()) {
            return true;
        }
        PlayerInventoryState state = getState(receipt.accountId());
        if (state == null) {
            return false;
        }
        synchronized (state) {
            Map<UUID, List<InventoryEntryModel>> currentByInventory = new LinkedHashMap<>();
            for (InventoryModel inventory : state.snapshotInventories()) {
                currentByInventory.put(inventory.getInventoryId(), state.snapshotEntries(inventory.getInventoryId()));
            }

            List<LocatedGrantMutation> located = new ArrayList<>();
            Set<UUID> locatedEntryIds = new HashSet<>();
            for (InventoryGrantMutation mutation : receipt.mutations()) {
                LocatedGrantMutation match = locateGrantMutation(currentByInventory, mutation);
                if (mutation.quantity() <= 0L || match == null
                    || !locatedEntryIds.add(match.entry().getInventoryEntryId())
                    || match.entry().getQuantity() < mutation.quantity()) {
                    return false;
                }
                located.add(match);
            }

            Map<UUID, List<LocatedGrantMutation>> byInventory = new LinkedHashMap<>();
            for (LocatedGrantMutation mutation : located) {
                byInventory.computeIfAbsent(mutation.inventoryId(), ignored -> new ArrayList<>()).add(mutation);
            }
            for (Map.Entry<UUID, List<LocatedGrantMutation>> inventoryMutations : byInventory.entrySet()) {
                List<InventoryEntryModel> entries = new ArrayList<>(
                    currentByInventory.getOrDefault(inventoryMutations.getKey(), List.of())
                );
                for (LocatedGrantMutation locatedMutation : inventoryMutations.getValue()) {
                    InventoryGrantMutation mutation = locatedMutation.mutation();
                    for (int index = 0; index < entries.size(); index++) {
                        InventoryEntryModel entry = entries.get(index);
                        if (!entry.getInventoryEntryId().equals(locatedMutation.entry().getInventoryEntryId())) {
                            continue;
                        }
                        long remaining = entry.getQuantity() - mutation.quantity();
                        if (remaining == 0L) {
                            entries.remove(index);
                        } else {
                            entries.set(index, withQuantity(entry, remaining, state.getAccountId()));
                        }
                        break;
                    }
                }
                state.replaceEntries(inventoryMutations.getKey(), entries);
            }
            return true;
        }
    }

    private boolean addPreparedInstanceEntry(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull ItemModel model,
        @NotNull InventoryInstanceType instanceType,
        @NotNull UUID instanceId
    ) {
        Set<Integer> usedSlots = collectUsedSlots(state, inventory);
        Integer slot = findNextFreeSlot(inventory, usedSlots);
        if (slot == null) {
            return false;
        }
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .toList());
        entries.add(newEntry(
            inventory.getInventoryId(),
            slot,
            model.getCategory(),
            null,
            instanceType.getCode(),
            instanceId,
            1L,
            null,
            state.getAccountId()
        ));
        state.replaceEntries(inventory.getInventoryId(), entries);
        return true;
    }

    private void restoreImmediateGrantState(
        @NotNull PlayerInventoryState state,
        @NotNull Map<UUID, List<InventoryEntryModel>> beforeEntries,
        @NotNull InventoryType beforeDisplayedType
    ) {
        for (InventoryModel inventory : state.snapshotInventories()) {
            state.replaceEntries(
                inventory.getInventoryId(),
                beforeEntries.getOrDefault(inventory.getInventoryId(), List.of())
            );
        }
        state.setDisplayedType(beforeDisplayedType);
    }

    private @NotNull List<InventoryGrantMutation> collectGrantMutations(
        @NotNull PlayerInventoryState state,
        @NotNull Map<UUID, List<InventoryEntryModel>> beforeEntries
    ) {
        List<InventoryGrantMutation> mutations = new ArrayList<>();
        for (InventoryModel inventory : state.snapshotInventories()) {
            Map<UUID, InventoryEntryModel> beforeById = new HashMap<>();
            for (InventoryEntryModel entry : beforeEntries.getOrDefault(inventory.getInventoryId(), List.of())) {
                beforeById.put(entry.getInventoryEntryId(), entry);
            }
            for (InventoryEntryModel entry : state.snapshotEntries(inventory.getInventoryId())) {
                InventoryEntryModel before = beforeById.get(entry.getInventoryEntryId());
                long beforeQuantity = before == null ? 0L : before.getQuantity();
                long added = entry.getQuantity() - beforeQuantity;
                if (added > 0L) {
                    mutations.add(new InventoryGrantMutation(
                        entry.getInventoryEntryId(),
                        entry.getInstanceId(),
                        added
                    ));
                }
            }
        }
        return List.copyOf(mutations);
    }

    private @Nullable LocatedGrantMutation locateGrantMutation(
        @NotNull Map<UUID, List<InventoryEntryModel>> currentByInventory,
        @NotNull InventoryGrantMutation mutation
    ) {
        for (Map.Entry<UUID, List<InventoryEntryModel>> inventory : currentByInventory.entrySet()) {
            for (InventoryEntryModel entry : inventory.getValue()) {
                boolean matches = mutation.instanceId() == null
                    ? entry.getInventoryEntryId().equals(mutation.entryId())
                    : mutation.instanceId().equals(entry.getInstanceId());
                if (matches) {
                    return new LocatedGrantMutation(inventory.getKey(), entry, mutation);
                }
            }
        }
        return null;
    }

    private int addInstanceItems(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull ItemModel model,
        int amount,
        @NotNull InventoryInstanceType instanceType,
        @NotNull Set<Integer> usedSlots,
        @NotNull String source
    ) {
        UUID accountId = state.getAccountId();
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .toList());
        int granted = 0;
        for (int i = 0; i < amount; i++) {
            Integer slot = findNextFreeSlot(inventory, usedSlots);
            if (slot == null) {
                break;
            }
            UUID instanceId = createInstanceId(model, accountId, instanceType, source);
            if (instanceId == null) {
                break;
            }
            entries.add(newEntry(inventory.getInventoryId(), slot, model.getCategory(), null,
                instanceType.getCode(), instanceId, 1L, null, accountId));
            usedSlots.add(slot);
            granted++;
        }
        if (granted > 0) {
            state.replaceEntries(inventory.getInventoryId(), entries);
        }
        return granted;
    }

    private int addStackedItems(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull ItemModel model,
        int amount,
        @NotNull Set<Integer> usedSlots
    ) {
        UUID accountId = state.getAccountId();
        int maxStack = Math.max(1, model.getMaxStack());
        boolean unlimitedStack = inventory.getInventoryType() == InventoryType.CURRENCY;
        int capacity = inventoryCapacity(inventory);
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .toList());
        if (unlimitedStack) {
            entries = new ArrayList<>(normalizeCurrencyEntries(state, inventory));
            usedSlots.clear();
            usedSlots.addAll(collectUsedSlots(state, inventory));
        }
        int remaining = amount;
        int granted = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (remaining <= 0) {
                break;
            }
            InventoryEntryModel entry = entries.get(index);
            Integer entrySlot = entry.getSlotIndex();
            if (!unlimitedStack && (entrySlot == null
                || !NormalInventoryLayout.isManagedSlot(entrySlot, capacity))) {
                continue;
            }
            if (!isStackableEntry(entry, model, maxStack)) {
                continue;
            }
            int addAmount = unlimitedStack
                ? remaining
                : (int) Math.min(maxStack - entry.getQuantity(), remaining);
            if (addAmount <= 0) {
                continue;
            }
            entries.set(index, withQuantity(entry, entry.getQuantity() + addAmount, accountId));
            granted += addAmount;
            remaining -= addAmount;
        }
        while (remaining > 0) {
            Integer slot = findNextFreeSlot(inventory, usedSlots);
            if (slot == null) {
                break;
            }
            int stackAmount = unlimitedStack ? remaining : Math.min(maxStack, remaining);
            entries.add(newEntry(inventory.getInventoryId(), slot, model.getCategory(), model.getId(),
                null, null, stackAmount, null, accountId));
            usedSlots.add(slot);
            granted += stackAmount;
            remaining -= stackAmount;
        }
        if (granted > 0) {
            state.replaceEntries(inventory.getInventoryId(), entries);
        }
        return granted;
    }

    // ---------------------------------------------------------------
    // Tool snapshot
    // ---------------------------------------------------------------

    public void saveToolInventorySnapshot(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        String snapshotKey = resolveToolSnapshotKey(astPlayer.getAccount().getMode());
        if (snapshotKey == null) {
            return;
        }
        InventoryModel inventory = ensureInventory(
            state,
            InventoryType.BAG,
            null,
            state.getAccountId(),
            InventoryProfile.BUILDER
        );
        ItemStack[] contents = astPlayer.getBukkit().getInventory().getContents();
        JsonObject snapshots = parseToolSnapshots(inventory.getMetadataJson());
        JsonObject snapshot = JsonParser.parseString(
            snapshotCodec.encodeBuilder(contents, astPlayer.getBukkit().getGameMode())
        ).getAsJsonObject();
        snapshots.add(snapshotKey, snapshot);
        state.updateInventoryMetadata(inventory.getInventoryId(), snapshots.toString(), state.getAccountId());
    }

    public void saveBuilderInventorySnapshot(@NotNull AstPlayer astPlayer) {
        saveToolInventorySnapshot(astPlayer);
    }

    public void applyToolInventoryToGui(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        resetGuiInteractionState(state);
        clearClickGuard(state.getAccountId());
        clearGuiInventory(astPlayer.getBukkit());
        InventoryModel inventory = state.findInventory(InventoryProfile.BUILDER, InventoryType.BAG);
        if (inventory != null) {
            applyToolSnapshot(astPlayer, inventory.getMetadataJson(), astPlayer.getAccount().getMode());
        }
    }

    public void applyBuilderInventoryToGui(@NotNull AstPlayer astPlayer) {
        applyToolInventoryToGui(astPlayer);
    }

    private void applyToolSnapshot(
        @NotNull AstPlayer astPlayer,
        @Nullable String metadataJson,
        @NotNull AccountMode mode
    ) {
        String snapshotJson = resolveToolSnapshotJson(metadataJson, mode);
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return;
        }
        InventorySnapshotCodec.BuilderSnapshot snapshot = snapshotCodec.decodeBuilder(snapshotJson);
        if (snapshot == null) {
            return;
        }
        astPlayer.getBukkit().getInventory().setContents(snapshot.contents());
        if (snapshot.gameMode() != null) {
            GameModeChangeGuard.setGameMode(astPlayer.getBukkit(), snapshot.gameMode());
        }
        astPlayer.getBukkit().updateInventory();
    }

    private @Nullable String resolveToolSnapshotKey(@NotNull AccountMode mode) {
        return switch (mode) {
            case BUILDER -> TOOL_SNAPSHOT_BUILDER_KEY;
            case ADMIN -> TOOL_SNAPSHOT_ADMIN_KEY;
            default -> null;
        };
    }

    private @NotNull JsonObject parseToolSnapshots(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonObject parsed = JsonParser.parseString(metadataJson).getAsJsonObject();
            if (isLegacyToolSnapshot(parsed)) {
                JsonObject migrated = new JsonObject();
                migrated.add(TOOL_SNAPSHOT_BUILDER_KEY, parsed.deepCopy());
                return migrated;
            }
            return parsed.deepCopy();
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            return new JsonObject();
        }
    }

    private @Nullable String resolveToolSnapshotJson(
        @Nullable String metadataJson,
        @NotNull AccountMode mode
    ) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonObject parsed = JsonParser.parseString(metadataJson).getAsJsonObject();
            if (isLegacyToolSnapshot(parsed)) {
                return metadataJson;
            }
            String snapshotKey = resolveToolSnapshotKey(mode);
            if (snapshotKey != null
                && parsed.has(snapshotKey)
                && parsed.get(snapshotKey).isJsonObject()) {
                return parsed.getAsJsonObject(snapshotKey).toString();
            }
            if (mode == AccountMode.ADMIN
                && parsed.has(TOOL_SNAPSHOT_BUILDER_KEY)
                && parsed.get(TOOL_SNAPSHOT_BUILDER_KEY).isJsonObject()) {
                return parsed.getAsJsonObject(TOOL_SNAPSHOT_BUILDER_KEY).toString();
            }
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            return null;
        }
        return null;
    }

    private boolean isLegacyToolSnapshot(@NotNull JsonObject object) {
        return object.has("format") && object.has("contents");
    }

    // ---------------------------------------------------------------
    // GUI rendering
    // ---------------------------------------------------------------

    public void applyInventoriesToGui(@NotNull AstPlayer astPlayer) {
        applyInventoryToGuiInternal(astPlayer, InventoryType.BAG, true);
        if (!applyActiveEquipmentLoadoutToGui(astPlayer)) {
            applyEquipSlotInventoryToGui(astPlayer);
            applyAccessorySlotInventoryToGui(astPlayer);
        }
        applyHotbarInventoryToGui(astPlayer);
    }

    /**
     * ツールモードから通常プレイヤーモードへ戻る際に GUI インベントリを再反映します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void applyInventoriesToGuiForModeSwitch(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        resetGuiInteractionState(state);
        clearClickGuard(state.getAccountId());
        applyInventoryToGuiInternal(astPlayer, state.getDisplayedType(), false);
    }

    /**
     * ログイン直後の初期描画専用の適用処理です。
     * <p>
     * 退出直前の Bukkit 側スナップショットを取り込む前にロード済み state を描画し、
     * ロード完了前の空スナップショットで state を上書きしないようにします。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void applyInventoriesToGuiOnJoin(@NotNull AstPlayer astPlayer) {
        // Join 直後は以前のセッションで残った見た目 ItemStack を全消去してから正本を反映する。
        clearGuiInventory(astPlayer.getBukkit());
        applyInventoryToGuiInternal(astPlayer, InventoryType.BAG, false);
        if (!applyActiveEquipmentLoadoutToGui(astPlayer)) {
            applyEquipSlotInventoryToGui(astPlayer);
            applyAccessorySlotInventoryToGui(astPlayer);
        }
        applyHotbarInventoryToGui(astPlayer);
        purgeUnknownAstralItemsFromReflectedSlots(astPlayer);
    }

    public void applyInventoryToGui(@NotNull AstPlayer astPlayer, @NotNull InventoryType inventoryType) {
        applyInventoryToGuiInternal(astPlayer, inventoryType, true);
    }

    private void applyInventoryToGuiInternal(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryType inventoryType,
        boolean captureCurrentSnapshots
    ) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        if (inventoryType == InventoryType.CURRENCY) {
            clearManagedStorageSlots(astPlayer.getBukkit());
            astPlayer.getBukkit().updateInventory();
            return;
        }
        if (captureCurrentSnapshots) {
            saveEquipSlotSnapshot(astPlayer);
            saveAccessorySlotSnapshot(astPlayer);
            syncCurrentEquipmentState(astPlayer);
        }
        state.setDisplayedType(InventoryType.BAG);

        InventoryModel selectedInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.BAG);
        applyInventoryToBukkit(astPlayer.getBukkit(), state, selectedInventory);

        if (!applyActiveEquipmentLoadoutToGui(astPlayer)) {
            applyEquipSlotInventoryToGui(astPlayer);
            applyAccessorySlotInventoryToGui(astPlayer);
        }
        applyHotbarInventoryToGui(astPlayer);
    }

    private void applyInventoryToBukkit(
        @NotNull Player bukkitPlayer,
        @NotNull PlayerInventoryState state,
        @Nullable InventoryModel inventory
    ) {
        if (inventory == null || !inventory.isEnabled()) {
            clearManagedStorageSlots(bukkitPlayer);
            return;
        }
        if (inventory.getInventoryType() == InventoryType.CURRENCY) {
            clearManagedStorageSlots(bukkitPlayer);
            return;
        }
        if (inventory.getInventoryType().isSlotted()) {
            applySlottedInventory(bukkitPlayer, state, inventory);
        }
    }

    private void applySlottedInventory(
        @NotNull Player bukkitPlayer,
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory
    ) {
        List<InventoryEntryModel> entries = state.snapshotEntries(inventory.getInventoryId());
        PlayerInventory playerInventory = bukkitPlayer.getInventory();
        Map<Integer, ItemStack> itemByGuiSlot = new HashMap<>();
        ItemStack filler = createManagedSlotFiller();
        int capacity = inventoryCapacity(inventory);
        int displayCapacity = NormalInventoryLayout.displayCapacity(entries, capacity);
        int scrollRow = Math.min(state.getBagScrollRow(), NormalInventoryLayout.maxScrollRow(displayCapacity));
        state.setBagScrollRow(scrollRow);

        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex == null || !NormalInventoryLayout.isManagedSlot(slotIndex, displayCapacity) || entry.isDeleted()) {
                continue;
            }
            int guiSlotIndex = NormalInventoryLayout.toGuiSlotIndex(slotIndex, scrollRow);
            if (guiSlotIndex < 0 || guiSlotIndex >= playerInventory.getStorageContents().length) {
                continue;
            }
            ItemStack itemStack = itemStackResolver.resolve(entry);
            if (itemStack == null) {
                continue;
            }
            if (slotIndex > capacity) {
                itemStack = createOverflowItem(itemStack);
            }
            itemByGuiSlot.put(guiSlotIndex, itemStack);
        }
        for (int guiSlot = NormalInventoryLayout.GUI_SLOT_START;
             guiSlot <= NormalInventoryLayout.GUI_SLOT_END;
             guiSlot++) {
            if (!NormalInventoryLayout.isManagedGuiSlot(guiSlot)) {
                continue;
            }
            int dbSlot = NormalInventoryLayout.toDbSlotIndex(guiSlot, scrollRow);
            ItemStack emptySlot = dbSlot > capacity ? createOverflowSlotFiller() : filler;
            setStorageItemIfChanged(playerInventory, guiSlot, itemByGuiSlot.getOrDefault(guiSlot, emptySlot));
        }
        setStorageItemIfChanged(playerInventory, NormalInventoryLayout.SCROLL_UP_GUI_SLOT,
            createScrollIcon(true, scrollRow > 0));
        setStorageItemIfChanged(playerInventory, NormalInventoryLayout.INFO_GUI_SLOT,
            createInventoryInfoIcon(entries, capacity, displayCapacity, scrollRow));
        setStorageItemIfChanged(playerInventory, NormalInventoryLayout.SCROLL_DOWN_GUI_SLOT,
            createScrollIcon(false, scrollRow < NormalInventoryLayout.maxScrollRow(displayCapacity)));
    }

    public @NotNull InventoryType getDisplayedInventoryType(@NotNull UUID accountId) {
        return InventoryType.BAG;
    }

    /**
     * BAG 右端列のスクロール・情報操作を処理します。
     *
     * @param astPlayer 操作したプレイヤー
     * @param bukkitSlot Bukkit PlayerInventory のスロット番号
     * @return 制御スロットとして処理した場合 true
     */
    public boolean handleInventoryControlClick(@NotNull AstPlayer astPlayer, int bukkitSlot) {
        if (bukkitSlot != NormalInventoryLayout.SCROLL_UP_GUI_SLOT
            && bukkitSlot != NormalInventoryLayout.INFO_GUI_SLOT
            && bukkitSlot != NormalInventoryLayout.SCROLL_DOWN_GUI_SLOT) {
            return false;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return true;
        }
        if (bukkitSlot == NormalInventoryLayout.INFO_GUI_SLOT) {
            return true;
        }
        InventoryModel bag = state.findInventory(DEFAULT_PROFILE, InventoryType.BAG);
        int capacity = state.getBagSlotCapacity();
        List<InventoryEntryModel> entries = bag == null
            ? List.of()
            : state.snapshotEntries(bag.getInventoryId());
        int displayCapacity = NormalInventoryLayout.displayCapacity(entries, capacity);
        int current = Math.min(state.getBagScrollRow(), NormalInventoryLayout.maxScrollRow(displayCapacity));
        int next = bukkitSlot == NormalInventoryLayout.SCROLL_UP_GUI_SLOT
            ? Math.max(0, current - 1)
            : Math.min(NormalInventoryLayout.maxScrollRow(displayCapacity), current + 1);
        if (next != current
            && clickGuard.tryAcquire(state.getAccountId(), InventoryClickGuard.ClickAction.INVENTORY_SWITCH)) {
            state.setBagScrollRow(next);
            applyDisplayedInventoryToGui(astPlayer);
            astPlayer.getBukkit().updateInventory();
        }
        return true;
    }

    public void clearGuiInventory(@NotNull AstPlayer astPlayer) {
        clearGuiInventory(astPlayer.getBukkit());
        astPlayer.getBukkit().updateInventory();
    }

    /**
     * 指定種別のデフォルトインベントリ内アイテムを GUI 表示用 ItemStack に変換します。
     *
     * @param accountId 対象アカウントID
     * @param inventoryType 取得するインベントリ種別
     * @return 表示可能な ItemStack 一覧
     */
    public @NotNull List<ItemStack> getInventoryItemStacks(
        @NotNull UUID accountId,
        @NotNull InventoryType inventoryType
    ) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return List.of();
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, inventoryType);
        if (inventory == null || !inventory.isEnabled()) {
            if (inventoryType == InventoryType.CURRENCY) {
                return goldCurrencyDisplay(0L);
            }
            return List.of();
        }
        List<InventoryEntryModel> entries = inventoryType == InventoryType.CURRENCY
            ? normalizeCurrencyEntries(state, inventory)
            : state.snapshotEntries(inventory.getInventoryId()).stream()
                .filter(entry -> !entry.isDeleted())
                .toList();
        List<ItemStack> itemStacks = entries.stream()
            .filter(entry -> !entry.isDeleted())
            .sorted(Comparator.<InventoryEntryModel, Integer>comparing(
                entry -> entry.getSlotIndex() == null ? Integer.MAX_VALUE : entry.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .map(entry -> inventoryType == InventoryType.CURRENCY
                ? itemStackResolver.resolveCurrencyDisplay(entry)
                : itemStackResolver.resolve(entry))
            .filter(itemStack -> itemStack != null && itemStack.getType() != Material.AIR)
            .toList();
        if (inventoryType != InventoryType.CURRENCY
            || hasCurrencyEntry(entries, ItemService.DEFAULT_CURRENCY_ITEM_ID)
            || hasCurrencyEntry(entries, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID)) {
            return itemStacks;
        }

        List<ItemStack> withGold = new ArrayList<>(itemStacks.size() + 1);
        withGold.addAll(goldCurrencyDisplay(0L));
        withGold.addAll(itemStacks);
        return withGold;
    }

    /**
     * ストレージ GUI の表示候補を取得します。
     *
     * @param accountId 対象アカウントID
     * @param options フィルタと並び順
     * @return 表示条件に一致したストレージ entry 一覧
     */
    public @NotNull List<StorageViewEntry> getStorageViewEntries(
        @NotNull UUID accountId,
        @NotNull StorageViewOptions options
    ) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return List.of();
        }
        InventoryModel storageInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.STORAGE);
        if (storageInventory == null || !storageInventory.isEnabled()) {
            return List.of();
        }

        return state.snapshotEntries(storageInventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .map(this::toStorageViewEntry)
            .filter(entry -> entry != null)
            .filter(entry -> matchesStorageFilters(entry, options))
            .sorted(storageComparator(options))
            .toList();
    }

    /**
     * BAG またはホットバーのアイテムをストレージへ収納します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot Bukkit PlayerInventory のスロット番号
     * @param amount 収納数。0 以下は全数扱い
     * @return 実際に収納した個数
     */
    public int moveOwnedItemToStorage(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot,
        int amount
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return 0;
        }
        boolean hotbarSlot = sourceBukkitSlot >= 0 && sourceBukkitSlot <= 8;
        InventoryEntryModel sourceEntry = hotbarSlot
            ? findHotbarEntryBySlot(state, sourceBukkitSlot + 1)
            : findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
        if (sourceEntry == null) {
            return 0;
        }
        ItemStack sourceItem = itemStackResolver.resolve(sourceEntry);
        if (sourceItem == null || sourceItem.getType() == Material.AIR) {
            return 0;
        }

        boolean takeAll = amount <= 0
            || amount >= sourceItem.getAmount()
            || sourceEntry.getInstanceType() != null;
        int movedAmount = takeAll ? sourceItem.getAmount() : Math.max(1, amount);
        InventoryModel storageInventory = ensureInventory(
            state,
            InventoryType.STORAGE,
            null,
            state.getAccountId(),
            DEFAULT_PROFILE
        );
        List<InventoryEntryModel> storageEntries = new ArrayList<>(state.snapshotEntries(storageInventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .toList());
        int stackableIndex = findStackableStorageEntryIndex(storageEntries, sourceEntry);
        if (stackableIndex >= 0) {
            InventoryEntryModel existing = storageEntries.get(stackableIndex);
            storageEntries.set(
                stackableIndex,
                withQuantity(existing, existing.getQuantity() + movedAmount, state.getAccountId())
            );
        } else {
            storageEntries.add(copyEntryToStorage(sourceEntry, storageInventory.getInventoryId(), movedAmount, state.getAccountId()));
        }
        state.replaceEntries(storageInventory.getInventoryId(), storageEntries);

        if (takeAll) {
            if (hotbarSlot) {
                removeHotbarEntryAfterMove(state, sourceEntry);
            } else {
                removeDisplayedEntryAfterMove(state, sourceEntry);
            }
        } else {
            reduceDisplayedEntryQuantity(state, sourceEntry, sourceEntry.getQuantity() - movedAmount);
        }
        requestManagedInventoryUiRefresh(astPlayer, hotbarSlot);
        return movedAmount;
    }

    /**
     * 指定スロットと同一の通常アイテムを BAG・ホットバー全体からストレージへ収納します。
     * 装備・ルーンなど個体 ID を持つアイテムは、指定スロットの1個だけを移動します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 基準にする Bukkit PlayerInventory のスロット番号
     * @return 実際に収納した個数
     */
    public int moveAllOwnedMatchingItemsToStorage(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return 0;
        }
        OwnedItemBatch batch = collectOwnedItemBatch(state, sourceBukkitSlot);
        if (batch == null) {
            return 0;
        }
        if (!isStackableByItemId(batch.sourceEntry())) {
            return moveOwnedItemToStorage(astPlayer, sourceBukkitSlot, 0);
        }

        InventoryModel storageInventory = ensureInventory(
            state,
            InventoryType.STORAGE,
            null,
            state.getAccountId(),
            DEFAULT_PROFILE
        );
        List<InventoryEntryModel> storageEntries = new ArrayList<>(state.snapshotEntries(storageInventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .toList());
        int stackableIndex = findStackableStorageEntryIndex(storageEntries, batch.sourceEntry());
        if (stackableIndex >= 0) {
            InventoryEntryModel existing = storageEntries.get(stackableIndex);
            storageEntries.set(
                stackableIndex,
                withQuantity(existing, existing.getQuantity() + batch.amount(), state.getAccountId())
            );
        } else {
            storageEntries.add(copyEntryToStorage(
                batch.sourceEntry(),
                storageInventory.getInventoryId(),
                batch.amount(),
                state.getAccountId()
            ));
        }
        state.replaceEntries(storageInventory.getInventoryId(), storageEntries);
        removeOwnedItemBatch(state, batch);
        requestManagedInventoryUiRefresh(astPlayer, batch.includesHotbar());
        return batch.amount();
    }

    /**
     * ストレージ entry を通常の所持インベントリへ取り出します。
     *
     * @param astPlayer 対象プレイヤー
     * @param storageEntryId 取り出すストレージ entry ID
     * @param amount 取り出し数。0 以下は全数扱い
     * @return 実際に取り出した個数
     */
    public int withdrawStorageEntry(
        @NotNull AstPlayer astPlayer,
        @NotNull UUID storageEntryId,
        int amount
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return 0;
        }
        InventoryModel storageInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.STORAGE);
        if (storageInventory == null || !storageInventory.isEnabled()) {
            return 0;
        }
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(storageInventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .toList());
        for (int index = 0; index < entries.size(); index++) {
            InventoryEntryModel entry = entries.get(index);
            if (!entry.getInventoryEntryId().equals(storageEntryId)) {
                continue;
            }
            ItemStack itemStack = itemStackResolver.resolve(entry);
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                return 0;
            }
            if (entry.getInstanceType() != null) {
                if (returnItemToOwnedInventory(astPlayer, itemStack.clone()) == null) {
                    return 0;
                }
                entries.remove(index);
                state.replaceEntries(storageInventory.getInventoryId(), entries);
                return itemStack.getAmount();
            }
            int availableQuantity = (int) Math.clamp(entry.getQuantity(), 1L, Integer.MAX_VALUE);
            int requestedAmount = amount <= 0 ? availableQuantity : Math.max(1, amount);
            int desiredAmount = Math.min(availableQuantity, requestedAmount);
            ItemModel model = resolveItemModel(entry);
            if (model == null) {
                return 0;
            }
            InventoryType targetType = resolveTargetInventoryType(model);
            InventoryModel targetInventory = ensureInventory(state, targetType);
            int movedAmount = addStackedItems(
                state,
                targetInventory,
                model,
                desiredAmount,
                collectUsedSlots(state, targetInventory)
            );
            if (movedAmount <= 0) {
                return 0;
            }
            if (movedAmount >= availableQuantity) {
                entries.remove(index);
            } else {
                entries.set(index, withQuantity(entry, availableQuantity - movedAmount, state.getAccountId()));
            }
            state.replaceEntries(storageInventory.getInventoryId(), entries);
            compactInventoryEntries(state, targetInventory.getInventoryId());
            autoSwitchDisplayedInventory(astPlayer, targetType);
            return movedAmount;
        }
        return 0;
    }

    /**
     * 指定アカウントの通貨インベントリから対象 itemId の数量合計を返します。
     *
     * @param accountId 対象アカウントID
     * @param itemId 通貨アイテムID
     * @return 所持数量。未ロードまたは未所持の場合は 0
     */
    public long getCurrencyAmount(@NotNull UUID accountId, @NotNull String itemId) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return 0L;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.CURRENCY);
        if (inventory == null || !inventory.isEnabled()) {
            return 0L;
        }
        String normalizedItemId = itemId.trim();
        if (normalizedItemId.isBlank()) {
            return 0L;
        }
        return normalizeCurrencyEntries(state, inventory).stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> entry.getItemId() != null && entry.getItemId().equalsIgnoreCase(normalizedItemId))
            .mapToLong(InventoryEntryModel::getQuantity)
            .sum();
    }

    /**
     * 全ゴールド額面と互換IDを基本ゴールドへ換算した合計値を返します。
     *
     * @param accountId 対象アカウントID
     * @return 合計ゴールド値
     */
    public long getGoldAmount(@NotNull UUID accountId) {
        long denominationTotal = GoldCurrencyCalculator.totalValue(
            denomination -> getCurrencyAmount(accountId, denomination.itemId())
        );
        long legacyAmount = getCurrencyAmount(accountId, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
        return denominationTotal > Long.MAX_VALUE - legacyAmount
            ? Long.MAX_VALUE
            : denominationTotal + legacyAmount;
    }

    /**
     * 通貨 GUI の通貨を通常 BAG へ取り出します。
     * <p>
     * 通貨は通常の報酬付与時には CURRENCY へ直接保存されますが、GUI 操作による取り出しだけは
     * プレイヤーが交換に使える通常アイテムとして BAG へ移します。
     *
     * @param astPlayer 対象プレイヤー
     * @param currencyItem 通貨 GUI 上の通貨 ItemStack
     * @param amount 取り出す数量
     * @return 実際に取り出せた数量
     */
    public int withdrawCurrencyToNormalInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack currencyItem,
        int amount
    ) {
        ItemReference reference = resolveItemReference(currencyItem);
        if (reference == null || ItemCategory.fromApiValue(reference.category()) != ItemCategory.CURRENCY) {
            return 0;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return 0;
        }
        InventoryModel currencyInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.CURRENCY);
        if (currencyInventory == null || !currencyInventory.isEnabled()) {
            return 0;
        }
        long available = getCurrencyAmount(astPlayer.getAccount().getUuid(), reference.itemId());
        int requested = Math.max(0, amount);
        int desired = (int) Math.min(available, Math.min(Integer.MAX_VALUE, requested));
        if (desired <= 0) {
            return 0;
        }

        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        if (model == null) {
            return 0;
        }
        InventoryStateSnapshot snapshot = snapshotState(astPlayer.getAccount().getUuid());
        InventoryModel bagInventory = ensureInventory(state, InventoryType.BAG);
        int added = addStackedItems(state, bagInventory, model, desired, collectUsedSlots(state, bagInventory));
        if (added <= 0) {
            return 0;
        }

        long consumed = consumeItemAmountFromInventory(state, currencyInventory, reference.itemId(), added);
        if (consumed != added) {
            restoreState(snapshot);
            return 0;
        }
        compactInventoryEntries(currencyInventory.getInventoryId(), state.getAccountId());
        requestManagedInventoryUiRefresh(astPlayer, false);
        return added;
    }

    /**
     * 通常 BAG またはホットバーの通貨アイテムを通貨インベントリへ戻します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 移動元の Bukkit プレイヤーインベントリスロット
     * @param amount 移動数量。0 以下は全量
     * @return 実際に戻せた数量
     */
    public int moveOwnedCurrencyToCurrency(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot,
        int amount
    ) {
        InventoryEntryModel sourceEntry = getOwnedEntryAtBukkitSlot(astPlayer, sourceBukkitSlot);
        if (sourceEntry == null || ItemCategory.fromApiValue(sourceEntry.getItemCategory()) != ItemCategory.CURRENCY) {
            return 0;
        }
        ItemStack sourceItem = itemStackResolver.resolve(sourceEntry);
        if (sourceItem == null || sourceItem.getType() == Material.AIR) {
            return 0;
        }
        int requested = amount <= 0 ? sourceItem.getAmount() : Math.min(amount, sourceItem.getAmount());
        if (requested <= 0) {
            return 0;
        }

        InventoryStateSnapshot snapshot = snapshotState(astPlayer.getAccount().getUuid());
        ItemStack moved = takeOwnedItemAmount(astPlayer, sourceBukkitSlot, requested);
        if (moved == null) {
            return 0;
        }
        InventoryType targetType = returnItemToOwnedInventory(astPlayer, moved);
        if (targetType != InventoryType.CURRENCY) {
            restoreState(snapshot);
            requestManagedInventoryUiRefresh(astPlayer, sourceBukkitSlot <= 8);
            return 0;
        }
        return moved.getAmount();
    }

    /**
     * 指定スロットと同じ通貨アイテムを BAG・ホットバー全体から CURRENCY へ戻します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 基準にする Bukkit PlayerInventory のスロット番号
     * @return 実際に戻した数量
     */
    public int moveAllOwnedMatchingCurrencyToCurrency(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return 0;
        }
        OwnedItemBatch batch = collectOwnedItemBatch(state, sourceBukkitSlot);
        if (batch == null
            || !isStackableByItemId(batch.sourceEntry())
            || ItemCategory.fromApiValue(batch.sourceEntry().getItemCategory()) != ItemCategory.CURRENCY) {
            return 0;
        }
        ItemModel model = resolveItemModel(batch.sourceEntry());
        InventoryStateSnapshot snapshot = snapshotState(astPlayer.getAccount().getUuid());
        if (model == null || snapshot == null) {
            return 0;
        }

        removeOwnedItemBatch(state, batch);
        InventoryModel currencyInventory = ensureInventory(state, InventoryType.CURRENCY);
        int added = addStackedItems(
            state,
            currencyInventory,
            model,
            batch.amount(),
            collectUsedSlots(state, currencyInventory)
        );
        if (added != batch.amount()) {
            restoreState(snapshot);
            requestManagedInventoryUiRefresh(astPlayer, batch.includesHotbar());
            return 0;
        }
        compactInventoryEntries(state, currencyInventory.getInventoryId());
        requestManagedInventoryUiRefresh(astPlayer, batch.includesHotbar());
        return added;
    }

    public long getNormalItemAmount(@NotNull UUID accountId, @NotNull String itemId) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return 0L;
        }
        String normalizedItemId = itemId.trim();
        if (normalizedItemId.isBlank()) {
            return 0L;
        }
        return getItemAmount(state, InventoryType.BAG, normalizedItemId)
            + getItemAmount(state, InventoryType.HOTBAR, normalizedItemId);
    }

    /**
     * 補償可能な複合操作の開始時点として、アカウント配下の全 inventory entry を取得します。
     * スナップショット自体は state を変更せず、同一アカウントへだけ復元できます。
     *
     * @param accountId 対象アカウントID
     * @return 現在のスナップショット。state 未ロードの場合は {@code null}
     */
    public @Nullable InventoryStateSnapshot snapshotState(@NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            Map<UUID, List<InventoryEntryModel>> entries = new LinkedHashMap<>();
            for (InventoryModel inventory : state.snapshotInventories()) {
                entries.put(inventory.getInventoryId(), state.snapshotEntries(inventory.getInventoryId()));
            }
            return new InventoryStateSnapshot(accountId, entries, state.getDisplayedType(), state.isDirty());
        }
    }

    /**
     * 複合操作に失敗した state を取得時点へ戻します。
     * 復元後の内容を後続 autosave の対象にするため dirty 状態は維持します。
     *
     * @param snapshot {@link #snapshotState(UUID)} で取得したスナップショット
     * @return 同一アカウントのロード済み state へ復元できた場合は {@code true}
     */
    public boolean restoreState(@Nullable InventoryStateSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        PlayerInventoryState state = getState(snapshot.accountId());
        if (state == null) {
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
            if (snapshot.dirty()) {
                state.restoreDirty();
            } else {
                state.takeAndClearDirty();
            }
        }
        return true;
    }

    /**
     * 合計ゴールド値から低額面を優先して指定量を消費します。
     * 低額面だけで不足する場合に限り、必要な上位額面を崩して釣り銭を作ります。
     *
     * @param accountId 対象アカウントID
     * @param amount 消費する基本ゴールド値
     * @return 全量を消費できた場合はtrue
     */
    public boolean consumeGold(@NotNull UUID accountId, long amount) {
        if (amount <= 0L) {
            return true;
        }
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return false;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.CURRENCY);
        if (inventory == null || !inventory.isEnabled()) {
            return false;
        }
        synchronized (state) {
            long legacyAmount = getCurrencyAmount(accountId, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
            Map<GoldDenomination, Long> remainingBalances = GoldCurrencyCalculator.spendSmallestFirst(
                denomination -> {
                    long denominationAmount = getCurrencyAmount(accountId, denomination.itemId());
                    if (denomination != GoldDenomination.GOLD) {
                        return denominationAmount;
                    }
                    return denominationAmount > Long.MAX_VALUE - legacyAmount
                        ? Long.MAX_VALUE
                        : denominationAmount + legacyAmount;
                },
                amount
            );
            if (remainingBalances == null) {
                return false;
            }
            InventoryStateSnapshot snapshot = snapshotState(accountId);
            if (snapshot == null || !removeAllGoldCurrency(state, inventory)) {
                return false;
            }
            for (Map.Entry<GoldDenomination, Long> entry : remainingBalances.entrySet()) {
                if (!addCurrencyAmountToInventory(state, inventory, entry.getKey().itemId(), entry.getValue())) {
                    restoreState(snapshot);
                    return false;
                }
            }
            compactInventoryEntries(state, inventory.getInventoryId());
            return true;
        }
    }

    /**
     * 指定した通貨を通貨インベントリから減算します。
     * <p>
     * 基本通貨 {@code gold} は互換 ID {@code ast_gold} の残高も合算して消費します。
     *
     * @param accountId 対象アカウント ID
     * @param itemId 通貨アイテム ID
     * @param amount 消費数量
     * @return 全量を消費できた場合 {@code true}
     */
    public boolean consumeCurrency(
        @NotNull UUID accountId,
        @NotNull String itemId,
        long amount
    ) {
        if (amount <= 0L) {
            return true;
        }
        String normalizedItemId = itemId.trim();
        if (normalizedItemId.isBlank()) {
            return false;
        }
        if (ItemService.DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(normalizedItemId)) {
            return consumeGold(accountId, amount);
        }
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return false;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.CURRENCY);
        if (inventory == null || !inventory.isEnabled() || getCurrencyAmount(accountId, normalizedItemId) < amount) {
            return false;
        }
        return consumeItemAmountFromInventory(state, inventory, normalizedItemId, amount) == amount;
    }

    /**
     * 価値が等しい組み込みゴールド額面を、同一トランザクション内で交換します。
     * 基本額面の交換元には互換ID {@code ast_gold} も使用します。
     *
     * @param accountId 対象アカウントID
     * @param sourceItemId 交換元額面ID
     * @param sourceAmount 交換元数量
     * @param targetItemId 交換先額面ID
     * @param targetAmount 交換先数量
     * @return 等価交換が完了した場合はtrue
     */
    public boolean exchangeCurrency(
        @NotNull UUID accountId,
        @NotNull String sourceItemId,
        long sourceAmount,
        @NotNull String targetItemId,
        long targetAmount
    ) {
        GoldDenomination source = GoldDenomination.findByItemId(sourceItemId);
        GoldDenomination target = GoldDenomination.findByItemId(targetItemId);
        if (source == null || target == null || source == target
            || sourceAmount <= 0L || targetAmount <= 0L
            || !hasEqualGoldValue(source, sourceAmount, target, targetAmount)) {
            return false;
        }
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return false;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.CURRENCY);
        if (inventory == null || !inventory.isEnabled()) {
            return false;
        }
        synchronized (state) {
            long available = getCurrencyAmount(accountId, source.itemId());
            if (source == GoldDenomination.GOLD) {
                long legacyAmount = getCurrencyAmount(accountId, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
                available = available > Long.MAX_VALUE - legacyAmount
                    ? Long.MAX_VALUE
                    : available + legacyAmount;
            }
            if (available < sourceAmount) {
                return false;
            }
            InventoryStateSnapshot snapshot = snapshotState(accountId);
            if (snapshot == null
                || consumeExactCurrencyAmount(state, inventory, source, sourceAmount) != sourceAmount
                || !addCurrencyAmountToInventory(state, inventory, target.itemId(), targetAmount)) {
                restoreState(snapshot);
                return false;
            }
            compactInventoryEntries(state, inventory.getInventoryId());
            return true;
        }
    }

    /**
     * 指定プレイヤーの通貨インベントリへゴールドを加算します。
     *
     * @param astPlayer 加算対象プレイヤー
     * @param amount 加算量
     * @return 全量を加算できた場合は {@code true}
     */
    public boolean addGold(@NotNull AstPlayer astPlayer, long amount) {
        if (amount <= 0L) {
            return true;
        }

        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.CURRENCY);
        if (inventory == null || !inventory.isEnabled()) {
            return false;
        }
        synchronized (state) {
            InventoryStateSnapshot snapshot = snapshotState(astPlayer.getAccount().getUuid());
            if (snapshot == null || !addGoldValue(state, inventory, amount)) {
                restoreState(snapshot);
                return false;
            }
            compactInventoryEntries(state, inventory.getInventoryId());
            return true;
        }
    }

    private boolean removeAllGoldCurrency(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory
    ) {
        for (GoldDenomination denomination : GoldDenomination.values()) {
            long amount = getCurrencyAmount(state.getAccountId(), denomination.itemId());
            if (consumeItemAmountFromInventory(state, inventory, denomination.itemId(), amount) != amount) {
                return false;
            }
        }
        long legacyAmount = getCurrencyAmount(state.getAccountId(), ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
        return consumeItemAmountFromInventory(
            state,
            inventory,
            ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID,
            legacyAmount
        ) == legacyAmount;
    }

    private boolean addGoldValue(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        long goldValue
    ) {
        for (Map.Entry<GoldDenomination, Long> entry : GoldCurrencyCalculator.decompose(goldValue).entrySet()) {
            if (!addCurrencyAmountToInventory(state, inventory, entry.getKey().itemId(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean addCurrencyAmountToInventory(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull String itemId,
        long amount
    ) {
        if (amount <= 0L) {
            return true;
        }
        ItemModel model = itemService.loadItem(itemId);
        if (model == null || ItemCategory.fromApiValue(model.getCategory()) != ItemCategory.CURRENCY) {
            return false;
        }
        List<InventoryEntryModel> entries = new ArrayList<>(normalizeCurrencyEntries(state, inventory));
        for (int index = 0; index < entries.size(); index++) {
            InventoryEntryModel entry = entries.get(index);
            if (entry.getItemId() == null || !entry.getItemId().equalsIgnoreCase(itemId)) {
                continue;
            }
            if (entry.getQuantity() > Long.MAX_VALUE - amount) {
                return false;
            }
            entries.set(index, withQuantity(entry, entry.getQuantity() + amount, state.getAccountId()));
            state.replaceEntries(inventory.getInventoryId(), entries);
            return true;
        }
        Set<Integer> usedSlots = collectUsedSlots(state, inventory);
        Integer slot = findNextFreeSlot(inventory, usedSlots);
        if (slot == null) {
            return false;
        }
        entries.add(newEntry(
            inventory.getInventoryId(),
            slot,
            model.getCategory(),
            model.getId(),
            null,
            null,
            amount,
            null,
            state.getAccountId()
        ));
        state.replaceEntries(inventory.getInventoryId(), entries);
        return true;
    }

    private long consumeExactCurrencyAmount(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull GoldDenomination source,
        long amount
    ) {
        long remaining = amount;
        remaining -= consumeItemAmountFromInventory(state, inventory, source.itemId(), remaining);
        if (remaining > 0L && source == GoldDenomination.GOLD) {
            remaining -= consumeItemAmountFromInventory(
                state,
                inventory,
                ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID,
                remaining
            );
        }
        return amount - remaining;
    }

    private boolean hasEqualGoldValue(
        @NotNull GoldDenomination source,
        long sourceAmount,
        @NotNull GoldDenomination target,
        long targetAmount
    ) {
        try {
            return Math.multiplyExact(source.goldValue(), sourceAmount)
                == Math.multiplyExact(target.goldValue(), targetAmount);
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    public boolean consumeNormalItem(@NotNull UUID accountId, @NotNull String itemId, long amount) {
        if (amount <= 0L) {
            return true;
        }
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return false;
        }
        if (getNormalItemAmount(accountId, itemId) < amount) {
            return false;
        }
        long remaining = amount;
        InventoryModel normalInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.BAG);
        if (normalInventory != null && normalInventory.isEnabled()) {
            remaining -= consumeItemAmountFromInventory(state, normalInventory, itemId, remaining);
        }
        InventoryModel hotbarInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.HOTBAR);
        if (remaining > 0L && hotbarInventory != null && hotbarInventory.isEnabled()) {
            remaining -= consumeItemAmountFromInventory(state, hotbarInventory, itemId, remaining);
        }
        return remaining <= 0L;
    }

    /**
     * 指定アカウントの即時保存をアカウント別キューへ登録します。
     * <p>
     * API I/O は保存コーディネーターの非同期 executor 上で実行されます。
     * 呼び出し元で同期待機すると同じ executor 上ではデッドロックし得るため、必要な後続処理は
     * 返却された future へ接続してください。
     *
     * @param accountId 対象アカウント ID
     * @return 保存に成功した場合 {@code true} となる future
     */
    public @NotNull CompletableFuture<Boolean> saveNow(@NotNull UUID accountId) {
        return saveCoordinator.saveNow(accountId);
    }

    public InventoryType resolveInventoryType(@NotNull ItemModel model) {
        return resolveTargetInventoryType(model);
    }

    public boolean canAddItemToNormalInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemModel model,
        int amount
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        int safeAmount = Math.max(1, amount);
        InventoryType targetType = resolveTargetInventoryType(model);
        if (targetType == InventoryType.CURRENCY) {
            return true;
        }
        InventoryModel inventory = ensureInventory(state, targetType);
        Set<Integer> usedSlots = collectUsedSlots(state, inventory);
        ItemCategory category = ItemCategory.fromApiValue(model.getCategory());
        if (category == ItemCategory.EQUIPMENT || category == ItemCategory.RUNE) {
            int freeSlots = 0;
            Set<Integer> simulatedUsed = new HashSet<>(usedSlots);
            for (int i = 0; i < safeAmount; i++) {
                Integer freeSlot = findNextFreeSlot(inventory, simulatedUsed);
                if (freeSlot == null) {
                    break;
                }
                simulatedUsed.add(freeSlot);
                freeSlots++;
            }
            return freeSlots >= safeAmount;
        }

        int maxStack = Math.max(1, model.getMaxStack());
        long capacity = state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> isStackableEntry(entry, model, maxStack))
            .mapToLong(entry -> maxStack - entry.getQuantity())
            .sum();
        Set<Integer> simulatedUsed = new HashSet<>(usedSlots);
        while (capacity < safeAmount) {
            Integer freeSlot = findNextFreeSlot(inventory, simulatedUsed);
            if (freeSlot == null) {
                break;
            }
            simulatedUsed.add(freeSlot);
            capacity += maxStack;
        }
        return capacity >= safeAmount;
    }

    private long getItemAmount(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryType inventoryType,
        @NotNull String itemId
    ) {
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, inventoryType);
        if (inventory == null || !inventory.isEnabled()) {
            return 0L;
        }
        return state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> entry.getItemId() != null && entry.getItemId().equalsIgnoreCase(itemId))
            .mapToLong(InventoryEntryModel::getQuantity)
            .sum();
    }

    // ---------------------------------------------------------------
    // Loadouts
    // ---------------------------------------------------------------

    public List<EquipmentLoadoutModel> getEquipmentLoadouts(@NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        return state == null ? List.of() : state.snapshotLoadouts(DEFAULT_PROFILE);
    }

    public @Nullable EquipmentLoadoutModel getActiveEquipmentLoadout(@NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        return state == null ? null : state.findActiveLoadout(DEFAULT_PROFILE);
    }

    public @Nullable EquipmentLoadoutModel ensureActiveEquipmentLoadout(@NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return null;
        }
        EquipmentLoadoutModel active = state.findActiveLoadout(DEFAULT_PROFILE);
        if (active != null) {
            return active;
        }
        try {
            List<EquipmentLoadoutModel> loadouts = equipmentLoadoutRepository.findByAccountId(accountId, DEFAULT_PROFILE);
            for (EquipmentLoadoutModel loadout : loadouts) {
                state.putLoadout(loadout);
            }
            if (!loadouts.isEmpty()) {
                EquipmentLoadoutModel activated = equipmentLoadoutRepository.activate(loadouts.get(0).getEquipmentLoadoutId(), accountId);
                if (activated != null) {
                    state.putLoadout(activated);
                    return activated;
                }
            }
            EquipmentLoadoutModel created = equipmentLoadoutRepository.create(
                accountId, DEFAULT_LOADOUT_NAME, accountId, DEFAULT_PROFILE, 0, true, null
            );
            state.putLoadout(created);
            return created;
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5253, accountId, e.getMessage());
            return null;
        }
    }

    /**
     * 現在の装備状態を業務ロジック向けのアイテム参照一覧として返します。
     * <p>
     * 防具・アクセサリはアクティブ loadout を正本として解決し、主武器は現在選択中の
     * HOTBAR スロットから補完します。Bukkit の見た目 ItemStack を直接走査せず、
     * state / loadout ベースでステータス系ロジックから利用する想定です。
     *
     * @param astPlayer 対象プレイヤー
     * @return 現在装備中として扱うアイテム参照一覧
     */
    public @NotNull List<ItemReference> getEquippedItemReferences(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return List.of();
        }

        List<ItemReference> references = new ArrayList<>();
        EquipmentLoadoutModel loadout = getActiveEquipmentLoadout(astPlayer.getAccount().getUuid());
        if (loadout != null) {
            for (EquipmentLoadoutSlotModel slot : loadout.getSlots()) {
                if (slot.isDeleted()) {
                    continue;
                }
                ItemReference reference = resolveItemReference(toInventoryEntry(slot));
                if (reference != null) {
                    references.add(reference);
                }
            }
        }

        InventoryEntryModel mainHandEntry = findHotbarEntryBySlot(
            state,
            HotbarLayout.toDbSlot(astPlayer.getBukkit().getInventory().getHeldItemSlot())
        );
        ItemReference mainHandReference = mainHandEntry == null ? null : resolveItemReference(mainHandEntry);
        if (mainHandReference != null) {
            references.add(mainHandReference);
        }
        return references;
    }

    /**
     * 指定した手に対応する HOTBAR 正本 entry を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param hand 主手またはオフハンド
     * @return 正本 entry。未設定時は null
     */
    public @Nullable InventoryEntryModel getHotbarEntryInHand(
        @NotNull AstPlayer astPlayer,
        @NotNull EquipmentSlot hand
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        return findHotbarEntryBySlot(state, toHotbarDbSlot(astPlayer, hand));
    }

    /**
     * 指定した手に対応する HOTBAR 正本の参照情報を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param hand 主手またはオフハンド
     * @return 参照情報。未設定時は null
     */
    public @Nullable ItemReference getItemReferenceInHand(
        @NotNull AstPlayer astPlayer,
        @NotNull EquipmentSlot hand
    ) {
        return resolveItemReference(getHotbarEntryInHand(astPlayer, hand));
    }

    /**
     * 指定した手に対応する HOTBAR 正本のアイテムモデルを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param hand 主手またはオフハンド
     * @return アイテムモデル。未設定時は null
     */
    public @Nullable ItemModel getItemModelInHand(
        @NotNull AstPlayer astPlayer,
        @NotNull EquipmentSlot hand
    ) {
        return itemReferenceResolver.resolveItemModel(getItemReferenceInHand(astPlayer, hand));
    }

    private boolean applyActiveEquipmentLoadoutToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return false;
        }
        EquipmentLoadoutModel loadout = getActiveEquipmentLoadout(astPlayer.getAccount().getUuid());
        if (loadout == null || loadout.getSlots().isEmpty()) {
            return false;
        }
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        inventory.setHelmet(new ItemStack(Material.AIR));
        inventory.setChestplate(new ItemStack(Material.AIR));
        inventory.setLeggings(new ItemStack(Material.AIR));
        inventory.setBoots(new ItemStack(Material.AIR));

        ItemStack desiredOffHand = new ItemStack(Material.AIR);
        for (EquipmentLoadoutSlotModel slot : loadout.getSlots()) {
            if (slot.isDeleted()) {
                continue;
            }
            ItemStack itemStack = itemStackResolver.resolve(toInventoryEntry(slot));
            if (itemStack == null) {
                continue;
            }
            if (SLOT_TYPE_ACCESSORY.equalsIgnoreCase(slot.getSlotType()) && slot.getSlotIndex() == 0) {
                desiredOffHand = itemStack;
                continue;
            }
            applyLoadoutSlot(inventory, slot.getSlotType(), slot.getSlotIndex(), itemStack);
        }
        if (!isSameItemStack(inventory.getItemInOffHand(), desiredOffHand)) {
            inventory.setItemInOffHand(desiredOffHand);
        }
        astPlayer.getBukkit().updateInventory();
        return true;
    }

    /**
     * アクティブロードアウトの装備状態を Bukkit 装備欄に基づいて state に反映します。
     * <p>
     * state.upsertActiveLoadoutSlot は自動的に markDirty するため、次回オートセーブで API へ反映されます。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void syncCurrentEquipmentState(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        ItemStack[] accessories = getAccessorySnapshotItems(astPlayer);
        accessories[AccessorySlotLayout.SLOT_OFF_HAND] = inventory.getItemInOffHand();
        applyLoadoutDiff(
            state,
            inventory.getHelmet(),
            inventory.getChestplate(),
            inventory.getLeggings(),
            inventory.getBoots(),
            accessories
        );
    }

    private void applyLoadoutDiff(
        @NotNull PlayerInventoryState state,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @NotNull ItemStack[] accessories
    ) {
        if (ensureActiveEquipmentLoadout(state.getAccountId()) == null) {
            return;
        }
        UUID actor = state.getAccountId();
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_HEAD, 0, readEquipmentInstanceId(head), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_CHEST, 0, readEquipmentInstanceId(chest), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_LEGS, 0, readEquipmentInstanceId(legs), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_FEET, 0, readEquipmentInstanceId(feet), actor);
        for (int slotIndex = AccessorySlotLayout.SLOT_MIN; slotIndex <= AccessorySlotLayout.SLOT_MAX; slotIndex++) {
            ItemStack itemStack = accessories.length > slotIndex ? accessories[slotIndex] : null;
            state.upsertActiveLoadoutSlot(
                DEFAULT_PROFILE,
                SLOT_TYPE_ACCESSORY,
                toAccessoryLoadoutSlotIndex(slotIndex),
                readEquipmentInstanceId(itemStack),
                actor
            );
        }
    }

    /**
     * 装備 GUI の内容を Bukkit 装備欄と state へ反映します。
     *
     * @param astPlayer 対象プレイヤー
     * @param head 頭装備
     * @param chest 胴装備
     * @param legs 脚装備
     * @param feet 足装備
     * @param accessories slotIndex と配列 index が一致するオフハンド・アクセサリ一覧
     * @return 装備状態が変更された場合 true
     */
    public boolean saveEquipmentGui(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @NotNull ItemStack[] accessories
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        PlayerInventory bukkitInventory = astPlayer.getBukkit().getInventory();
        if (!hasEquipmentGuiChanges(astPlayer, bukkitInventory, head, chest, legs, feet, accessories)) {
            return false;
        }
        bukkitInventory.setHelmet(itemOrAir(head));
        bukkitInventory.setChestplate(itemOrAir(chest));
        bukkitInventory.setLeggings(itemOrAir(legs));
        bukkitInventory.setBoots(itemOrAir(feet));
        bukkitInventory.setItemInOffHand(itemOrAir(accessoryAt(accessories, AccessorySlotLayout.SLOT_OFF_HAND)));

        ItemStack[] equipSnapshot = new ItemStack[EquipSlotLayout.SLOT_MAX + 1];
        equipSnapshot[EquipSlotLayout.SLOT_HEAD] = itemOrAir(head);
        equipSnapshot[EquipSlotLayout.SLOT_CHEST] = itemOrAir(chest);
        equipSnapshot[EquipSlotLayout.SLOT_LEGS] = itemOrAir(legs);
        equipSnapshot[EquipSlotLayout.SLOT_FEET] = itemOrAir(feet);
        InventoryModel equipInventory = ensureInventory(state, InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX, state.getAccountId(), DEFAULT_PROFILE);
        state.updateInventoryMetadata(equipInventory.getInventoryId(),
            snapshotCodec.encode(equipSnapshot), state.getAccountId());

        applyLoadoutDiff(state, head, chest, legs, feet, accessories);

        astPlayer.getBukkit().updateInventory();
        return true;
    }

    private boolean hasEquipmentGuiChanges(
        @NotNull AstPlayer astPlayer,
        @NotNull PlayerInventory bukkitInventory,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @NotNull ItemStack[] accessories
    ) {
        if (!isSameEquipmentItem(head, bukkitInventory.getHelmet())
            || !isSameEquipmentItem(chest, bukkitInventory.getChestplate())
            || !isSameEquipmentItem(legs, bukkitInventory.getLeggings())
            || !isSameEquipmentItem(feet, bukkitInventory.getBoots())
            || !isSameEquipmentItem(
                accessoryAt(accessories, AccessorySlotLayout.SLOT_OFF_HAND),
                bukkitInventory.getItemInOffHand()
            )) {
            return true;
        }
        for (int slotIndex = AccessorySlotLayout.SLOT_AMULET; slotIndex <= AccessorySlotLayout.SLOT_MAX; slotIndex++) {
            if (!isSameEquipmentItem(
                accessoryAt(accessories, slotIndex),
                getAccessorySnapshotItem(astPlayer, slotIndex)
            )) {
                return true;
            }
        }
        return false;
    }

    private @Nullable ItemStack accessoryAt(@NotNull ItemStack[] accessories, int slotIndex) {
        return accessories.length > slotIndex ? accessories[slotIndex] : null;
    }

    private boolean isSameEquipmentItem(@Nullable ItemStack expected, @Nullable ItemStack current) {
        UUID expectedInstanceId = readEquipmentInstanceId(expected);
        UUID currentInstanceId = readEquipmentInstanceId(current);
        if (expectedInstanceId != null || currentInstanceId != null) {
            return expectedInstanceId != null && expectedInstanceId.equals(currentInstanceId);
        }
        return itemOrAir(expected).getType() == itemOrAir(current).getType();
    }

    private boolean isSameEquipmentInstance(@Nullable ItemStack itemStack, @NotNull String instanceId) {
        UUID expectedInstanceId = parseUuidOrNull(instanceId);
        UUID currentInstanceId = readEquipmentInstanceId(itemStack);
        return expectedInstanceId != null && expectedInstanceId.equals(currentInstanceId);
    }

    // ---------------------------------------------------------------
    // EQUIP_SLOT
    // ---------------------------------------------------------------

    public void applyEquipSlotInventoryToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        Player bukkitPlayer = astPlayer.getBukkit();
        PlayerInventory playerInventory = bukkitPlayer.getInventory();
        playerInventory.setHelmet(new ItemStack(Material.AIR));
        playerInventory.setChestplate(new ItemStack(Material.AIR));
        playerInventory.setLeggings(new ItemStack(Material.AIR));
        playerInventory.setBoots(new ItemStack(Material.AIR));
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.EQUIP_SLOT);
        if (inventory != null) {
            List<InventoryEntryModel> entries = state.snapshotEntries(inventory.getInventoryId());
            if (inventory.getMetadataJson() != null && !inventory.getMetadataJson().isBlank()) {
                var snapshot = snapshotCodec.decode(inventory.getMetadataJson());
                if (snapshot != null) {
                    EquipSlotLayout.applySnapshot(bukkitPlayer, snapshot);
                }
            } else if (!entries.isEmpty()) {
                EquipSlotLayout.applyEntriesToPlayer(bukkitPlayer, entries, itemStackResolver::resolve);
            }
        }
        bukkitPlayer.updateInventory();
    }

    public void saveEquipSlotSnapshot(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        InventoryModel inventory = ensureInventory(state, InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX, state.getAccountId(), DEFAULT_PROFILE);
        var snapshot = EquipSlotLayout.createSnapshot(astPlayer.getBukkit());
        state.updateInventoryMetadata(inventory.getInventoryId(),
            snapshotCodec.encode(snapshot), state.getAccountId());
    }

    public void equipToSlot(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel entry,
        int slotIndex
    ) {
        if (!EquipSlotLayout.isManagedSlot(slotIndex)) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        InventoryModel equipInventory = ensureInventory(state, InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX, state.getAccountId(), DEFAULT_PROFILE);
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(equipInventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .filter(e -> e.getSlotIndex() == null || e.getSlotIndex() != slotIndex)
            .toList());
        entries.add(copyEntryWithSlot(entry, equipInventory.getInventoryId(), slotIndex, state.getAccountId()));
        state.replaceEntries(equipInventory.getInventoryId(), entries);
        EquipmentType.fromEquipSlotIndex(slotIndex)
            .applyTo(astPlayer.getBukkit().getInventory(), itemStackResolver.resolve(entry));
        astPlayer.getBukkit().updateInventory();
    }

    // ---------------------------------------------------------------
    // HOTBAR
    // ---------------------------------------------------------------

    public void applyHotbarInventoryToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        ensureInventory(state, InventoryType.HOTBAR, HotbarLayout.CAPACITY, state.getAccountId(), DEFAULT_PROFILE);
        renderHotbarInventory(astPlayer);
    }

    public void saveHotbarSnapshot(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        ensureInventory(state, InventoryType.HOTBAR, HotbarLayout.CAPACITY, state.getAccountId(), DEFAULT_PROFILE);
    }

    public boolean handleHotbarSlotClick(@NotNull AstPlayer astPlayer, int hotbarSlotIndex) {
        if (!HotbarLayout.isManagedSlot(hotbarSlotIndex)) {
            return false;
        }
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return false;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        InventoryEntryModel entry = findHotbarEntryBySlot(state, hotbarSlotIndex);
        if (entry != null) {
            state.setSelectedHotbarSlot(null);
            boolean returned = returnHotbarEntryToInventory(astPlayer, state, entry);
            renderHotbarInventory(astPlayer);
            return returned;
        }

        Integer currentSelected = state.getSelectedHotbarSlot();
        if (Integer.valueOf(hotbarSlotIndex).equals(currentSelected)) {
            state.setSelectedHotbarSlot(null);
            renderHotbarInventory(astPlayer);
            return true;
        }
        state.setSelectedHotbarSlot(hotbarSlotIndex);
        if (HotbarLayout.isMainHotbarSlot(hotbarSlotIndex)) {
            astPlayer.getBukkit().getInventory().setHeldItemSlot(HotbarLayout.toBukkitSlot(hotbarSlotIndex));
        }
        renderHotbarInventory(astPlayer);
        return true;
    }

    public void moveToHotbar(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel entry,
        int hotbarSlotIndex
    ) {
        if (!HotbarLayout.isManagedSlot(hotbarSlotIndex)) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        ItemStack itemStack = itemStackResolver.resolve(entry);
        if (itemStack == null) {
            return;
        }
        upsertHotbarEntry(state, entry, hotbarSlotIndex);
        renderHotbarInventory(astPlayer);
    }

    /**
     * 手持ちスロットの hotbar エントリを消費します。
     *
     * @param astPlayer       対象プレイヤー
     * @param hand            消費元の手
     * @param expectedItemId  期待するアイテムID
     * @param amount          消費数
     * @return 消費成功時は {@code true}
     */
    public boolean consumeHotbarItemInHand(
        @NotNull AstPlayer astPlayer,
        @NotNull EquipmentSlot hand,
        @NotNull String expectedItemId,
        int amount
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }

        int safeAmount = Math.max(1, amount);
        int hotbarSlot = toHotbarDbSlot(astPlayer, hand);
        InventoryEntryModel entry = findHotbarEntryBySlot(state, hotbarSlot);
        if (entry == null || entry.isDeleted()) {
            return false;
        }
        if (entry.getItemId() == null || !entry.getItemId().equalsIgnoreCase(expectedItemId)) {
            return false;
        }
        if (entry.getQuantity() < safeAmount) {
            return false;
        }

        InventoryModel hotbarInventory = ensureInventory(
            state, InventoryType.HOTBAR, HotbarLayout.CAPACITY, state.getAccountId(), DEFAULT_PROFILE);
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(hotbarInventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .toList());
        for (int index = 0; index < entries.size(); index++) {
            InventoryEntryModel candidate = entries.get(index);
            if (!candidate.getInventoryEntryId().equals(entry.getInventoryEntryId())) {
                continue;
            }

            long remaining = candidate.getQuantity() - safeAmount;
            if (remaining > 0) {
                entries.set(index, withQuantity(candidate, remaining, state.getAccountId()));
            } else {
                entries.remove(index);
            }
            state.replaceEntries(hotbarInventory.getInventoryId(), entries);
            renderHotbarInventory(astPlayer);
            return true;
        }

        return false;
    }

    /**
     * 同一ホットバースロットへの再装備で entry が複製していたバグの修正点。
     * <p>
     * 旧実装では coalescer の窓中に楽観反映キャッシュがズレ、複数 entry が API へ書かれることがありました。
     * 本実装では state.replaceEntries() を同期的に呼ぶ単一トランザクションのため、複製は構造的に発生しません。
     */
    private void upsertHotbarEntry(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry,
        int targetDbSlot
    ) {
        InventoryModel hotbarInventory = ensureInventory(state, InventoryType.HOTBAR,
            HotbarLayout.CAPACITY, state.getAccountId(), DEFAULT_PROFILE);
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(hotbarInventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .filter(e -> e.getSlotIndex() == null || e.getSlotIndex() != targetDbSlot)
            .toList());
        entries.add(copyEntryWithSlot(sourceEntry, hotbarInventory.getInventoryId(), targetDbSlot, state.getAccountId()));
        state.replaceEntries(hotbarInventory.getInventoryId(), entries);
    }

    private boolean returnHotbarEntryToInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel hotbarEntry
    ) {
        InventoryType targetType = resolveTargetInventoryType(hotbarEntry);
        InventoryModel targetInventory = ensureInventory(state, targetType,
            resolveSlotCapacity(targetType), state.getAccountId(), DEFAULT_PROFILE);
        ItemCategory category = ItemCategory.fromApiValue(hotbarEntry.getItemCategory());
        if (category == ItemCategory.EQUIPMENT || category == ItemCategory.RUNE) {
            List<InventoryEntryModel> targetEntries = state.snapshotEntries(targetInventory.getInventoryId());
            Set<Integer> usedSlots = NormalInventoryLayout.collectUsedSlots(
                targetEntries, inventoryCapacity(targetInventory));
            Integer targetSlot = NormalInventoryLayout.findNextFreeSlot(
                usedSlots, inventoryCapacity(targetInventory));
            if (targetSlot == null) {
                return false;
            }
            List<InventoryEntryModel> newTargetEntries = new ArrayList<>(targetEntries.stream()
                .filter(e -> !e.isDeleted())
                .toList());
            newTargetEntries.add(copyEntryWithSlot(hotbarEntry, targetInventory.getInventoryId(), targetSlot, state.getAccountId()));
            state.replaceEntries(targetInventory.getInventoryId(), newTargetEntries);
        } else {
            ItemModel model = resolveItemModel(hotbarEntry);
            if (model == null) {
                return false;
            }
            int amount = Math.max(1, (int) hotbarEntry.getQuantity());
            Set<Integer> usedSlots = collectUsedSlots(state, targetInventory);
            if (addStackedItems(state, targetInventory, model, amount, usedSlots) != amount) {
                return false;
            }
        }

        InventoryModel hotbarInventory = ensureInventory(state, InventoryType.HOTBAR,
            HotbarLayout.CAPACITY, state.getAccountId(), DEFAULT_PROFILE);
        List<InventoryEntryModel> hotbarEntries = state.snapshotEntries(hotbarInventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .filter(e -> !e.getInventoryEntryId().equals(hotbarEntry.getInventoryEntryId()))
            .toList();
        state.replaceEntries(hotbarInventory.getInventoryId(), hotbarEntries);
        autoSwitchDisplayedInventory(astPlayer, targetType);
        return true;
    }

    private int findNextHotbarSlot(@NotNull PlayerInventoryState state) {
        InventoryModel hotbarInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.HOTBAR);
        if (hotbarInventory == null) {
            return HotbarLayout.DB_SLOT_START;
        }
        Set<Integer> used = new HashSet<>();
        for (InventoryEntryModel entry : state.snapshotEntries(hotbarInventory.getInventoryId())) {
            if (entry.isDeleted()) {
                continue;
            }
            Integer slot = entry.getSlotIndex();
            if (slot != null) {
                used.add(slot);
            }
        }
        for (int slot = HotbarLayout.DB_SLOT_START; slot <= HotbarLayout.DB_SLOT_END; slot++) {
            if (!used.contains(slot)) {
                return slot;
            }
        }
        if (!used.contains(HotbarLayout.DB_SLOT_OFFHAND)) {
            return HotbarLayout.DB_SLOT_OFFHAND;
        }
        return -1;
    }

    private @Nullable InventoryEntryModel findHotbarEntryBySlot(@NotNull PlayerInventoryState state, int hotbarSlotIndex) {
        InventoryModel hotbarInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.HOTBAR);
        if (hotbarInventory == null) {
            return null;
        }
        for (InventoryEntryModel entry : state.snapshotEntries(hotbarInventory.getInventoryId())) {
            if (entry.isDeleted()) {
                continue;
            }
            Integer slot = entry.getSlotIndex();
            if (slot != null && slot == hotbarSlotIndex) {
                return entry;
            }
        }
        return null;
    }

    private void renderHotbarInventory(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        InventoryModel hotbarInventory = state.findInventory(DEFAULT_PROFILE, InventoryType.HOTBAR);
        Map<Integer, InventoryEntryModel> bySlot = new HashMap<>();
        if (hotbarInventory != null) {
            for (InventoryEntryModel entry : state.snapshotEntries(hotbarInventory.getInventoryId())) {
                Integer slot = entry.getSlotIndex();
                if (slot != null && HotbarLayout.isManagedSlot(slot) && !entry.isDeleted()) {
                    bySlot.put(slot, entry);
                }
            }
        }
        hotbarRenderer.renderHotbarInventory(
            astPlayer,
            bySlot,
            state.getSelectedHotbarSlot()
        );
    }

    public void setHotbarShortcutMode(@NotNull AstPlayer astPlayer, boolean on) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        if (state.setHotbarShortcutMode(on)) {
            applyDisplayedInventoryToGui(astPlayer);
            renderHotbarInventory(astPlayer);
        }
    }

    public boolean isHotbarShortcutMode(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        return state != null && state.isHotbarShortcutMode();
    }

    public boolean hasHotbarEntry(@NotNull AstPlayer astPlayer, int hotbarSlotIndex) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        return state != null && findHotbarEntryBySlot(state, hotbarSlotIndex) != null;
    }

    // ---------------------------------------------------------------
    // equip / assign clicked item
    // ---------------------------------------------------------------

    /**
     * 表示中インベントリ上のアイテムを、その entry を正本として装備または HOTBAR へ割り当てます。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 表示中インベントリ上の Bukkit スロット
     * @return 処理に成功した場合 true
     */
    public boolean equipOrAssignClickedItem(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        InventoryEntryModel sourceEntry = getDisplayedEntryAtBukkitSlot(astPlayer, sourceBukkitSlot);
        if (sourceEntry == null) {
            return false;
        }
        ItemStack sourceItem = itemStackResolver.resolve(sourceEntry);
        if (sourceItem == null || sourceItem.getType() == Material.AIR) {
            return false;
        }
        ItemCategory category = ItemCategory.fromApiValue(sourceEntry.getItemCategory());
        ItemModel model = resolveItemModel(sourceEntry);
        if (model == null) {
            return false;
        }

        if (category == ItemCategory.EQUIPMENT) {
            if (model.getEquipment() == null) {
                return false;
            }
            ItemEquipmentSlot itemSlot = model.getEquipment().getSlot();
            if (itemSlot != ItemEquipmentSlot.WEAPON
                && itemSlot != ItemEquipmentSlot.TOOL
                && !EquipmentRequirementService.checkAndNotify(astPlayer, model.getEquipment())) {
                return false;
            }
            if (itemSlot == ItemEquipmentSlot.HEAD || itemSlot == ItemEquipmentSlot.CHEST
                || itemSlot == ItemEquipmentSlot.LEGS || itemSlot == ItemEquipmentSlot.FEET
                || itemSlot == ItemEquipmentSlot.SUBWEAPON) {
                return equipArmorItem(astPlayer, state, sourceEntry, sourceItem, sourceBukkitSlot,
                    EquipmentType.fromItemEquipmentSlot(itemSlot));
            }
            if (itemSlot == ItemEquipmentSlot.ACCESSORY) {
                return equipAccessoryItem(astPlayer, state, sourceEntry, sourceItem, sourceBukkitSlot);
            }
            if (itemSlot == ItemEquipmentSlot.WEAPON || itemSlot == ItemEquipmentSlot.TOOL) {
                return assignHotbarItem(astPlayer, state, sourceEntry);
            }
            return false;
        }
        if (category == ItemCategory.BUNDLE || category == ItemCategory.CONSUMABLE) {
            return assignHotbarItem(astPlayer, state, sourceEntry);
        }
        return false;
    }

    private boolean equipArmorItem(
        @NotNull AstPlayer astPlayer,
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry,
        @NotNull ItemStack clickedItem,
        int sourceBukkitSlot,
        @NotNull EquipmentType equipmentType
    ) {
        if (equipmentType == EquipmentType.UNSUPPORTED) {
            return false;
        }
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        ItemStack previous = getEquipmentItem(inventory, equipmentType);
        if (!canReturnReplacedEquipment(state, sourceEntry, false, previous)) {
            return false;
        }
        equipmentType.applyTo(inventory, clickedItem.clone());
        inventory.setItem(sourceBukkitSlot, emptyToAir(previous));

        removeDisplayedEntryAfterMove(state, sourceEntry);
        returnReplacedItemToOwnedInventory(astPlayer, previous);
        saveEquipSlotSnapshot(astPlayer);
        syncCurrentEquipmentState(astPlayer);
        requestManagedInventoryUiRefresh(astPlayer, false);
        return true;
    }

    private boolean equipAccessoryItem(
        @NotNull AstPlayer astPlayer,
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry,
        @NotNull ItemStack clickedItem,
        int sourceBukkitSlot
    ) {
        ItemModel model = resolveItemModel(sourceEntry);
        if (model == null || model.getEquipment() == null) {
            return false;
        }
        int accessorySlot = findAccessoryTargetSlot(astPlayer, model.getEquipment().getTag());
        if (!AccessorySlotLayout.isManagedSlot(accessorySlot)
            || accessorySlot == AccessorySlotLayout.SLOT_OFF_HAND
            || getAccessorySnapshotItem(astPlayer, accessorySlot) != null) {
            return false;
        }
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        inventory.setItem(sourceBukkitSlot, new ItemStack(Material.AIR));
        updateAccessorySnapshotSlot(state, accessorySlot, clickedItem.clone());
        removeDisplayedEntryAfterMove(state, sourceEntry);
        syncCurrentEquipmentState(astPlayer);
        requestManagedInventoryUiRefresh(astPlayer, false);
        return true;
    }

    private boolean assignHotbarItem(
        @NotNull AstPlayer astPlayer,
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry
    ) {
        Integer selectedHotbarSlotIndex = state.getSelectedHotbarSlot();
        state.setSelectedHotbarSlot(null);
        int targetDbSlot = selectedHotbarSlotIndex != null && HotbarLayout.isManagedSlot(selectedHotbarSlotIndex)
            ? selectedHotbarSlotIndex
            : findNextHotbarSlot(state);
        if (!HotbarLayout.isManagedSlot(targetDbSlot)) {
            return false;
        }
        upsertHotbarEntry(state, sourceEntry, targetDbSlot);
        removeDisplayedEntryAfterMove(state, sourceEntry);
        requestManagedInventoryUiRefresh(astPlayer, true);
        return true;
    }

    /**
     * BAG またはホットバーのアイテムを装備 GUI へ移動します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot Bukkit PlayerInventory のスロット番号
     * @param replacedItem GUI 側で置き換えられる既存アイテム
     * @return 移動できた場合 true
     */
    public boolean moveOwnedItemToEquipmentGui(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot,
        @Nullable ItemStack replacedItem
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        boolean hotbarSlot = sourceBukkitSlot >= 0 && sourceBukkitSlot <= 8;
        InventoryEntryModel sourceEntry = hotbarSlot
            ? findHotbarEntryBySlot(state, sourceBukkitSlot + 1)
            : findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
        if (sourceEntry == null) {
            return false;
        }
        boolean hasReplacedItem = replacedItem != null && replacedItem.getType() != Material.AIR;
        if (hasReplacedItem && itemReferenceResolver.resolve(replacedItem) == null) {
            return false;
        }
        if (!canReturnReplacedEquipment(state, sourceEntry, hotbarSlot, replacedItem)) {
            return false;
        }
        List<InventoryEntryModel> sourceInventoryEntries = state.snapshotEntries(sourceEntry.getInventoryId());
        if (hotbarSlot) {
            List<InventoryEntryModel> remaining = state.snapshotEntries(sourceEntry.getInventoryId()).stream()
                .filter(entry -> !entry.isDeleted())
                .filter(entry -> !entry.getInventoryEntryId().equals(sourceEntry.getInventoryEntryId()))
                .toList();
            state.replaceEntries(sourceEntry.getInventoryId(), remaining);
            state.setSelectedHotbarSlot(null);
        } else {
            removeDisplayedEntryAfterMove(state, sourceEntry);
        }
        if (hasReplacedItem && returnItemToOwnedInventory(astPlayer, replacedItem.clone()) == null) {
            state.replaceEntries(sourceEntry.getInventoryId(), sourceInventoryEntries);
            requestManagedInventoryUiRefresh(astPlayer, hotbarSlot);
            return false;
        }
        requestManagedInventoryUiRefresh(astPlayer, hotbarSlot);
        return true;
    }

    public @Nullable ItemStack takeDisplayedItem(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        return takeDisplayedItemAmount(astPlayer, sourceBukkitSlot, 0);
    }

    /**
     * BAG またはホットバーからアイテムを1エントリ取り出します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot Bukkit PlayerInventory のスロット番号
     * @return 取り出した ItemStack。対象がなければ null
     */
    public @Nullable ItemStack takeOwnedItem(@NotNull AstPlayer astPlayer, int sourceBukkitSlot) {
        return takeOwnedItemAmount(astPlayer, sourceBukkitSlot, 0);
    }

    /**
     * BAG またはホットバーから指定数量のアイテムを取り出します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot Bukkit PlayerInventory のスロット番号
     * @param amount 取り出す数量。0以下または所持数以上なら全量
     * @return 取り出した ItemStack。対象がなければ null
     */
    public @Nullable ItemStack takeOwnedItemAmount(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot,
        int amount
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        boolean hotbarSlot = sourceBukkitSlot >= 0 && sourceBukkitSlot <= 8;
        InventoryEntryModel sourceEntry = hotbarSlot
            ? findHotbarEntryBySlot(state, sourceBukkitSlot + 1)
            : findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
        if (sourceEntry == null) {
            return null;
        }
        ItemStack sourceItem = itemStackResolver.resolve(sourceEntry);
        if (sourceItem == null || sourceItem.getType() == Material.AIR) {
            return null;
        }
        int totalAmount = sourceItem.getAmount();
        boolean takeAll = amount <= 0
            || amount >= totalAmount
            || sourceEntry.getInstanceType() != null;
        int takeAmount = takeAll ? totalAmount : amount;
        if (takeAll && hotbarSlot) {
            removeHotbarEntryAfterMove(state, sourceEntry);
        } else if (takeAll) {
            removeDisplayedEntryAfterMove(state, sourceEntry);
        } else {
            reduceDisplayedEntryQuantity(state, sourceEntry, sourceEntry.getQuantity() - takeAmount);
        }
        requestManagedInventoryUiRefresh(astPlayer, hotbarSlot);
        ItemStack result = sourceItem.clone();
        result.setAmount(takeAmount);
        return result;
    }

    /**
     * 表示中インベントリの指定スロットから、指定数量だけアイテムを取り出します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 表示中インベントリ上の Bukkit スロット
     * @param amount 取り出す数量。0 以下、または entry の数量を超える場合は entry 全量を取り出す。
     *               entry がインスタンス系（装備・ルーン）の場合は常に全量を取り出す。
     * @return 取り出した ItemStack（指定数量分）。対象が存在しない場合は null。
     */
    public @Nullable ItemStack takeDisplayedItemAmount(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot,
        int amount
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        InventoryEntryModel sourceEntry = findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
        if (sourceEntry == null) {
            return null;
        }
        ItemStack sourceItem = itemStackResolver.resolve(sourceEntry);
        if (sourceItem == null || sourceItem.getType() == Material.AIR) {
            return null;
        }
        int totalAmount = sourceItem.getAmount();
        boolean takeAll = amount <= 0
            || amount >= totalAmount
            || sourceEntry.getInstanceType() != null;
        int takeAmount = takeAll ? totalAmount : amount;
        if (takeAll) {
            removeDisplayedEntryAfterMove(state, sourceEntry);
        } else {
            reduceDisplayedEntryQuantity(state, sourceEntry, sourceEntry.getQuantity() - takeAmount);
        }
        requestManagedInventoryUiRefresh(astPlayer, false);
        ItemStack result = sourceItem.clone();
        result.setAmount(takeAmount);
        return result;
    }

    private void reduceDisplayedEntryQuantity(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry,
        long newQuantity
    ) {
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(sourceEntry.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .toList());
        for (int index = 0; index < entries.size(); index++) {
            InventoryEntryModel candidate = entries.get(index);
            if (!candidate.getInventoryEntryId().equals(sourceEntry.getInventoryEntryId())) {
                continue;
            }
            entries.set(index, withQuantity(candidate, newQuantity, state.getAccountId()));
            state.replaceEntries(sourceEntry.getInventoryId(), entries);
            return;
        }
    }

    private void removeHotbarEntryAfterMove(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry
    ) {
        List<InventoryEntryModel> remaining = state.snapshotEntries(sourceEntry.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> !entry.getInventoryEntryId().equals(sourceEntry.getInventoryEntryId()))
            .toList();
        state.replaceEntries(sourceEntry.getInventoryId(), remaining);
        state.setSelectedHotbarSlot(null);
    }

    private void removeDisplayedEntryAfterMove(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry
    ) {
        List<InventoryEntryModel> remaining = state.snapshotEntries(sourceEntry.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .filter(e -> !e.getInventoryEntryId().equals(sourceEntry.getInventoryEntryId()))
            .sorted(Comparator.<InventoryEntryModel, Integer>comparing(
                e -> e.getSlotIndex() == null ? Integer.MAX_VALUE : e.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .toList();
        state.replaceEntries(sourceEntry.getInventoryId(), remaining);
    }

    /**
     * 装備交換で外れる装備を、BAG の利用可能範囲へ安全に戻せるか判定します。
     * 容量外スロットのアイテムを移動しても通常範囲は空かないため、空きがなければ交換を拒否します。
     */
    private boolean canReturnReplacedEquipment(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry,
        boolean sourceIsHotbar,
        @Nullable ItemStack replacedItem
    ) {
        if (replacedItem == null || replacedItem.getType() == Material.AIR) {
            return true;
        }
        ItemReference replacedReference = itemReferenceResolver.resolve(replacedItem);
        if (replacedReference == null || itemReferenceResolver.resolveItemModel(replacedReference) == null) {
            return false;
        }
        InventoryModel bag = state.findInventory(DEFAULT_PROFILE, InventoryType.BAG);
        if (bag == null || !bag.isEnabled()) {
            return false;
        }
        int capacity = state.getBagSlotCapacity();
        Set<Integer> usedSlots = NormalInventoryLayout.collectUsedSlots(
            state.snapshotEntries(bag.getInventoryId()), capacity);
        if (!sourceIsHotbar && sourceEntry.getInventoryId().equals(bag.getInventoryId())) {
            Integer sourceSlot = sourceEntry.getSlotIndex();
            if (sourceSlot != null && NormalInventoryLayout.isManagedSlot(sourceSlot, capacity)) {
                usedSlots.remove(sourceSlot);
            }
        }
        return NormalInventoryLayout.findNextFreeSlot(usedSlots, capacity) != null;
    }

    private void returnReplacedItemToOwnedInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack replacedItem
    ) {
        if (replacedItem == null || replacedItem.getType() == Material.AIR) {
            return;
        }
        returnItemToOwnedInventory(astPlayer, replacedItem.clone());
    }

    private void requestManagedInventoryUiRefresh(@NotNull AstPlayer astPlayer, boolean includeHotbar) {
        applyDisplayedInventoryToGui(astPlayer);
        if (includeHotbar) {
            renderHotbarInventory(astPlayer);
        }
        astPlayer.getBukkit().updateInventory();

        io.github.maaasu.astralRecord.AstralRecord plugin = io.github.maaasu.astralRecord.AstralRecord.getInstance();
        if (plugin == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!astPlayer.getBukkit().isOnline()) {
                return;
            }
            applyDisplayedInventoryToGui(astPlayer);
            if (includeHotbar) {
                renderHotbarInventory(astPlayer);
            }
            astPlayer.getBukkit().updateInventory();
        });
    }

    private void applyDisplayedInventoryToGui(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.BAG);
        applyInventoryToBukkit(astPlayer.getBukkit(), state, inventory);
    }

    /**
     * 表示中インベントリ上の Bukkit スロットに対応する正本 entry を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 表示中インベントリ上の Bukkit スロット
     * @return 対応する entry。見つからない場合は null
     */
    public @Nullable InventoryEntryModel getDisplayedEntryAtBukkitSlot(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        return findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
    }

    /**
     * BAG またはホットバーの Bukkit スロットに対応する正本 entry を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot Bukkit PlayerInventory のスロット番号
     * @return 対応する entry。対象がなければ null
     */
    public @Nullable InventoryEntryModel getOwnedEntryAtBukkitSlot(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        return sourceBukkitSlot >= 0 && sourceBukkitSlot <= 8
            ? findHotbarEntryBySlot(state, sourceBukkitSlot + 1)
            : findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
    }

    /**
     * 表示中インベントリ上の Bukkit スロットに対応する正本 entry からアイテムモデルを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 表示中インベントリ上の Bukkit スロット
     * @return 対応するアイテムモデル。見つからない場合は null
     */
    public @Nullable ItemModel getDisplayedItemModelAtBukkitSlot(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        return resolveItemModel(getDisplayedEntryAtBukkitSlot(astPlayer, sourceBukkitSlot));
    }

    /**
     * BAG またはホットバーの Bukkit スロットにあるアイテムモデルを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot Bukkit PlayerInventory のスロット番号
     * @return 対応するモデル。対象がなければ null
     */
    public @Nullable ItemModel getOwnedItemModelAtBukkitSlot(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        InventoryEntryModel entry = sourceBukkitSlot >= 0 && sourceBukkitSlot <= 8
            ? findHotbarEntryBySlot(state, sourceBukkitSlot + 1)
            : findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
        return resolveItemModel(entry);
    }

    private @Nullable InventoryEntryModel findDisplayedEntryAtBukkitSlot(
        @NotNull PlayerInventoryState state,
        int sourceBukkitSlot
    ) {
        if (!NormalInventoryLayout.isManagedGuiSlot(sourceBukkitSlot)) {
            return null;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, InventoryType.BAG);
        if (inventory == null) {
            return null;
        }
        int dbSlot = NormalInventoryLayout.toDbSlotIndex(sourceBukkitSlot, state.getBagScrollRow());
        for (InventoryEntryModel entry : state.snapshotEntries(inventory.getInventoryId())) {
            if (entry.isDeleted()) {
                continue;
            }
            if (entry.getSlotIndex() != null && entry.getSlotIndex() == dbSlot) {
                return entry;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // ACCESSORY_SLOT
    // ---------------------------------------------------------------

    public void applyAccessorySlotInventoryToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        Player bukkitPlayer = astPlayer.getBukkit();
        ItemStack[] snapshot = new ItemStack[AccessorySlotLayout.SLOT_MAX + 1];
        snapshot[AccessorySlotLayout.SLOT_OFF_HAND] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_OFF_HAND));
        AccessorySlotLayout.applySnapshot(bukkitPlayer, snapshot);
        bukkitPlayer.updateInventory();
    }

    public void saveAccessorySlotSnapshot(@NotNull AstPlayer astPlayer) {
        syncCurrentEquipmentState(astPlayer);
    }

    public @Nullable ItemStack getAccessorySnapshotItem(@NotNull AstPlayer astPlayer, int slotIndex) {
        if (!AccessorySlotLayout.isManagedSlot(slotIndex)) {
            return null;
        }
        int loadoutSlotIndex = toAccessoryLoadoutSlotIndex(slotIndex);
        if (loadoutSlotIndex < 0) {
            return null;
        }
        EquipmentLoadoutModel loadout = getActiveEquipmentLoadout(astPlayer.getAccount().getUuid());
        if (loadout == null || loadout.getSlots().isEmpty()) {
            return null;
        }
        for (EquipmentLoadoutSlotModel slot : loadout.getSlots()) {
            if (slot.isDeleted()) {
                continue;
            }
            if (!SLOT_TYPE_ACCESSORY.equalsIgnoreCase(slot.getSlotType())) {
                continue;
            }
            if (slot.getSlotIndex() != loadoutSlotIndex) {
                continue;
            }
            if (slot.getEquipmentInstanceId() == null) {
                continue;
            }
            ItemStack item = itemStackResolver.resolve(toInventoryEntry(slot));
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            return item;
        }
        return null;
    }

    /**
     * オフハンドを含む全アクセサリスロットのスナップショットを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return slotIndex と配列 index が一致するスナップショット
     */
    public @NotNull ItemStack[] getAccessorySnapshotItems(@NotNull AstPlayer astPlayer) {
        ItemStack[] items = new ItemStack[AccessorySlotLayout.SLOT_MAX + 1];
        for (int slotIndex = AccessorySlotLayout.SLOT_MIN; slotIndex <= AccessorySlotLayout.SLOT_MAX; slotIndex++) {
            items[slotIndex] = getAccessorySnapshotItem(astPlayer, slotIndex);
        }
        return items;
    }

    public @NotNull List<ItemStack> getEquippedAccessorySnapshotItems(@NotNull AstPlayer astPlayer) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = AccessorySlotLayout.SLOT_OFF_HAND; slot <= AccessorySlotLayout.SLOT_MAX; slot++) {
            ItemStack item = getAccessorySnapshotItem(astPlayer, slot);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item);
            }
        }
        return items;
    }

    public void refreshEquipmentInstanceDisplay(
        @NotNull AstPlayer astPlayer,
        @NotNull EquipmentInstance instance
    ) {
        ItemModel model = itemService.findLoadedById(instance.getItemId());
        if (model == null) {
            model = itemService.loadItem(instance.getItemId());
        }
        if (model == null) {
            return;
        }
        ItemStack updated = itemStackFactory.create(model, instance, 1);
        String instanceId = instance.getEquipmentInstanceId();
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isSameEquipmentInstance(inventory.getItem(slot), instanceId)) {
                inventory.setItem(slot, updated.clone());
            }
        }
        if (isSameEquipmentInstance(inventory.getHelmet(), instanceId)) {
            inventory.setHelmet(updated.clone());
        }
        if (isSameEquipmentInstance(inventory.getChestplate(), instanceId)) {
            inventory.setChestplate(updated.clone());
        }
        if (isSameEquipmentInstance(inventory.getLeggings(), instanceId)) {
            inventory.setLeggings(updated.clone());
        }
        if (isSameEquipmentInstance(inventory.getBoots(), instanceId)) {
            inventory.setBoots(updated.clone());
        }
        if (isSameEquipmentInstance(inventory.getItemInOffHand(), instanceId)) {
            inventory.setItemInOffHand(updated.clone());
        }
        astPlayer.getBukkit().updateInventory();
    }

    public void moveToAccessorySlot(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel entry,
        int slotIndex
    ) {
        AccessorySlotType slotType = AccessorySlotType.fromSlotIndex(slotIndex);
        ItemModel model = resolveItemModel(entry);
        if (slotType == null || !slotType.isAccessory() || model == null || model.getEquipment() == null
            || !slotType.matchesEquipmentTag(model.getEquipment().getTag())) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        int loadoutSlotIndex = toAccessoryLoadoutSlotIndex(slotIndex);
        if (ensureActiveEquipmentLoadout(state.getAccountId()) != null && loadoutSlotIndex >= 0) {
            ItemStack itemStack = itemStackResolver.resolve(entry);
            state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY,
                loadoutSlotIndex, readEquipmentInstanceId(itemStack), state.getAccountId());
        }
        EquipmentType.fromAccessorySlotIndex(slotIndex)
            .applyTo(astPlayer.getBukkit().getInventory(), itemStackResolver.resolve(entry));
        astPlayer.getBukkit().updateInventory();
    }

    private void updateAccessorySnapshotSlot(
        @NotNull PlayerInventoryState state,
        int accessorySlot,
        @Nullable ItemStack itemStack
    ) {
        if (!AccessorySlotLayout.isManagedSlot(accessorySlot)) {
            return;
        }
        int loadoutSlotIndex = toAccessoryLoadoutSlotIndex(accessorySlot);
        if (loadoutSlotIndex < 0) {
            return;
        }
        if (ensureActiveEquipmentLoadout(state.getAccountId()) == null) {
            return;
        }
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY,
            loadoutSlotIndex, readEquipmentInstanceId(itemStack), state.getAccountId());
    }

    private int toAccessoryLoadoutSlotIndex(int accessorySlot) {
        return AccessorySlotLayout.isManagedSlot(accessorySlot) ? accessorySlot - 1 : -1;
    }

    private int findAccessoryTargetSlot(@NotNull AstPlayer astPlayer, @Nullable String equipmentTag) {
        for (AccessorySlotType type : AccessorySlotType.values()) {
            if (!type.matchesEquipmentTag(equipmentTag)) {
                continue;
            }
            if (getAccessorySnapshotItem(astPlayer, type.getSlotIndex()) == null) {
                return type.getSlotIndex();
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------
    // canPlace / type resolution
    // ---------------------------------------------------------------

    public boolean canPlaceInEquipmentGuiSlot(
        @Nullable ItemStack itemStack,
        @Nullable EquipmentType equipmentType,
        @Nullable AccessorySlotType accessorySlotType
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        return canPlaceInEquipmentGuiSlot(itemReferenceResolver.resolve(itemStack), equipmentType, accessorySlotType);
    }

    /**
     * ItemStack の装備種別とプレイヤー条件を検証し、条件未達時は理由を通知します。
     *
     * @param astPlayer 判定対象プレイヤー
     * @param itemStack 判定対象 ItemStack
     * @param equipmentType 対象装備種別
     * @param accessorySlotType 種類別アクセサリ枠
     * @return 配置可能な場合は {@code true}
     */
    public boolean canPlaceInEquipmentGuiSlot(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack itemStack,
        @Nullable EquipmentType equipmentType,
        @Nullable AccessorySlotType accessorySlotType
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        return canPlaceInEquipmentGuiSlot(
            astPlayer,
            itemReferenceResolver.resolve(itemStack),
            equipmentType,
            accessorySlotType
        );
    }

    /**
     * inventory entry を正本として、指定装備スロットへ配置可能か判定します。
     *
     * @param entry 判定対象 entry
     * @param equipmentType 対象装備種別
     * @param accessorySlotType 種類別アクセサリ枠。防具・オフハンドの場合は null
     * @return 配置可能な場合 true
     */
    public boolean canPlaceInEquipmentGuiSlot(
        @Nullable InventoryEntryModel entry,
        @Nullable EquipmentType equipmentType,
        @Nullable AccessorySlotType accessorySlotType
    ) {
        if (entry == null) {
            return true;
        }
        return canPlaceInEquipmentGuiSlot(resolveItemReference(entry), equipmentType, accessorySlotType);
    }

    /**
     * inventory entry の装備種別とプレイヤー条件を検証し、条件未達時は理由を通知します。
     *
     * @param astPlayer 判定対象プレイヤー
     * @param entry 判定対象 entry
     * @param equipmentType 対象装備種別
     * @param accessorySlotType 種類別アクセサリ枠
     * @return 配置可能な場合は {@code true}
     */
    public boolean canPlaceInEquipmentGuiSlot(
        @NotNull AstPlayer astPlayer,
        @Nullable InventoryEntryModel entry,
        @Nullable EquipmentType equipmentType,
        @Nullable AccessorySlotType accessorySlotType
    ) {
        if (entry == null) {
            return true;
        }
        return canPlaceInEquipmentGuiSlot(
            astPlayer,
            resolveItemReference(entry),
            equipmentType,
            accessorySlotType
        );
    }

    private boolean canPlaceInEquipmentGuiSlot(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemReference reference,
        @Nullable EquipmentType equipmentType,
        @Nullable AccessorySlotType accessorySlotType
    ) {
        if (!canPlaceInEquipmentGuiSlot(reference, equipmentType, accessorySlotType)) {
            return false;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        return model != null
            && model.getEquipment() != null
            && EquipmentRequirementService.checkAndNotify(astPlayer, model.getEquipment());
    }

    private boolean canPlaceInEquipmentGuiSlot(
        @Nullable ItemReference reference,
        @Nullable EquipmentType equipmentType,
        @Nullable AccessorySlotType accessorySlotType
    ) {
        if (reference == null || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return false;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        if (model == null || model.getEquipment() == null) {
            return false;
        }
        ItemEquipmentSlot itemSlot = model.getEquipment().getSlot();
        if (accessorySlotType != null) {
            return itemSlot == ItemEquipmentSlot.ACCESSORY
                && accessorySlotType.matchesEquipmentTag(model.getEquipment().getTag());
        }
        return EquipmentType.fromItemEquipmentSlot(itemSlot) == equipmentType;
    }

    /**
     * inventory entry の equipment tag からアクセサリ種別を解決します。
     *
     * @param entry 解決対象 entry
     * @return 対応するアクセサリ種別。アクセサリでない場合や tag 未対応の場合は null
     */
    public @Nullable AccessorySlotType getAccessorySlotTypeForEntry(@Nullable InventoryEntryModel entry) {
        if (entry == null) {
            return null;
        }
        ItemModel model = resolveItemModel(entry);
        if (model == null || model.getEquipment() == null
            || model.getEquipment().getSlot() != ItemEquipmentSlot.ACCESSORY) {
            return null;
        }
        return AccessorySlotType.fromEquipmentTag(model.getEquipment().getTag());
    }

    public @NotNull EquipmentType getEquipmentTypeForItem(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return EquipmentType.UNSUPPORTED;
        }
        return getEquipmentType(resolveItemReference(itemStack));
    }

    /**
     * inventory entry を正本として、装備種別を解決します。
     *
     * @param entry 解決対象 entry
     * @return 解決できた装備種別。非装備または未対応の場合は {@link EquipmentType#UNSUPPORTED}
     */
    public @NotNull EquipmentType getEquipmentTypeForEntry(@Nullable InventoryEntryModel entry) {
        return getEquipmentType(resolveItemReference(entry));
    }

    private @NotNull EquipmentType getEquipmentType(@Nullable ItemReference reference) {
        if (reference == null || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return EquipmentType.UNSUPPORTED;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        if (model == null || model.getEquipment() == null) {
            return EquipmentType.UNSUPPORTED;
        }
        return EquipmentType.fromItemEquipmentSlot(model.getEquipment().getSlot());
    }

    // ---------------------------------------------------------------
    // return-to-owned
    // ---------------------------------------------------------------

    public @Nullable InventoryType returnItemToOwnedInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack itemStack
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        return returnResolvedItemToOwnedInventory(astPlayer, itemReferenceResolver.resolve(itemStack), itemStack.getAmount());
    }

    /**
     * inventory entry を正本として、対象アイテムを所有インベントリへ戻します。
     *
     * @param astPlayer 対象プレイヤー
     * @param entry 返却対象 entry
     * @return 返却先のインベントリ種別。返却できなかった場合は null
     */
    public @Nullable InventoryType returnItemToOwnedInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable InventoryEntryModel entry
    ) {
        if (entry == null) {
            return null;
        }
        return returnResolvedItemToOwnedInventory(
            astPlayer,
            resolveItemReference(entry),
            Math.max(1, (int) Math.min(Integer.MAX_VALUE, entry.getQuantity()))
        );
    }

    /**
     * 参照情報と個数を指定して、対象アイテムを所有インベントリへ返却します。
     *
     * @param astPlayer 対象プレイヤー
     * @param reference 返却対象アイテム参照
     * @param amount 返却個数
     * @return 返却先インベントリ種別。返却できない場合は null
     */
    public @Nullable InventoryType returnItemToOwnedInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemReference reference,
        int amount
    ) {
        return returnResolvedItemToOwnedInventory(astPlayer, reference, amount);
    }

    private @Nullable InventoryType returnResolvedItemToOwnedInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemReference reference,
        int amount
    ) {
        if (reference == null) {
            return null;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        if (model == null) {
            return null;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        ItemCategory category = ItemCategory.fromApiValue(reference.category());
        InventoryType targetType = resolveTargetInventoryType(model);
        InventoryModel targetInventory = ensureInventory(state, targetType,
            resolveSlotCapacity(targetType), state.getAccountId(), DEFAULT_PROFILE);

        boolean added = switch (category) {
            case EQUIPMENT -> addExistingInstanceEntry(state, targetInventory, model,
                InventoryInstanceType.EQUIPMENT, reference.equipmentInstanceId());
            case RUNE -> addExistingInstanceEntry(state, targetInventory, model,
                InventoryInstanceType.RUNE, reference.runeInstanceId());
            default -> {
                Set<Integer> usedSlots = collectUsedSlots(state, targetInventory);
                yield addStackedItems(state, targetInventory, model, amount, usedSlots) > 0;
            }
        };
        if (!added) {
            return null;
        }
        compactInventoryEntries(state, targetInventory.getInventoryId());
        autoSwitchDisplayedInventory(astPlayer, targetType);
        return targetType;
    }

    private boolean addExistingInstanceEntry(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull ItemModel model,
        @NotNull InventoryInstanceType instanceType,
        @Nullable String instanceIdValue
    ) {
        UUID instanceId = instanceIdValue == null ? null : parseUuidOrNull(instanceIdValue);
        if (instanceId == null) {
            return false;
        }
        Set<Integer> usedSlots = collectUsedSlots(state, inventory);
        Integer slot = findNextFreeSlot(inventory, usedSlots);
        if (slot == null) {
            return false;
        }
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .toList());
        entries.add(newEntry(inventory.getInventoryId(), slot, model.getCategory(),
            null, instanceType.getCode(), instanceId, 1L, null, state.getAccountId()));
        state.replaceEntries(inventory.getInventoryId(), entries);
        return true;
    }

    /**
     * 指定インベントリの entry を slot_index を連続値に詰め直します。
     *
     * @param inventoryId 対象 inventoryId
     * @param accountId 操作アカウント
     */
    public void compactInventoryEntries(@NotNull UUID inventoryId, @NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        if (state == null) {
            return;
        }
        compactInventoryEntries(state, inventoryId);
    }

    private void compactInventoryEntries(@NotNull PlayerInventoryState state, @NotNull UUID inventoryId) {
        InventoryModel inventory = state.findInventoryById(inventoryId);
        if (inventory != null && inventory.getInventoryType() == InventoryType.BAG) {
            return;
        }
        List<InventoryEntryModel> entries = state.snapshotEntries(inventoryId).stream()
            .filter(e -> !e.isDeleted())
            .sorted(Comparator.<InventoryEntryModel, Integer>comparing(
                e -> e.getSlotIndex() == null ? Integer.MAX_VALUE : e.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .toList();
        boolean unlimitedSlots = inventory != null && inventory.getInventoryType() == InventoryType.CURRENCY;
        if (inventory != null && inventory.getInventoryType() == InventoryType.CURRENCY) {
            entries = normalizeCurrencyEntries(state, inventory);
        }
        int next = NormalInventoryLayout.DB_SLOT_START;
        List<InventoryEntryModel> compacted = new ArrayList<>();
        boolean changed = false;
        for (InventoryEntryModel entry : entries) {
            if (!unlimitedSlots && inventory != null && next > inventoryCapacity(inventory)) {
                break;
            }
            Integer current = entry.getSlotIndex();
            if (current != null && current == next) {
                compacted.add(entry);
                next++;
                continue;
            }
            compacted.add(withSlot(entry, next, state.getAccountId()));
            changed = true;
            next++;
        }
        if (changed) {
            state.replaceEntries(inventoryId, compacted);
        }
    }

    private void autoSwitchDisplayedInventory(@NotNull AstPlayer astPlayer, @NotNull InventoryType targetType) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        if (targetType == InventoryType.CURRENCY) {
            return;
        }
        if (state.getDisplayedType() == targetType) {
            applyDisplayedInventoryToGui(astPlayer);
            return;
        }
        applyInventoryToGuiNextTick(astPlayer, targetType);
    }

    private void applyInventoryToGuiNextTick(@NotNull AstPlayer astPlayer, @NotNull InventoryType targetType) {
        io.github.maaasu.astralRecord.AstralRecord plugin = io.github.maaasu.astralRecord.AstralRecord.getInstance();
        if (plugin == null) {
            applyInventoryToGui(astPlayer, targetType);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!astPlayer.getBukkit().isOnline()) {
                return;
            }
            applyInventoryToGui(astPlayer, targetType);
        });
    }

    // ---------------------------------------------------------------
    // legacy save / flush integration (kept for backward-compatible callers)
    // ---------------------------------------------------------------

    /**
     * 旧 API: ログアウト直前の即時 flush 用フックです。新実装ではログアウト時の通常 save に統合済みのため、
     * 互換のためのスタブとして残しています。マーケットなど即時整合性が必要な処理は
     * {@link #saveNow(UUID)} を呼び、アカウント別保存キューへ登録してください。
     *
     * @param accountId 対象アカウントID
     */
    public void flushPendingCoalescedWrites(@NotNull UUID accountId) {
        // no-op: 旧実装の coalescer 窓は廃止。LOGOUT 時の save に統合済み。
    }

    /**
     * プラグイン停止時に未完了書き込みの完了待ちを行います。新実装では BukkitScheduler 経由の async save が
     * メインスレッド外で完結するため、特に待機処理は不要ですが、互換のためのフックを残しています。
     *
     * @param timeoutMillis 最大待機時間（ミリ秒）
     */
    public void awaitPendingWrites(long timeoutMillis) {
        persistence.awaitShutdown(timeoutMillis);
    }

    // ---------------------------------------------------------------
    // GUI helpers (Bukkit-side)
    // ---------------------------------------------------------------

    private void clearGuiInventory(@NotNull Player bukkitPlayer) {
        var inventory = bukkitPlayer.getInventory();
        inventory.clear();
        var storage = inventory.getStorageContents();
        Arrays.fill(storage, new ItemStack(Material.AIR));
        inventory.setStorageContents(storage);
        inventory.setArmorContents(new ItemStack[] {
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
        });
        inventory.setItemInMainHand(new ItemStack(Material.AIR));
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
    }

    private void resetGuiInteractionState(@NotNull PlayerInventoryState state) {
        state.setSelectedHotbarSlot(null);
        state.setHotbarShortcutMode(false);
    }

    private void clearManagedStorageSlots(@NotNull Player bukkitPlayer) {
        PlayerInventory inventory = bukkitPlayer.getInventory();
        for (int guiSlot = NormalInventoryLayout.GUI_SLOT_START;
             guiSlot <= NormalInventoryLayout.GUI_SLOT_END;
             guiSlot++) {
            setStorageItemIfChanged(inventory, guiSlot, null);
        }
    }

    private void purgeUnknownAstralItemsFromReflectedSlots(@NotNull AstPlayer astPlayer) {
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        boolean changed = false;

        for (int guiSlot = NormalInventoryLayout.GUI_SLOT_START;
             guiSlot <= NormalInventoryLayout.GUI_SLOT_END;
             guiSlot++) {
            changed |= purgeUnknownAstralItemAtStorageSlot(inventory, guiSlot);
        }
        for (int dbSlot = HotbarLayout.DB_SLOT_START; dbSlot <= HotbarLayout.DB_SLOT_END; dbSlot++) {
            changed |= purgeUnknownAstralItemAtStorageSlot(inventory, HotbarLayout.toBukkitSlot(dbSlot));
        }

        if (shouldPurgeUnknownAstralItem(inventory.getHelmet())) {
            inventory.setHelmet(new ItemStack(Material.AIR));
            changed = true;
        }
        if (shouldPurgeUnknownAstralItem(inventory.getChestplate())) {
            inventory.setChestplate(new ItemStack(Material.AIR));
            changed = true;
        }
        if (shouldPurgeUnknownAstralItem(inventory.getLeggings())) {
            inventory.setLeggings(new ItemStack(Material.AIR));
            changed = true;
        }
        if (shouldPurgeUnknownAstralItem(inventory.getBoots())) {
            inventory.setBoots(new ItemStack(Material.AIR));
            changed = true;
        }
        if (shouldPurgeUnknownAstralItem(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(new ItemStack(Material.AIR));
            changed = true;
        }

        if (changed) {
            astPlayer.getBukkit().updateInventory();
        }
    }

    private boolean purgeUnknownAstralItemAtStorageSlot(@NotNull PlayerInventory inventory, int bukkitSlot) {
        if (!shouldPurgeUnknownAstralItem(inventory.getItem(bukkitSlot))) {
            return false;
        }
        inventory.setItem(bukkitSlot, new ItemStack(Material.AIR));
        return true;
    }

    private boolean shouldPurgeUnknownAstralItem(@Nullable ItemStack itemStack) {
        ItemReference reference = itemReferenceResolver.resolve(itemStack);
        return reference != null && itemReferenceResolver.resolveItemModel(reference) == null;
    }

    private boolean setStorageItemIfChanged(
        @NotNull PlayerInventory inventory,
        int bukkitSlot,
        @Nullable ItemStack itemStack
    ) {
        ItemStack next = itemOrAir(itemStack);
        ItemStack current = inventory.getItem(bukkitSlot);
        if (isSameItemStack(current, next)) {
            return false;
        }
        inventory.setItem(bukkitSlot, next);
        return true;
    }

    private boolean isSameItemStack(@Nullable ItemStack current, @Nullable ItemStack next) {
        boolean currentEmpty = current == null || current.getType() == Material.AIR;
        boolean nextEmpty = next == null || next.getType() == Material.AIR;
        if (currentEmpty || nextEmpty) {
            return currentEmpty == nextEmpty;
        }
        return current.getAmount() == next.getAmount() && current.isSimilar(next);
    }

    private @Nullable ItemStack getEquipmentItem(@NotNull PlayerInventory inventory, @NotNull EquipmentType equipmentType) {
        return switch (equipmentType) {
            case MAIN_HAND -> inventory.getItemInMainHand();
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            case OFF_HAND -> inventory.getItemInOffHand();
            case UNSUPPORTED -> null;
        };
    }

    // ---------------------------------------------------------------
    // model / draft helpers
    // ---------------------------------------------------------------

    private @NotNull InventoryEntryModel newEntry(
        @NotNull UUID inventoryId,
        @Nullable Integer slotIndex,
        @NotNull String itemCategory,
        @Nullable String itemId,
        @Nullable String instanceType,
        @Nullable UUID instanceId,
        long quantity,
        @Nullable String metadataJson,
        @NotNull UUID actor
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            slotIndex,
            itemCategory,
            itemId,
            instanceType,
            instanceId,
            quantity,
            metadataJson,
            now,
            now,
            actor,
            actor,
            false
        );
    }

    private @NotNull InventoryEntryModel copyEntryWithSlot(
        @NotNull InventoryEntryModel entry,
        @NotNull UUID targetInventoryId,
        @Nullable Integer slotIndex,
        @NotNull UUID actor
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            targetInventoryId,
            slotIndex,
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            entry.getQuantity(),
            entry.getMetadataJson(),
            now,
            now,
            actor,
            actor,
            false
        );
    }

    private @NotNull InventoryEntryModel copyEntryToStorage(
        @NotNull InventoryEntryModel entry,
        @NotNull UUID storageInventoryId,
        int quantity,
        @NotNull UUID actor
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            storageInventoryId,
            nextStorageSlot(storageInventoryId),
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            Math.max(1L, quantity),
            storageMetadataJson(entry),
            now,
            now,
            actor,
            actor,
            false
        );
    }

    private @Nullable OwnedItemBatch collectOwnedItemBatch(
        @NotNull PlayerInventoryState state,
        int sourceBukkitSlot
    ) {
        InventoryEntryModel sourceEntry = sourceBukkitSlot >= 0 && sourceBukkitSlot <= 8
            ? findHotbarEntryBySlot(state, sourceBukkitSlot + 1)
            : findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
        if (sourceEntry == null) {
            return null;
        }
        if (!isStackableByItemId(sourceEntry)) {
            int amount = (int) Math.clamp(sourceEntry.getQuantity(), 1L, Integer.MAX_VALUE);
            boolean includesHotbar = sourceBukkitSlot >= 0 && sourceBukkitSlot <= 8;
            return new OwnedItemBatch(sourceEntry, Set.of(sourceEntry.getInventoryEntryId()), amount, includesHotbar);
        }

        Set<UUID> entryIds = new HashSet<>();
        long totalAmount = 0L;
        boolean includesHotbar = false;
        for (InventoryType inventoryType : List.of(InventoryType.BAG, InventoryType.HOTBAR)) {
            InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, inventoryType);
            if (inventory == null || !inventory.isEnabled()) {
                continue;
            }
            for (InventoryEntryModel entry : state.snapshotEntries(inventory.getInventoryId())) {
                if (entry.isDeleted() || !isSameStackableItem(entry, sourceEntry)) {
                    continue;
                }
                entryIds.add(entry.getInventoryEntryId());
                totalAmount += Math.max(0L, entry.getQuantity());
                includesHotbar |= inventoryType == InventoryType.HOTBAR;
            }
        }
        if (entryIds.isEmpty() || totalAmount <= 0L || totalAmount > Integer.MAX_VALUE) {
            return null;
        }
        return new OwnedItemBatch(sourceEntry, Set.copyOf(entryIds), (int) totalAmount, includesHotbar);
    }

    private void removeOwnedItemBatch(
        @NotNull PlayerInventoryState state,
        @NotNull OwnedItemBatch batch
    ) {
        for (InventoryType inventoryType : List.of(InventoryType.BAG, InventoryType.HOTBAR)) {
            InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, inventoryType);
            if (inventory == null) {
                continue;
            }
            List<InventoryEntryModel> activeEntries = state.snapshotEntries(inventory.getInventoryId()).stream()
                .filter(entry -> !entry.isDeleted())
                .toList();
            if (activeEntries.stream().noneMatch(entry -> batch.entryIds().contains(entry.getInventoryEntryId()))) {
                continue;
            }
            List<InventoryEntryModel> remaining = activeEntries.stream()
                .filter(entry -> !batch.entryIds().contains(entry.getInventoryEntryId()))
                .toList();
            if (inventoryType != InventoryType.BAG) {
                state.setSelectedHotbarSlot(null);
            }
            state.replaceEntries(inventory.getInventoryId(), remaining);
        }
    }

    private int findStackableStorageEntryIndex(
        @NotNull List<InventoryEntryModel> storageEntries,
        @NotNull InventoryEntryModel sourceEntry
    ) {
        if (!isStackableByItemId(sourceEntry)) {
            return -1;
        }
        for (int index = 0; index < storageEntries.size(); index++) {
            if (isSameStackableItem(storageEntries.get(index), sourceEntry)) {
                return index;
            }
        }
        return -1;
    }

    private boolean isSameStackableItem(
        @NotNull InventoryEntryModel existing,
        @NotNull InventoryEntryModel sourceEntry
    ) {
        if (!isStackableByItemId(existing) || !isStackableByItemId(sourceEntry)) {
            return false;
        }
        return existing.getItemCategory().equals(sourceEntry.getItemCategory())
            && existing.getItemId() != null
            && existing.getItemId().equals(sourceEntry.getItemId());
    }

    private boolean isStackableByItemId(@NotNull InventoryEntryModel entry) {
        return entry.getInstanceType() == null
            && entry.getInstanceId() == null
            && entry.getItemId() != null
            && !entry.getItemId().isBlank();
    }

    private record OwnedItemBatch(
        @NotNull InventoryEntryModel sourceEntry,
        @NotNull Set<UUID> entryIds,
        int amount,
        boolean includesHotbar
    ) {
    }

    private int nextStorageSlot(@NotNull UUID storageInventoryId) {
        int next = 1;
        for (PlayerInventoryState state : stateRegistry.all()) {
            InventoryModel inventory = state.findInventoryById(storageInventoryId);
            if (inventory == null) {
                continue;
            }
            for (InventoryEntryModel entry : state.snapshotEntries(storageInventoryId)) {
                if (entry.isDeleted() || entry.getSlotIndex() == null) {
                    continue;
                }
                next = Math.max(next, entry.getSlotIndex() + 1);
            }
            return next;
        }
        return next;
    }

    private @NotNull String storageMetadataJson(@NotNull InventoryEntryModel sourceEntry) {
        JsonObject object = new JsonObject();
        object.addProperty(STORAGE_ACQUIRED_AT_KEY, sourceEntry.getCreatedAt().toString());
        return object.toString();
    }

    private @Nullable StorageViewEntry toStorageViewEntry(@NotNull InventoryEntryModel entry) {
        ItemStack itemStack = itemStackResolver.resolve(entry);
        ItemModel itemModel = resolveItemModel(entry);
        if (itemStack == null || itemStack.getType() == Material.AIR || itemModel == null) {
            return null;
        }
        itemStack.setAmount((int) Math.min(Integer.MAX_VALUE, Math.max(1L, entry.getQuantity())));
        return new StorageViewEntry(entry, itemStack, itemModel, storageAcquiredAt(entry));
    }

    private @NotNull LocalDateTime storageAcquiredAt(@NotNull InventoryEntryModel entry) {
        String metadataJson = entry.getMetadataJson();
        if (metadataJson == null || metadataJson.isBlank()) {
            return entry.getCreatedAt();
        }
        try {
            JsonObject object = JsonParser.parseString(metadataJson).getAsJsonObject();
            if (object.has(STORAGE_ACQUIRED_AT_KEY) && !object.get(STORAGE_ACQUIRED_AT_KEY).isJsonNull()) {
                return LocalDateTime.parse(object.get(STORAGE_ACQUIRED_AT_KEY).getAsString());
            }
        } catch (JsonSyntaxException | IllegalStateException | java.time.format.DateTimeParseException ignored) {
            return entry.getCreatedAt();
        }
        return entry.getCreatedAt();
    }

    private boolean matchesStorageFilters(
        @NotNull StorageViewEntry entry,
        @NotNull StorageViewOptions options
    ) {
        String categoryFilter = options.categoryFilter();
        if (categoryFilter != null
            && !categoryFilter.isBlank()
            && !entry.itemModel().getCategory().equalsIgnoreCase(categoryFilter)) {
            return false;
        }
        String rarityFilter = options.rarityFilter();
        return rarityFilter == null
            || rarityFilter.isBlank()
            || entry.itemModel().getRarity().equalsIgnoreCase(rarityFilter);
    }

    private @NotNull Comparator<StorageViewEntry> storageComparator(@NotNull StorageViewOptions options) {
        Comparator<StorageViewEntry> primary = switch (options.sortKey()) {
            case STORED_ORDER -> Comparator.comparingInt(this::storageSequence);
            case ACQUIRED_ORDER -> Comparator.comparing(StorageViewEntry::acquiredAt);
            case RARITY -> Comparator.comparingInt(entry -> rarityRank(entry.itemModel().getRarity()));
            case SALE_VALUE -> Comparator.comparingInt(entry -> Math.max(0, entry.itemModel().getSaleValue()));
        };
        if (options.sortDirection() == StorageSortDirection.DESC) {
            primary = primary.reversed();
        }
        return primary.thenComparingInt(this::storageSequence)
            .thenComparing(entry -> entry.entry().getInventoryEntryId());
    }

    private int storageSequence(@NotNull StorageViewEntry entry) {
        Integer slotIndex = entry.entry().getSlotIndex();
        return slotIndex == null ? Integer.MAX_VALUE : slotIndex;
    }

    private int rarityRank(@NotNull String rarity) {
        return ItemRarity.rankOf(rarity);
    }

    private @NotNull InventoryEntryModel withSlot(
        @NotNull InventoryEntryModel entry,
        @Nullable Integer slotIndex,
        @NotNull UUID actor
    ) {
        if (entry.getSlotIndex() != null && entry.getSlotIndex().equals(slotIndex)) {
            return entry;
        }
        return new InventoryEntryModel(
            entry.getInventoryEntryId(),
            entry.getInventoryId(),
            slotIndex,
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            entry.getQuantity(),
            entry.getMetadataJson(),
            entry.getCreatedAt(),
            LocalDateTime.now(),
            entry.getCreatedBy(),
            actor,
            entry.isDeleted()
        );
    }

    private @NotNull InventoryEntryModel withQuantity(
        @NotNull InventoryEntryModel entry,
        long quantity,
        @NotNull UUID actor
    ) {
        return new InventoryEntryModel(
            entry.getInventoryEntryId(),
            entry.getInventoryId(),
            entry.getSlotIndex(),
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            quantity,
            entry.getMetadataJson(),
            entry.getCreatedAt(),
            LocalDateTime.now(),
            entry.getCreatedBy(),
            actor,
            entry.isDeleted()
        );
    }

    private @NotNull List<InventoryEntryModel> normalizeCurrencyEntries(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory
    ) {
        if (inventory.getInventoryType() != InventoryType.CURRENCY) {
            return state.snapshotEntries(inventory.getInventoryId()).stream()
                .filter(entry -> !entry.isDeleted())
                .toList();
        }

        List<InventoryEntryModel> entries = state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .sorted(Comparator.<InventoryEntryModel, Integer>comparing(
                entry -> entry.getSlotIndex() == null ? Integer.MAX_VALUE : entry.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .toList();
        Map<String, InventoryEntryModel> mergedByItemId = new LinkedHashMap<>();
        boolean changed = false;

        for (InventoryEntryModel entry : entries) {
            String itemId = entry.getItemId();
            if (itemId == null || itemId.isBlank()) {
                mergedByItemId.put("__entry__:" + entry.getInventoryEntryId(), entry);
                continue;
            }

            InventoryEntryModel existing = mergedByItemId.get(itemId);
            if (existing == null) {
                mergedByItemId.put(itemId, entry);
                continue;
            }

            mergedByItemId.put(
                itemId,
                withQuantity(existing, existing.getQuantity() + entry.getQuantity(), state.getAccountId())
            );
            changed = true;
        }

        List<InventoryEntryModel> normalized = new ArrayList<>();
        int slot = NormalInventoryLayout.DB_SLOT_START;
        for (InventoryEntryModel entry : mergedByItemId.values()) {
            InventoryEntryModel normalizedEntry = withSlot(entry, slot, state.getAccountId());
            if (normalizedEntry != entry) {
                changed = true;
            }
            normalized.add(normalizedEntry);
            slot++;
        }

        if (changed) {
            state.replaceEntries(inventory.getInventoryId(), normalized);
        }
        return normalized;
    }

    private long consumeItemAmountFromInventory(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory,
        @NotNull String itemId,
        long amount
    ) {
        if (amount <= 0L) {
            return 0L;
        }
        List<InventoryEntryModel> sourceEntries = inventory.getInventoryType() == InventoryType.CURRENCY
            ? normalizeCurrencyEntries(state, inventory)
            : state.snapshotEntries(inventory.getInventoryId()).stream()
                .filter(entry -> !entry.isDeleted())
                .toList();
        List<InventoryEntryModel> entries = new ArrayList<>(sourceEntries);
        long remaining = amount;
        for (int index = 0; index < entries.size() && remaining > 0L; index++) {
            InventoryEntryModel entry = entries.get(index);
            if (entry.getItemId() == null || !entry.getItemId().equalsIgnoreCase(itemId)) {
                continue;
            }
            long consumed = Math.min(entry.getQuantity(), remaining);
            long nextQuantity = entry.getQuantity() - consumed;
            remaining -= consumed;
            if (nextQuantity > 0L) {
                entries.set(index, withQuantity(entry, nextQuantity, state.getAccountId()));
            } else {
                entries.remove(index);
                index--;
            }
        }
        long consumedTotal = amount - remaining;
        if (consumedTotal > 0L) {
            state.replaceEntries(inventory.getInventoryId(), entries);
        }
        return consumedTotal;
    }

    private boolean isStackableEntry(@NotNull InventoryEntryModel entry, @NotNull ItemModel model, int maxStack) {
        boolean isCurrency = ItemCategory.fromApiValue(model.getCategory()) == ItemCategory.CURRENCY;
        if (!isCurrency && maxStack <= 1) return false;
        if (entry.getItemId() == null || !entry.getItemId().equals(model.getId())) return false;
        if (!entry.getItemCategory().equalsIgnoreCase(model.getCategory())) return false;
        if (entry.getInstanceType() != null || entry.getInstanceId() != null) return false;
        return isCurrency || entry.getQuantity() < maxStack;
    }

    private boolean hasCurrencyEntry(@NotNull List<InventoryEntryModel> entries, @NotNull String itemId) {
        return entries.stream()
            .filter(entry -> !entry.isDeleted())
            .anyMatch(entry -> entry.getItemId() != null && entry.getItemId().equalsIgnoreCase(itemId));
    }

    private @NotNull List<ItemStack> goldCurrencyDisplay(long amount) {
        ItemModel gold = itemService.loadItem(ItemService.DEFAULT_CURRENCY_ITEM_ID);
        if (gold == null) {
            return List.of();
        }
        return List.of(itemStackResolver.resolveCurrencyDisplay(gold, amount));
    }

    private @Nullable UUID createInstanceId(
        @NotNull ItemModel model,
        @NotNull UUID accountId,
        @NotNull InventoryInstanceType instanceType,
        @NotNull String source
    ) {
        String instanceId = switch (instanceType) {
            case EQUIPMENT -> {
                EquipmentInstance instance = itemService.createEquipmentInstance(
                    model.getId(), accountId.toString(), source, accountId.toString());
                yield instance == null ? null : instance.getEquipmentInstanceId();
            }
            case RUNE -> {
                RuneInstance instance = itemService.createRuneInstance(
                    model.getId(), accountId.toString(), source, accountId.toString());
                yield instance == null ? null : instance.getRuneInstanceId();
            }
        };
        return instanceId == null ? null : parseUuidOrNull(instanceId);
    }

    private @Nullable UUID parseUuidOrNull(@NotNull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private @NotNull ItemStack createManagedSlotFiller() {
        ItemStack itemStack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createOverflowSlotFiller() {
        ItemStack itemStack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("容量外", NamedTextColor.DARK_GRAY));
            meta.lore(List.of(Component.text("新しいアイテムは配置できません", NamedTextColor.GRAY)));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createOverflowItem(@NotNull ItemStack source) {
        ItemStack itemStack = source.clone();
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }
        List<Component> lore = meta.lore() == null
            ? new ArrayList<>()
            : new ArrayList<>(meta.lore());
        lore.add(Component.text("容量外: 移動・破棄のみ", NamedTextColor.RED));
        meta.lore(lore);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private @NotNull ItemStack createScrollIcon(boolean up, boolean enabled) {
        ItemStack itemStack = new ItemStack(enabled ? Material.ARROW : Material.GRAY_DYE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(up ? "上へスクロール" : "下へスクロール",
                enabled ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
            meta.lore(List.of(Component.text(
                enabled ? "クリックで1行移動" : "これ以上スクロールできません",
                enabled ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY
            )));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createInventoryInfoIcon(
        @NotNull List<InventoryEntryModel> entries,
        int capacity,
        int displayCapacity,
        int scrollRow
    ) {
        long used = entries.stream()
            .filter(entry -> !entry.isDeleted())
            .map(InventoryEntryModel::getSlotIndex)
            .filter(slotIndex -> slotIndex != null && NormalInventoryLayout.isManagedSlot(slotIndex, capacity))
            .count();
        long overflow = NormalInventoryLayout.overflowCount(entries, capacity);
        int totalRows = NormalInventoryLayout.totalRows(displayCapacity);
        int firstRow = scrollRow + 1;
        int lastRow = Math.min(totalRows, scrollRow + NormalInventoryLayout.VISIBLE_ROWS);
        ItemStack itemStack = new ItemStack(Material.CHEST);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("インベントリ情報", NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("使用: " + used + " / " + capacity, NamedTextColor.GRAY));
            if (overflow > 0) {
                lore.add(Component.text("容量外: " + overflow, NamedTextColor.RED));
            }
            lore.add(Component.text("表示行: " + firstRow + " - " + lastRow + " / " + totalRows,
                NamedTextColor.GRAY));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack itemOrAir(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return new ItemStack(Material.AIR);
        }
        return itemStack;
    }

    private @NotNull ItemStack emptyToAir(@Nullable ItemStack itemStack) {
        return itemOrAir(itemStack);
    }

    private @Nullable UUID readEquipmentInstanceId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        ItemReference reference = itemReferenceResolver.resolve(itemStack);
        if (reference == null || !reference.hasEquipmentInstanceId()) {
            return null;
        }
        return parseUuidOrNull(reference.equipmentInstanceId());
    }

    private ItemModel resolveItemModel(@NotNull String itemId) {
        ItemModel loaded = itemService.findLoadedById(itemId);
        if (loaded != null) {
            return loaded;
        }
        return itemService.loadItem(itemId);
    }

    private @Nullable ItemModel resolveItemModel(@NotNull InventoryEntryModel entry) {
        return itemReferenceResolver.resolveItemModel(resolveItemReference(entry));
    }

    private @Nullable ItemReference resolveItemReference(@Nullable ItemStack itemStack) {
        return itemReferenceResolver.resolve(itemStack);
    }

    private @Nullable ItemReference resolveItemReference(@Nullable InventoryEntryModel entry) {
        if (entry == null) {
            return null;
        }
        if (entry.getItemId() != null && !entry.getItemId().isBlank()) {
            String category = entry.getItemCategory();
            if (category == null || category.isBlank()) {
                ItemModel model = resolveItemModel(entry.getItemId());
                if (model == null) {
                    return null;
                }
                category = model.getCategory();
            }
            return new ItemReference(entry.getItemId(), category, null, null);
        }
        InventoryInstanceType instanceType = InventoryInstanceType.fromCode(entry.getInstanceType());
        if (instanceType == null || entry.getInstanceId() == null) {
            return null;
        }
        return switch (instanceType) {
            case EQUIPMENT -> {
                EquipmentInstance instance = itemService.findEquipmentInstanceById(entry.getInstanceId().toString());
                ItemModel model = instance == null ? null : resolveItemModel(instance.getItemId());
                yield model == null ? null : new ItemReference(
                    model.getId(),
                    model.getCategory(),
                    instance.getEquipmentInstanceId(),
                    null
                );
            }
            case RUNE -> {
                RuneInstance instance = itemService.findRuneInstanceById(entry.getInstanceId().toString());
                ItemModel model = instance == null ? null : resolveItemModel(instance.getItemId());
                yield model == null ? null : new ItemReference(
                    model.getId(),
                    model.getCategory(),
                    null,
                    instance.getRuneInstanceId()
                );
            }
        };
    }

    private int toHotbarDbSlot(@NotNull AstPlayer astPlayer, @NotNull EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
            ? HotbarLayout.DB_SLOT_OFFHAND
            : HotbarLayout.toDbSlot(astPlayer.getBukkit().getInventory().getHeldItemSlot());
    }

    private @NotNull InventoryEntryModel toInventoryEntry(@NotNull EquipmentLoadoutSlotModel slot) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            slot.getEquipmentLoadoutSlotId(),
            slot.getEquipmentLoadoutId(),
            slot.getSlotIndex(),
            ItemCategory.EQUIPMENT.name(),
            null,
            InventoryInstanceType.EQUIPMENT.getCode(),
            slot.getEquipmentInstanceId(),
            1L,
            null,
            now,
            now,
            slot.getCreatedBy(),
            slot.getUpdatedBy(),
            false
        );
    }

    private void applyLoadoutSlot(
        @NotNull PlayerInventory inventory,
        @NotNull String slotType,
        int slotIndex,
        @NotNull ItemStack itemStack
    ) {
        switch (slotType.toUpperCase(java.util.Locale.ROOT)) {
            case "WEAPON" -> {
            }
            case SLOT_TYPE_HEAD -> inventory.setHelmet(itemStack);
            case SLOT_TYPE_CHEST -> inventory.setChestplate(itemStack);
            case SLOT_TYPE_LEGS -> inventory.setLeggings(itemStack);
            case SLOT_TYPE_FEET -> inventory.setBoots(itemStack);
            case SLOT_TYPE_ACCESSORY -> {
                if (slotIndex == 0) {
                    inventory.setItemInOffHand(itemStack);
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------
    // slot util
    // ---------------------------------------------------------------

    private @NotNull InventoryType resolveTargetInventoryType(@NotNull ItemModel model) {
        return switch (ItemCategory.fromApiValue(model.getCategory())) {
            case CURRENCY -> InventoryType.CURRENCY;
            default -> InventoryType.BAG;
        };
    }

    private @NotNull InventoryType resolveTargetInventoryType(@NotNull InventoryEntryModel entry) {
        return switch (ItemCategory.fromApiValue(entry.getItemCategory())) {
            case CURRENCY -> InventoryType.CURRENCY;
            default -> InventoryType.BAG;
        };
    }

    private @NotNull Set<Integer> collectUsedSlots(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory
    ) {
        List<InventoryEntryModel> entries = state.snapshotEntries(inventory.getInventoryId());
        if (inventory.getInventoryType() != InventoryType.CURRENCY) {
            return NormalInventoryLayout.collectUsedSlots(entries, inventoryCapacity(inventory));
        }
        Set<Integer> usedSlots = new HashSet<>();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex != null && slotIndex > 0 && !entry.isDeleted()) {
                usedSlots.add(slotIndex);
            }
        }
        return usedSlots;
    }

    private @Nullable Integer findNextFreeSlot(@NotNull InventoryModel inventory, @NotNull Set<Integer> usedSlots) {
        if (inventory.getInventoryType() != InventoryType.CURRENCY) {
            return NormalInventoryLayout.findNextFreeSlot(usedSlots, inventoryCapacity(inventory));
        }
        int candidate = NormalInventoryLayout.DB_SLOT_START;
        while (candidate > 0) {
            if (!usedSlots.contains(candidate)) {
                return candidate;
            }
            candidate++;
        }
        return null;
    }

    private @Nullable Integer resolveSlotCapacity(@NotNull InventoryType inventoryType) {
        if (inventoryType == InventoryType.CURRENCY || inventoryType == InventoryType.BAG) {
            return null;
        }
        return inventoryType.isSlotted() ? NormalInventoryLayout.DEFAULT_CAPACITY : null;
    }

    private int inventoryCapacity(@NotNull InventoryModel inventory) {
        if (inventory.getInventoryType() == InventoryType.BAG) {
            PlayerInventoryState state = stateRegistry.get(inventory.getAccountId());
            return state == null ? 0 : state.getBagSlotCapacity();
        }
        return NormalInventoryLayout.effectiveCapacity(
            inventory.getInventoryType(), inventory.getSlotCapacity());
    }

    /**
     * API 側で生成済みのインスタンス参照です。
     *
     * @param instanceType インスタンス種別
     * @param instanceId インスタンス ID
     */
    public record PreparedInventoryInstance(
        @NotNull InventoryInstanceType instanceType,
        @NotNull UUID instanceId
    ) {
    }

    /**
     * インベントリ公開前に解決した報酬です。
     *
     * @param model アイテム定義
     * @param amount 追加数
     * @param instances 装備品またはルーンの生成済みインスタンス
     */
    public record PreparedInventoryReward(
        @NotNull ItemModel model,
        int amount,
        @NotNull List<PreparedInventoryInstance> instances
    ) {
        public PreparedInventoryReward {
            instances = List.copyOf(instances);
        }
    }

    /**
     * 1 回の報酬追加で増加した数量を識別する差分です。
     *
     * @param entryId 追加時の entry ID
     * @param instanceId インスタンス ID。スタック品では {@code null}
     * @param quantity 増加数量
     */
    public record InventoryGrantMutation(
        @NotNull UUID entryId,
        @Nullable UUID instanceId,
        long quantity
    ) {
    }

    /**
     * 報酬追加の補償に使用する受取票です。
     *
     * @param accountId 対象アカウント ID
     * @param mutations 今回の追加で発生した差分
     */
    public record InventoryGrantReceipt(
        @NotNull UUID accountId,
        @NotNull List<InventoryGrantMutation> mutations
    ) {
        public InventoryGrantReceipt {
            mutations = List.copyOf(mutations);
        }
    }

    private record LocatedGrantMutation(
        @NotNull UUID inventoryId,
        @NotNull InventoryEntryModel entry,
        @NotNull InventoryGrantMutation mutation
    ) {
    }

    /**
     * inventory entry の補償用スナップショットです。
     *
     * @param accountId 対象アカウントID
     * @param entriesByInventoryId inventoryId ごとの entry 一覧
     * @param displayedType 取得時点の表示 inventory 種別
     * @param dirty 取得時点の未保存状態
     */
    public record InventoryStateSnapshot(
        @NotNull UUID accountId,
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventoryId,
        @NotNull InventoryType displayedType,
        boolean dirty
    ) {
        public InventoryStateSnapshot {
            Map<UUID, List<InventoryEntryModel>> immutableEntries = new LinkedHashMap<>();
            entriesByInventoryId.forEach((inventoryId, entries) ->
                immutableEntries.put(inventoryId, List.copyOf(entries))
            );
            entriesByInventoryId = Map.copyOf(immutableEntries);
        }
    }
}

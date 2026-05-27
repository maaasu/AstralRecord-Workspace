package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
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
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * インベントリ機能のビジネスロジックを担うサービス。
 * <p>
 * すべてのインベントリ・装備ロードアウト状態は {@link PlayerInventoryState} に集約され、
 * 本サービスはその state を読み書きするだけで API 通信は行いません。
 * API への反映は {@link InventoryPersistence} 経由で 60 秒間隔のオートセーブまたはログアウト時に行われます。
 * マーケットなど整合性が必要な操作は {@link InventoryPersistence#saveNow(PlayerInventoryState)} を直接呼び出してください。
 */
public class InventoryService {
    private static final InventoryProfile DEFAULT_PROFILE = InventoryProfile.GAME;
    private static final String DEFAULT_LOADOUT_NAME = "Default";
    private static final String SLOT_TYPE_HEAD = "HEAD";
    private static final String SLOT_TYPE_CHEST = "CHEST";
    private static final String SLOT_TYPE_LEGS = "LEGS";
    private static final String SLOT_TYPE_FEET = "FEET";
    private static final String SLOT_TYPE_ACCESSORY = "ACCESSORY";

    private final InventoryRepository inventoryRepository;
    private final EquipmentLoadoutRepository equipmentLoadoutRepository;
    private final ItemService itemService;
    private final InventoryItemStackResolver itemStackResolver;
    private final InventorySnapshotCodec snapshotCodec;
    private final HotbarRenderer hotbarRenderer;
    private final PlayerInventoryStateRegistry stateRegistry;
    private final InventoryPersistence persistence;
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
     */
    public InventoryService(
        InventoryRepository inventoryRepository,
        EquipmentLoadoutRepository equipmentLoadoutRepository,
        ItemService itemService,
        ItemStackFactory itemStackFactory,
        PlayerInventoryStateRegistry stateRegistry,
        InventoryPersistence persistence
    ) {
        this.inventoryRepository = inventoryRepository;
        this.equipmentLoadoutRepository = equipmentLoadoutRepository;
        this.itemService = itemService;
        this.itemStackResolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        this.snapshotCodec = new InventorySnapshotCodec();
        this.hotbarRenderer = new HotbarRenderer(itemStackResolver);
        this.stateRegistry = stateRegistry;
        this.persistence = persistence;
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
     * 永続化サービスを返します。即時セーブ等の拡張点として外部から利用してください。
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
        List<InventoryEntryModel> entries = new ArrayList<>(state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(e -> !e.isDeleted())
            .toList());
        int remaining = amount;
        int granted = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (remaining <= 0) {
                break;
            }
            InventoryEntryModel entry = entries.get(index);
            if (!isStackableEntry(entry, model, maxStack)) {
                continue;
            }
            long room = maxStack - entry.getQuantity();
            if (room <= 0) {
                continue;
            }
            int addAmount = (int) Math.min(room, remaining);
            entries.set(index, withQuantity(entry, entry.getQuantity() + addAmount, accountId));
            granted += addAmount;
            remaining -= addAmount;
        }
        while (remaining > 0) {
            Integer slot = findNextFreeSlot(inventory, usedSlots);
            if (slot == null) {
                break;
            }
            int stackAmount = Math.min(maxStack, remaining);
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
    // Builder snapshot
    // ---------------------------------------------------------------

    public void saveBuilderInventorySnapshot(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        InventoryModel inventory = ensureInventory(
            state,
            InventoryType.NORMAL,
            null,
            state.getAccountId(),
            InventoryProfile.BUILDER
        );
        ItemStack[] contents = astPlayer.getBukkit().getInventory().getContents();
        String metadataJson = snapshotCodec.encodeBuilder(contents, astPlayer.getBukkit().getGameMode());
        state.updateInventoryMetadata(inventory.getInventoryId(), metadataJson, state.getAccountId());
    }

    public void applyBuilderInventoryToGui(@NotNull AstPlayer astPlayer) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        clearGuiInventory(astPlayer.getBukkit());
        InventoryModel inventory = state.findInventory(InventoryProfile.BUILDER, InventoryType.NORMAL);
        if (inventory != null) {
            applyBuilderSnapshot(astPlayer, inventory.getMetadataJson());
        }
    }

    private void applyBuilderSnapshot(@NotNull AstPlayer astPlayer, @Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        InventorySnapshotCodec.BuilderSnapshot snapshot = snapshotCodec.decodeBuilder(metadataJson);
        if (snapshot == null) {
            return;
        }
        astPlayer.getBukkit().getInventory().setContents(snapshot.contents());
        if (snapshot.gameMode() != null) {
            astPlayer.getBukkit().setGameMode(snapshot.gameMode());
        }
        astPlayer.getBukkit().updateInventory();
    }

    // ---------------------------------------------------------------
    // GUI rendering
    // ---------------------------------------------------------------

    public void applyInventoriesToGui(@NotNull AstPlayer astPlayer) {
        applyInventoryToGui(astPlayer, InventoryType.NORMAL);
        if (!applyActiveEquipmentLoadoutToGui(astPlayer)) {
            applyEquipSlotInventoryToGui(astPlayer);
            applyAccessorySlotInventoryToGui(astPlayer);
        }
        applyHotbarInventoryToGui(astPlayer);
    }

    public void applyInventoryToGui(@NotNull AstPlayer astPlayer, @NotNull InventoryType inventoryType) {
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
        saveEquipSlotSnapshot(astPlayer);
        saveAccessorySlotSnapshot(astPlayer);
        syncCurrentEquipmentState(astPlayer);
        state.setDisplayedType(inventoryType);

        InventoryModel selectedInventory = state.findInventory(DEFAULT_PROFILE, inventoryType);
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

        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex == null || !NormalInventoryLayout.isManagedSlot(slotIndex) || entry.isDeleted()) {
                continue;
            }
            int guiSlotIndex = NormalInventoryLayout.toGuiSlotIndex(slotIndex);
            if (guiSlotIndex < 0 || guiSlotIndex >= playerInventory.getStorageContents().length) {
                continue;
            }
            ItemStack itemStack = itemStackResolver.resolve(entry);
            if (itemStack == null) {
                continue;
            }
            itemByGuiSlot.put(guiSlotIndex, itemStack);
        }
        for (int dbSlot = NormalInventoryLayout.DB_SLOT_START; dbSlot <= NormalInventoryLayout.DB_SLOT_END; dbSlot++) {
            int guiSlot = NormalInventoryLayout.toGuiSlotIndex(dbSlot);
            setStorageItemIfChanged(playerInventory, guiSlot, itemByGuiSlot.get(guiSlot));
        }
    }

    public @NotNull InventoryType getDisplayedInventoryType(@NotNull UUID accountId) {
        PlayerInventoryState state = getState(accountId);
        return state == null ? InventoryType.NORMAL : state.getDisplayedType();
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
            return List.of();
        }
        return state.snapshotEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .sorted(Comparator.<InventoryEntryModel, Integer>comparing(
                entry -> entry.getSlotIndex() == null ? Integer.MAX_VALUE : entry.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .map(itemStackResolver::resolve)
            .filter(itemStack -> itemStack != null && itemStack.getType() != Material.AIR)
            .toList();
    }

    public InventoryType resolveInventoryType(@NotNull ItemModel model) {
        return resolveTargetInventoryType(model);
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
        applyLoadoutDiff(state,
            inventory.getHelmet(),
            inventory.getChestplate(),
            inventory.getLeggings(),
            inventory.getBoots(),
            inventory.getItemInOffHand(),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_NECKLACE),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_RING),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_EARRING),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BRACELET),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BELT),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_CHARM)
        );
    }

    private void applyLoadoutDiff(
        @NotNull PlayerInventoryState state,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @Nullable ItemStack offHand,
        @Nullable ItemStack accessory2,
        @Nullable ItemStack accessory3,
        @Nullable ItemStack accessory4,
        @Nullable ItemStack accessory5,
        @Nullable ItemStack accessory6,
        @Nullable ItemStack accessory7
    ) {
        if (ensureActiveEquipmentLoadout(state.getAccountId()) == null) {
            return;
        }
        UUID actor = state.getAccountId();
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_HEAD, 0, readEquipmentInstanceId(head), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_CHEST, 0, readEquipmentInstanceId(chest), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_LEGS, 0, readEquipmentInstanceId(legs), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_FEET, 0, readEquipmentInstanceId(feet), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY, 0, readEquipmentInstanceId(offHand), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY, 1, readEquipmentInstanceId(accessory2), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY, 2, readEquipmentInstanceId(accessory3), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY, 3, readEquipmentInstanceId(accessory4), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY, 4, readEquipmentInstanceId(accessory5), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY, 5, readEquipmentInstanceId(accessory6), actor);
        state.upsertActiveLoadoutSlot(DEFAULT_PROFILE, SLOT_TYPE_ACCESSORY, 6, readEquipmentInstanceId(accessory7), actor);
    }

    /**
     * 装備 GUI の内容を Bukkit 装備欄と state へ反映します。
     */
    public void saveEquipmentGui(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @Nullable ItemStack offHand,
        @Nullable ItemStack accessory2,
        @Nullable ItemStack accessory3,
        @Nullable ItemStack accessory4,
        @Nullable ItemStack accessory5,
        @Nullable ItemStack accessory6,
        @Nullable ItemStack accessory7
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        PlayerInventory bukkitInventory = astPlayer.getBukkit().getInventory();
        bukkitInventory.setHelmet(itemOrAir(head));
        bukkitInventory.setChestplate(itemOrAir(chest));
        bukkitInventory.setLeggings(itemOrAir(legs));
        bukkitInventory.setBoots(itemOrAir(feet));
        bukkitInventory.setItemInOffHand(itemOrAir(offHand));

        ItemStack[] equipSnapshot = new ItemStack[EquipSlotLayout.SLOT_MAX + 1];
        equipSnapshot[EquipSlotLayout.SLOT_HEAD] = itemOrAir(head);
        equipSnapshot[EquipSlotLayout.SLOT_CHEST] = itemOrAir(chest);
        equipSnapshot[EquipSlotLayout.SLOT_LEGS] = itemOrAir(legs);
        equipSnapshot[EquipSlotLayout.SLOT_FEET] = itemOrAir(feet);
        InventoryModel equipInventory = ensureInventory(state, InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX, state.getAccountId(), DEFAULT_PROFILE);
        state.updateInventoryMetadata(equipInventory.getInventoryId(),
            snapshotCodec.encode(equipSnapshot), state.getAccountId());

        applyLoadoutDiff(state, head, chest, legs, feet, offHand,
            accessory2, accessory3, accessory4, accessory5, accessory6, accessory7);

        astPlayer.getBukkit().updateInventory();
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
        int hotbarSlot = hand == EquipmentSlot.OFF_HAND
            ? HotbarLayout.DB_SLOT_OFFHAND
            : HotbarLayout.toDbSlot(astPlayer.getBukkit().getInventory().getHeldItemSlot());
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
            Set<Integer> usedSlots = NormalInventoryLayout.collectUsedSlots(targetEntries);
            Integer targetSlot = NormalInventoryLayout.findNextFreeSlot(usedSlots);
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

        if (state.getDisplayedType() == targetType) {
            applyDisplayedInventoryToGui(astPlayer);
        }
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
        if (state.isHotbarShortcutMode()) {
            hotbarRenderer.renderShortcutIcons(astPlayer, state.getDisplayedType());
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
        hotbarRenderer.renderHotbarInventory(astPlayer, bySlot, state.getDisplayedType(), state.getSelectedHotbarSlot());
    }

    public void setHotbarShortcutMode(@NotNull AstPlayer astPlayer, boolean on) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        if (state.setHotbarShortcutMode(on)) {
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

    public boolean handleHotbarShortcutClick(@NotNull AstPlayer astPlayer, int bukkitSlot) {
        if (bukkitSlot == HotbarRenderer.SHORTCUT_INVENTORY_CYCLE_SLOT) {
            PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
            if (state == null) {
                return false;
            }
            InventoryType target = switch (state.getDisplayedType()) {
                case NORMAL -> InventoryType.EQUIPMENT;
                case EQUIPMENT -> InventoryType.RUNE;
                default -> InventoryType.NORMAL;
            };
            applyInventoryToGui(astPlayer, target);
            return true;
        }
        if (!isHotbarShortcutMode(astPlayer)) {
            return false;
        }
        if (bukkitSlot == HotbarRenderer.SHORTCUT_CLOSE_SLOT) {
            astPlayer.getBukkit().closeInventory();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------
    // equip / assign clicked item
    // ---------------------------------------------------------------

    public boolean equipOrAssignClickedItem(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemStack clickedItem,
        int sourceBukkitSlot
    ) {
        if (clickedItem.getType() == Material.AIR) {
            return false;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        InventoryEntryModel sourceEntry = findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
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
            if (itemSlot == ItemEquipmentSlot.HEAD || itemSlot == ItemEquipmentSlot.CHEST
                || itemSlot == ItemEquipmentSlot.LEGS || itemSlot == ItemEquipmentSlot.FEET) {
                return equipArmorItem(astPlayer, state, sourceEntry, sourceItem, sourceBukkitSlot,
                    EquipmentType.fromItemEquipmentSlot(itemSlot));
            }
            if (itemSlot == ItemEquipmentSlot.ACCESSORY) {
                return equipAccessoryItem(astPlayer, state, sourceEntry, sourceItem, sourceBukkitSlot);
            }
            if (itemSlot == ItemEquipmentSlot.WEAPON || itemSlot == ItemEquipmentSlot.TOOL) {
                return assignHotbarItem(astPlayer, state, sourceEntry, sourceBukkitSlot);
            }
            return false;
        }
        if (category == ItemCategory.BUNDLE || category == ItemCategory.CONSUMABLE) {
            return assignHotbarItem(astPlayer, state, sourceEntry, sourceBukkitSlot);
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
        int accessorySlot = findAccessoryTargetSlot(astPlayer);
        if (!AccessorySlotLayout.isManagedSlot(accessorySlot)) {
            return false;
        }
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        if (accessorySlot == AccessorySlotLayout.SLOT_OFF_HAND) {
            ItemStack previous = inventory.getItemInOffHand();
            inventory.setItemInOffHand(clickedItem.clone());
            inventory.setItem(sourceBukkitSlot, emptyToAir(previous));
            saveAccessorySlotSnapshot(astPlayer);
        } else {
            if (getAccessorySnapshotItem(astPlayer, accessorySlot) != null) {
                return false;
            }
            inventory.setItem(sourceBukkitSlot, new ItemStack(Material.AIR));
            updateAccessorySnapshotSlot(state, accessorySlot, clickedItem.clone());
        }
        removeDisplayedEntryAfterMove(state, sourceEntry);
        if (accessorySlot == AccessorySlotLayout.SLOT_OFF_HAND) {
            returnReplacedItemToOwnedInventory(astPlayer, inventory.getItem(sourceBukkitSlot));
        }
        syncCurrentEquipmentState(astPlayer);
        requestManagedInventoryUiRefresh(astPlayer, false);
        return true;
    }

    private boolean assignHotbarItem(
        @NotNull AstPlayer astPlayer,
        @NotNull PlayerInventoryState state,
        @NotNull InventoryEntryModel sourceEntry,
        int sourceBukkitSlot
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

    public boolean moveDisplayedItemToEquipmentGui(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot,
        @Nullable ItemStack replacedItem
    ) {
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return false;
        }
        boolean hasReplacedItem = replacedItem != null && replacedItem.getType() != Material.AIR;
        if (hasReplacedItem
            && (ItemStackFactory.getAstralItemId(replacedItem) == null || ItemStackFactory.getCategory(replacedItem) == null)) {
            return false;
        }
        InventoryEntryModel sourceEntry = findDisplayedEntryAtBukkitSlot(state, sourceBukkitSlot);
        if (sourceEntry == null) {
            return false;
        }
        removeDisplayedEntryAfterMove(state, sourceEntry);
        if (hasReplacedItem && returnItemToOwnedInventory(astPlayer, replacedItem.clone()) == null) {
            return false;
        }
        requestManagedInventoryUiRefresh(astPlayer, false);
        return true;
    }

    public @Nullable ItemStack takeDisplayedItem(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        return takeDisplayedItemAmount(astPlayer, sourceBukkitSlot, 0);
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
        List<InventoryEntryModel> compacted = new ArrayList<>(remaining.size());
        int next = NormalInventoryLayout.DB_SLOT_START;
        for (InventoryEntryModel entry : remaining) {
            compacted.add(withSlot(entry, next, state.getAccountId()));
            next++;
        }
        state.replaceEntries(sourceEntry.getInventoryId(), compacted);
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
        InventoryType displayedType = state.getDisplayedType();
        if (displayedType == InventoryType.CURRENCY) {
            clearManagedStorageSlots(astPlayer.getBukkit());
            return;
        }
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, displayedType);
        applyInventoryToBukkit(astPlayer.getBukkit(), state, inventory);
    }

    private @Nullable InventoryEntryModel findDisplayedEntryAtBukkitSlot(
        @NotNull PlayerInventoryState state,
        int sourceBukkitSlot
    ) {
        if (!NormalInventoryLayout.isManagedGuiSlot(sourceBukkitSlot)) {
            return null;
        }
        InventoryType displayedType = state.getDisplayedType();
        int dbSlot = NormalInventoryLayout.toDbSlotIndex(sourceBukkitSlot);
        InventoryModel inventory = state.findInventory(DEFAULT_PROFILE, displayedType);
        if (inventory == null) {
            return null;
        }
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
        snapshot[AccessorySlotLayout.SLOT_NECKLACE] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_NECKLACE));
        snapshot[AccessorySlotLayout.SLOT_RING] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_RING));
        snapshot[AccessorySlotLayout.SLOT_EARRING] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_EARRING));
        snapshot[AccessorySlotLayout.SLOT_BRACELET] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BRACELET));
        snapshot[AccessorySlotLayout.SLOT_BELT] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BELT));
        snapshot[AccessorySlotLayout.SLOT_CHARM] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_CHARM));
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

    public void moveToAccessorySlot(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel entry,
        int slotIndex
    ) {
        if (!AccessorySlotLayout.isManagedSlot(slotIndex)) {
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
        if (accessorySlot == AccessorySlotLayout.SLOT_OFF_HAND) return 0;
        if (accessorySlot == AccessorySlotLayout.SLOT_NECKLACE) return 1;
        if (accessorySlot == AccessorySlotLayout.SLOT_RING) return 2;
        if (accessorySlot == AccessorySlotLayout.SLOT_EARRING) return 3;
        if (accessorySlot == AccessorySlotLayout.SLOT_BRACELET) return 4;
        if (accessorySlot == AccessorySlotLayout.SLOT_BELT) return 5;
        if (accessorySlot == AccessorySlotLayout.SLOT_CHARM) return 6;
        return -1;
    }

    private int findAccessoryTargetSlot(@NotNull AstPlayer astPlayer) {
        ItemStack offhand = astPlayer.getBukkit().getInventory().getItemInOffHand();
        if (offhand.getType() == Material.AIR) {
            return AccessorySlotLayout.SLOT_OFF_HAND;
        }
        for (int slot = AccessorySlotLayout.SLOT_NECKLACE; slot <= AccessorySlotLayout.SLOT_RING; slot++) {
            if (getAccessorySnapshotItem(astPlayer, slot) == null) {
                return slot;
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
        boolean extendedAccessory
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        String category = ItemStackFactory.getCategory(itemStack);
        if (itemId == null || category == null || ItemCategory.fromApiValue(category) != ItemCategory.EQUIPMENT) {
            return false;
        }
        ItemModel model = resolveItemModel(itemId);
        if (model == null || model.getEquipment() == null) {
            return false;
        }
        ItemEquipmentSlot itemSlot = model.getEquipment().getSlot();
        if (extendedAccessory) {
            return itemSlot == ItemEquipmentSlot.ACCESSORY;
        }
        return EquipmentType.fromItemEquipmentSlot(itemSlot) == equipmentType;
    }

    public @NotNull EquipmentType getEquipmentTypeForItem(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return EquipmentType.UNSUPPORTED;
        }
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        String category = ItemStackFactory.getCategory(itemStack);
        if (itemId == null || category == null || ItemCategory.fromApiValue(category) != ItemCategory.EQUIPMENT) {
            return EquipmentType.UNSUPPORTED;
        }
        ItemModel model = resolveItemModel(itemId);
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
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        String categoryCode = ItemStackFactory.getCategory(itemStack);
        if (itemId == null || categoryCode == null) {
            return null;
        }
        ItemModel model = resolveItemModel(itemId);
        if (model == null) {
            return null;
        }
        PlayerInventoryState state = getState(astPlayer.getAccount().getUuid());
        if (state == null) {
            return null;
        }
        ItemCategory category = ItemCategory.fromApiValue(categoryCode);
        InventoryType targetType = resolveTargetInventoryType(model);
        InventoryModel targetInventory = ensureInventory(state, targetType,
            resolveSlotCapacity(targetType), state.getAccountId(), DEFAULT_PROFILE);

        boolean added = switch (category) {
            case EQUIPMENT -> addExistingInstanceEntry(state, targetInventory, model,
                InventoryInstanceType.EQUIPMENT, ItemStackFactory.getEquipmentInstanceId(itemStack));
            case RUNE -> addExistingInstanceEntry(state, targetInventory, model,
                InventoryInstanceType.RUNE, ItemStackFactory.getRuneInstanceId(itemStack));
            default -> {
                int amount = Math.max(1, itemStack.getAmount());
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
        List<InventoryEntryModel> entries = state.snapshotEntries(inventoryId).stream()
            .filter(e -> !e.isDeleted())
            .sorted(Comparator.<InventoryEntryModel, Integer>comparing(
                e -> e.getSlotIndex() == null ? Integer.MAX_VALUE : e.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .toList();
        InventoryModel inventory = state.findInventoryById(inventoryId);
        boolean unlimitedSlots = inventory != null && inventory.getInventoryType() == InventoryType.CURRENCY;
        int next = NormalInventoryLayout.DB_SLOT_START;
        List<InventoryEntryModel> compacted = new ArrayList<>();
        boolean changed = false;
        for (InventoryEntryModel entry : entries) {
            if (!unlimitedSlots && next > NormalInventoryLayout.DB_SLOT_END) {
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
        applyInventoryToGui(astPlayer, targetType);
    }

    // ---------------------------------------------------------------
    // legacy save / flush integration (kept for backward-compatible callers)
    // ---------------------------------------------------------------

    /**
     * 旧 API: ログアウト直前の即時 flush 用フックです。新実装ではログアウト時の通常 save に統合済みのため、
     * 互換のためのスタブとして残しています。マーケットなど即時整合性が必要な処理は
     * {@link InventoryPersistence#saveNow(PlayerInventoryState)} を呼んでください。
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

    private void clearManagedStorageSlots(@NotNull Player bukkitPlayer) {
        PlayerInventory inventory = bukkitPlayer.getInventory();
        for (int dbSlot = NormalInventoryLayout.DB_SLOT_START; dbSlot <= NormalInventoryLayout.DB_SLOT_END; dbSlot++) {
            setStorageItemIfChanged(inventory, NormalInventoryLayout.toGuiSlotIndex(dbSlot), null);
        }
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

    private boolean isStackableEntry(@NotNull InventoryEntryModel entry, @NotNull ItemModel model, int maxStack) {
        if (maxStack <= 1) return false;
        if (entry.getItemId() == null || !entry.getItemId().equals(model.getId())) return false;
        if (!entry.getItemCategory().equalsIgnoreCase(model.getCategory())) return false;
        if (entry.getInstanceType() != null || entry.getInstanceId() != null) return false;
        return entry.getQuantity() < maxStack;
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
        String instanceId = ItemStackFactory.getEquipmentInstanceId(itemStack);
        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }
        return parseUuidOrNull(instanceId);
    }

    private ItemModel resolveItemModel(@NotNull String itemId) {
        ItemModel loaded = itemService.findLoadedById(itemId);
        if (loaded != null) {
            return loaded;
        }
        return itemService.loadItem(itemId);
    }

    private @Nullable ItemModel resolveItemModel(@NotNull InventoryEntryModel entry) {
        if (entry.getItemId() != null && !entry.getItemId().isBlank()) {
            return resolveItemModel(entry.getItemId());
        }
        InventoryInstanceType instanceType = InventoryInstanceType.fromCode(entry.getInstanceType());
        if (instanceType == null || entry.getInstanceId() == null) {
            return null;
        }
        return switch (instanceType) {
            case EQUIPMENT -> {
                EquipmentInstance instance = itemService.findEquipmentInstanceById(entry.getInstanceId().toString());
                yield instance == null ? null : resolveItemModel(instance.getItemId());
            }
            case RUNE -> {
                RuneInstance instance = itemService.findRuneInstanceById(entry.getInstanceId().toString());
                yield instance == null ? null : resolveItemModel(instance.getItemId());
            }
        };
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
            case EQUIPMENT -> InventoryType.EQUIPMENT;
            case RUNE -> InventoryType.RUNE;
            case CURRENCY -> InventoryType.CURRENCY;
            default -> InventoryType.NORMAL;
        };
    }

    private @NotNull InventoryType resolveTargetInventoryType(@NotNull InventoryEntryModel entry) {
        return switch (ItemCategory.fromApiValue(entry.getItemCategory())) {
            case EQUIPMENT -> InventoryType.EQUIPMENT;
            case RUNE -> InventoryType.RUNE;
            case CURRENCY -> InventoryType.CURRENCY;
            default -> InventoryType.NORMAL;
        };
    }

    private @NotNull Set<Integer> collectUsedSlots(
        @NotNull PlayerInventoryState state,
        @NotNull InventoryModel inventory
    ) {
        List<InventoryEntryModel> entries = state.snapshotEntries(inventory.getInventoryId());
        if (inventory.getInventoryType() != InventoryType.CURRENCY) {
            return NormalInventoryLayout.collectUsedSlots(entries);
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
            return NormalInventoryLayout.findNextFreeSlot(usedSlots);
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
        if (inventoryType == InventoryType.CURRENCY) {
            return null;
        }
        return inventoryType.isSlotted() ? NormalInventoryLayout.CAPACITY : null;
    }
}

package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.EquipmentOperationInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentRepairMenuScreenView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class EquipmentRepairService {
    private static final Component TITLE = Component.text("装備修理", NamedTextColor.GOLD);

    private final Plugin plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final InventorySaveCoordinator inventorySaveCoordinator;
    private final PlayerInventoryStateRegistry inventoryStateRegistry;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final ParticleDisplayService particleDisplayService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final EquipmentRepairMenuScreenView view = new EquipmentRepairMenuScreenView();
    private final Map<UUID, RepairSession> sessions = new ConcurrentHashMap<>();
    private StatusService statusService;

    /**
     * 装備修理 GUI の操作サービスを初期化します。
     *
     * @param menuView メニュー GUI の描画・判定サービス
     * @param inventoryService プレイヤーインベントリ操作サービス
     * @param inventorySaveCoordinator ログアウト保存との直列化サービス
     * @param inventoryStateRegistry ログイン世代ごとのインベントリ state レジストリ
     * @param itemService 装備インスタンス更新に使うアイテムサービス
     * @param itemStackFactory 更新後の装備 ItemStack 生成サービス
     * @param particleDisplayService 修理成功時の共通パーティクル表示サービス
     */
    public EquipmentRepairService(
        @NotNull Plugin plugin,
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull PlayerInventoryStateRegistry inventoryStateRegistry,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
        this.inventoryStateRegistry = inventoryStateRegistry;
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.particleDisplayService = particleDisplayService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    /**
     * 修理で破損状態から復帰したときに再計算するステータスサービスを設定します。
     *
     * @param statusService ステータス再計算サービス。未設定の場合は再計算を行いません。
     */
    public void setStatusService(@Nullable StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * 指定 inventory が装備修理 GUI かを判定します。
     *
     * @param inventory 判定対象 inventory
     * @return 装備修理 GUI の場合は {@code true}
     */
    public boolean isRepairMenu(@Nullable Inventory inventory) {
        return menuView.isMenuInventory(inventory)
            && menuView.getMenuScreen(inventory) == MenuScreen.EQUIPMENT_REPAIR;
    }

    /**
     * プレイヤーに装備修理 GUI を開きます。
     * 前提として gameplay player のみ操作可能で、開く際に装備 inventory 表示へ切り替えます。
     *
     * @param player GUI を開く Bukkit プレイヤー
     */
    public void open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }
        RepairSession session = getOrCreateSession(player, astPlayer);
        if (session == null) {
            GuiSound.DENY.play(player);
            return;
        }
        session.closeRequested = false;
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.EQUIPMENT_REPAIR, -1, 0),
            BaseMenuScreenView.SIZE,
            TITLE
        );
        render(player, inventory, session);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 装備修理 GUI 上部 inventory のクリックを処理します。
     * 対象 slot の装備返却、修理実行、GUI 再描画を副作用として行います。
     *
     * @param player 操作したプレイヤー
     * @param rawSlot クリックされた raw slot
     */
    public void handleTopClick(@NotNull Player player, int rawSlot) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            player.closeInventory();
            return;
        }
        RepairSession session = getOrCreateSession(player, astPlayer);
        if (session == null) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return;
        }
        if (session.inFlightOperationId != null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (rawSlot == EquipmentRepairMenuScreenView.TARGET_SLOT) {
            if (!returnSelectedEquipment(astPlayer, session)) {
                GuiSound.DENY.play(player);
                return;
            }
            inventoryService.applyInventoryToGui(astPlayer, InventoryType.BAG);
            render(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == EquipmentRepairMenuScreenView.EXECUTE_SLOT) {
            executeRepair(player, astPlayer, session);
            return;
        }
        GuiSound.DENY.play(player);
    }

    /**
     * BAG またはホットバーのクリックから修理対象装備を選択します。
     * 選択中の装備がある場合は元の inventory へ返却し、失敗時は選択状態を戻します。
     *
     * @param player 操作したプレイヤー
     * @param bukkitSlot クリックされた Bukkit inventory slot
     * @return 修理対象として処理した場合 true。対象外アイテムの場合 false
     */
    public boolean handlePlayerInventoryClick(@NotNull Player player, int bukkitSlot) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return true;
        }
        RepairSession session = getOrCreateSession(player, astPlayer);
        if (session == null) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return true;
        }
        if (session.inFlightOperationId != null) {
            GuiSound.DENY.play(player);
            return true;
        }
        InventoryEntryModel selectedEntry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, bukkitSlot);
        ItemModel clickedModel = inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, bukkitSlot);
        if (clickedModel == null || clickedModel.getEquipment() == null) {
            return false;
        }
        ItemStack selected = inventoryService.takeOwnedItem(astPlayer, bukkitSlot);
        if (selected == null || selected.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return true;
        }
        SelectionResult selection = resolveSelection(selected);
        if (selection.state() == SelectionState.INVALID_TARGET) {
            if (!EquipmentOperationInventoryState.restoreEntry(session.inventoryState, selectedEntry)) {
                Logger.log(LogId.W_5203, "repair_invalid_target", session.accountId);
            }
            GuiSound.DENY.play(player);
            return true;
        }

        ItemStack previous = session.selectedEquipment;
        InventoryEntryModel previousEntry = session.selectedEntry;
        session.selectedEquipment = selected.clone();
        session.selectedEntry = selectedEntry;
        if (previous != null && previous.getType() != Material.AIR) {
            if (!EquipmentOperationInventoryState.restoreEntry(session.inventoryState, previousEntry)) {
                EquipmentOperationInventoryState.restoreEntry(session.inventoryState, selectedEntry);
                session.selectedEquipment = previous;
                session.selectedEntry = previousEntry;
                GuiSound.DENY.play(player);
                return true;
            }
        }
        render(player, player.getOpenInventory().getTopInventory(), session);
        GuiSound.SELECT.play(player);
        return true;
    }

    /**
     * 装備修理 GUI の close 時に選択中装備を返却し、必要に応じて元の表示 inventory へ戻します。
     *
     * @param player GUI を閉じたプレイヤー
     */
    public void handleClose(@NotNull Player player) {
        RepairSession session = sessions.get(player.getUniqueId());
        if (session == null || session.owner != player) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (session.inFlightOperationId != null) {
            session.closeRequested = true;
            if (astPlayer != null) {
                restoreDisplayedInventory(astPlayer, session);
            }
            return;
        }
        if (astPlayer == null) {
            if (sessions.remove(player.getUniqueId(), session)) {
                detachForSave(session);
            }
            return;
        }
        releaseSession(astPlayer, session, true);
    }

    /**
     * ログアウト保存より前に、対象ログイン世代の修理セッションを state へ回収します。
     * 進行中の API 処理は同一アカウントの保存キュー上で確定または補償されます。
     *
     * @param player ログアウトする Bukkit プレイヤー
     */
    public void prepareForPlayerSave(@NotNull Player player) {
        RepairSession session = sessions.get(player.getUniqueId());
        if (session == null || session.owner != player || !sessions.remove(player.getUniqueId(), session)) {
            return;
        }
        detachForSave(session);
    }

    /**
     * プラグイン停止前に全修理セッションを state へ回収し、進行処理を保存キューへ登録します。
     */
    public void prepareAllForShutdown() {
        for (RepairSession session : List.copyOf(sessions.values())) {
            if (sessions.remove(session.owner.getUniqueId(), session)) {
                detachForSave(session);
            }
        }
    }

    /**
     * 修理費用を計算します。
     * 耐久値が高い装備ほど軽い倍率を乗せ、欠損耐久 1 につき最低 1 ゴールドを要求します。
     *
     * @param durabilityMax 装備の最大耐久値
     * @param missingDurability 欠損している耐久値
     * @return 修理に必要なゴールド。修理不要な場合は {@code 0}
     */
    public static long calculateRepairCost(int durabilityMax, int missingDurability) {
        if (durabilityMax <= 0 || missingDurability <= 0) {
            return 0L;
        }
        double multiplier = 1.0D + Math.max(0, durabilityMax - 300) / 1400.0D;
        return Math.max(1L, (long) Math.ceil(missingDurability * multiplier));
    }

    private void executeRepair(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull RepairSession session
    ) {
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        RepairContext context = selection.context();
        if (context == null) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, selection.state().messageId());
            return;
        }
        long ownedGold = ownedGold(astPlayer.getAccount().getUuid());
        if (ownedGold < context.cost()) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5276);
            return;
        }
        boolean wasBroken = context.instance().getDurabilityValue() <= 0;
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryService.InventoryStateSnapshot paymentSnapshot = inventoryService.snapshotState(accountId);
        if (paymentSnapshot == null || !inventoryService.consumeGold(accountId, context.cost())) {
            restorePayment(paymentSnapshot, accountId, "repair_consume");
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5277);
            return;
        }
        UUID operationId = UUID.randomUUID();
        session.inFlightOperationId = operationId;
        session.paymentSnapshot = paymentSnapshot;
        session.closeRequested = false;
        render(player, player.getOpenInventory().getTopInventory(), session);
        CompletableFuture<EquipmentInstance> operationFuture = AsyncTaskUtil.supplyAsync(plugin, () ->
            itemService.updateEquipmentDurability(
                context.instance().getEquipmentInstanceId(),
                context.instance().getDurabilityMax(),
                accountId.toString()
            )
        );
        session.operationFuture = operationFuture;
        operationFuture.whenComplete((updated, throwable) -> {
            if (session.detached) {
                return;
            }
            AsyncTaskUtil.runSync(
                plugin,
                () -> completeRepair(
                    player,
                    astPlayer,
                    session,
                    operationId,
                    paymentSnapshot,
                    context,
                    wasBroken,
                    updated,
                    throwable
                )
            );
        });
    }

    private void completeRepair(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull RepairSession session,
        @NotNull UUID operationId,
        @NotNull InventoryService.InventoryStateSnapshot paymentSnapshot,
        @NotNull RepairContext context,
        boolean wasBroken,
        @Nullable EquipmentInstance updated,
        @Nullable Throwable throwable
    ) {
        if (session.detached
            || session.owner != player
            || !operationId.equals(session.inFlightOperationId)) {
            return;
        }
        session.inFlightOperationId = null;
        session.operationFuture = null;
        session.paymentSnapshot = null;
        if (throwable != null || updated == null) {
            restorePayment(paymentSnapshot, astPlayer.getAccount().getUuid(), "repair_api");
            if (player.isOnline()) {
                GuiSound.DENY.play(player);
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5277);
            }
            finishRepairOperation(player, astPlayer, session);
            return;
        }

        session.selectedEquipment = itemStackFactory.create(context.model(), updated, 1);
        if (wasBroken && statusService != null) {
            statusService.refreshStatus(astPlayer);
        }
        if (player.isOnline()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5278, displayName(context.model()), context.cost());
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 1.2f);
            particleDisplayService.spawnForNearbyViewers(
                player.getLocation().add(0.0, 1.0, 0.0),
                SharedParticleDefinitions.EQUIPMENT_REPAIR_ENCHANT
            );
        }
        finishRepairOperation(player, astPlayer, session);
    }

    private void finishRepairOperation(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull RepairSession session
    ) {
        if (session.closeRequested
            || sessions.get(player.getUniqueId()) != session
            || !player.isOnline()
            || !isRepairMenu(player.getOpenInventory().getTopInventory())) {
            releaseSession(astPlayer, session, player.isOnline());
            return;
        }
        render(player, player.getOpenInventory().getTopInventory(), session);
    }

    private void restorePayment(
        @Nullable InventoryService.InventoryStateSnapshot snapshot,
        @NotNull UUID accountId,
        @NotNull String operation
    ) {
        if (!inventoryService.restoreState(snapshot)) {
            Logger.log(LogId.W_5203, operation, accountId);
        }
    }

    private void render(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull RepairSession session
    ) {
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        view.render(
            inventory,
            session.selectedEquipment == null ? null : session.selectedEquipment.clone(),
            createCostItem(player, selection),
            view.createGuideItem(),
            createInfoItem(player, selection),
            createExecuteItem(player, selection, session.inFlightOperationId != null)
        );
    }

    private @NotNull SelectionResult resolveSelection(@Nullable ItemStack selectedEquipment) {
        if (selectedEquipment == null || selectedEquipment.getType() == Material.AIR) {
            return new SelectionResult(SelectionState.NONE_SELECTED, null, null, null);
        }
        ItemReference reference = itemReferenceResolver.resolve(selectedEquipment);
        if (reference == null
            || !reference.hasEquipmentInstanceId()
            || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return new SelectionResult(SelectionState.INVALID_TARGET, null, null, null);
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (model == null || model.getEquipment() == null || instance == null) {
            return new SelectionResult(SelectionState.INVALID_TARGET, model, instance, null);
        }
        if (instance.getDurabilityMax() <= 0) {
            return new SelectionResult(SelectionState.NO_DURABILITY, model, instance, null);
        }
        int missing = Math.max(0, instance.getDurabilityMax() - instance.getDurabilityValue());
        if (missing <= 0) {
            return new SelectionResult(SelectionState.ALREADY_FULL, model, instance, null);
        }
        long cost = calculateRepairCost(instance.getDurabilityMax(), missing);
        return new SelectionResult(
            SelectionState.READY,
            model,
            instance,
            new RepairContext(model, instance, missing, cost)
        );
    }

    private @NotNull ItemStack createCostItem(@NotNull Player player, @NotNull SelectionResult selection) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        long ownedGold = AccountModeGuard.isGameplayPlayer(astPlayer) ? ownedGold(astPlayer.getAccount().getUuid()) : 0L;
        RepairContext context = selection.context();
        if (context == null) {
            return createItem(
                Material.GOLD_NUGGET,
                Component.text("修理費用", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(Component.text(selection.state().message(), NamedTextColor.GRAY))
            );
        }
        return createItem(
            Material.GOLD_INGOT,
            Component.text("修理費用", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(
                Component.text("回復耐久: " + context.missingDurability(), NamedTextColor.GRAY),
                Component.text(
                    "必要ゴールド: " + context.cost() + " / 所持: " + ownedGold,
                    ownedGold >= context.cost() ? NamedTextColor.GREEN : NamedTextColor.RED
                )
            )
        );
    }

    private @NotNull ItemStack createInfoItem(@NotNull Player player, @NotNull SelectionResult selection) {
        RepairContext context = selection.context();
        if (context == null) {
            return createItem(
                Material.BOOK,
                Component.text("修理情報", NamedTextColor.YELLOW),
                List.of(Component.text(selection.state().message(), NamedTextColor.RED))
            );
        }
        return createItem(
            Material.KNOWLEDGE_BOOK,
            Component.text("修理情報", NamedTextColor.AQUA, TextDecoration.BOLD),
            List.of(
                Component.text("装備: " + displayName(context.model()), NamedTextColor.GRAY),
                Component.text("現在耐久: " + context.instance().getDurabilityValue() + " / " + context.instance().getDurabilityMax(), NamedTextColor.GRAY),
                Component.text("修理後: " + context.instance().getDurabilityMax() + " / " + context.instance().getDurabilityMax(), NamedTextColor.GREEN)
            )
        );
    }

    private @NotNull ItemStack createExecuteItem(
        @NotNull Player player,
        @NotNull SelectionResult selection,
        boolean inFlight
    ) {
        if (inFlight) {
            return createItem(
                Material.BARRIER,
                PlayerMsgResource.getComponent(PlayerMsgId.P_5283.getId()).decorate(TextDecoration.BOLD),
                List.of(PlayerMsgResource.getComponent(PlayerMsgId.P_6700.getId()))
            );
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        RepairContext context = selection.context();
        boolean executable = AccountModeGuard.isGameplayPlayer(astPlayer)
            && context != null
            && ownedGold(astPlayer.getAccount().getUuid()) >= context.cost();
        return createItem(
            executable ? Material.ANVIL : Material.BARRIER,
            Component.text("修理実行", executable ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
            List.of(Component.text(
                executable ? "クリックすると耐久値を最大まで回復します。" : selection.state().message(),
                executable ? NamedTextColor.GRAY : NamedTextColor.RED
            ))
        );
    }

    private long ownedGold(@NotNull UUID accountId) {
        return inventoryService.getCurrencyAmount(accountId, ItemService.DEFAULT_CURRENCY_ITEM_ID)
            + inventoryService.getCurrencyAmount(accountId, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
    }

    private synchronized @Nullable RepairSession getOrCreateSession(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        PlayerInventoryState inventoryState = inventoryStateRegistry.get(accountId);
        if (inventoryState == null) {
            return null;
        }
        RepairSession current = sessions.get(player.getUniqueId());
        if (current != null && current.owner == player && current.inventoryState == inventoryState) {
            return current;
        }
        if (current != null && sessions.remove(player.getUniqueId(), current)) {
            detachForSave(current);
        }
        RepairSession created = new RepairSession(
            player,
            accountId,
            inventoryState,
            inventoryService.getDisplayedInventoryType(accountId)
        );
        sessions.put(player.getUniqueId(), created);
        return created;
    }

    private void detachForSave(@NotNull RepairSession session) {
        session.detached = true;
        session.closeRequested = true;
        session.entryRestoredForSave = EquipmentOperationInventoryState.restoreEntry(
            session.inventoryState,
            session.selectedEntry
        );
        if (!session.entryRestoredForSave) {
            Logger.log(LogId.W_5203, "repair_logout_restore", session.accountId);
        }
        restoreBukkitInventoryBeforeSave(session);

        CompletableFuture<EquipmentInstance> operationFuture = session.operationFuture;
        if (session.inFlightOperationId == null || operationFuture == null) {
            inventorySaveCoordinator.enqueueLogoutReconciliation(session.accountId, () -> {
                boolean restored = EquipmentOperationInventoryState.restoreEntry(
                    session.inventoryState,
                    session.selectedEntry
                );
                clearHeldEquipment(session);
                if (!restored) {
                    Logger.log(LogId.W_5203, "repair_logout_entry", session.accountId);
                }
                return restored;
            });
            return;
        }
        inventorySaveCoordinator.enqueueLogoutReconciliation(
            session.accountId,
            () -> reconcileDetachedOperation(session, operationFuture)
        ).exceptionally(throwable -> {
            Logger.log(LogId.W_5203, "repair_logout_reconciliation", session.accountId);
            return false;
        });
    }

    private void restoreBukkitInventoryBeforeSave(@NotNull RepairSession session) {
        AstPlayer astPlayer = AstPlayerCache.get(session.owner);
        if (astPlayer == null || astPlayer.getBukkit() != session.owner) {
            return;
        }
        inventoryService.applyInventoryToGui(
            astPlayer,
            session.previousDisplayedType == null ? InventoryType.BAG : session.previousDisplayedType
        );
    }

    private boolean reconcileDetachedOperation(
        @NotNull RepairSession session,
        @NotNull CompletableFuture<EquipmentInstance> operationFuture
    ) {
        EquipmentInstance updated;
        try {
            updated = operationFuture.join();
        } catch (RuntimeException failure) {
            boolean compensated = EquipmentOperationInventoryState.restoreSnapshot(
                session.inventoryState,
                session.paymentSnapshot
            );
            boolean restored = EquipmentOperationInventoryState.restoreEntry(
                session.inventoryState,
                session.selectedEntry
            );
            clearHeldEquipment(session);
            if (!compensated || !restored) {
                Logger.log(LogId.W_5203, "repair_logout_api", session.accountId);
            }
            return compensated && restored;
        }

        if (updated == null) {
            boolean compensated = EquipmentOperationInventoryState.restoreSnapshot(
                session.inventoryState,
                session.paymentSnapshot
            );
            boolean restored = EquipmentOperationInventoryState.restoreEntry(
                session.inventoryState,
                session.selectedEntry
            );
            clearHeldEquipment(session);
            if (!compensated || !restored) {
                Logger.log(LogId.W_5203, "repair_logout_result", session.accountId);
            }
            return compensated && restored;
        }

        boolean restored = session.entryRestoredForSave;
        clearHeldEquipment(session);
        if (!restored) {
            Logger.log(LogId.W_5203, "repair_logout_entry", session.accountId);
        }
        return restored;
    }

    private void clearHeldEquipment(@NotNull RepairSession session) {
        session.selectedEquipment = null;
        session.selectedEntry = null;
        session.inFlightOperationId = null;
        session.operationFuture = null;
        session.paymentSnapshot = null;
    }

    private boolean returnSelectedEquipment(@NotNull AstPlayer astPlayer, @NotNull RepairSession session) {
        if (session.selectedEquipment == null || session.selectedEquipment.getType() == Material.AIR) {
            return false;
        }
        if (!EquipmentOperationInventoryState.restoreEntry(session.inventoryState, session.selectedEntry)) {
            astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedEquipment.clone());
        }
        session.selectedEquipment = null;
        session.selectedEntry = null;
        return true;
    }

    private void releaseSession(
        @NotNull AstPlayer astPlayer,
        @NotNull RepairSession session,
        boolean restoreDisplayedInventory
    ) {
        sessions.remove(session.owner.getUniqueId(), session);
        if (session.selectedEquipment != null && session.selectedEquipment.getType() != Material.AIR) {
            if (!EquipmentOperationInventoryState.restoreEntry(session.inventoryState, session.selectedEntry)) {
                astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedEquipment.clone());
            }
            session.selectedEquipment = null;
            session.selectedEntry = null;
        }
        if (restoreDisplayedInventory && session.owner == astPlayer.getBukkit()) {
            restoreDisplayedInventory(astPlayer, session);
        }
    }

    private void restoreDisplayedInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull RepairSession session
    ) {
        if (session.previousDisplayedType != null) {
            inventoryService.applyInventoryToGui(astPlayer, session.previousDisplayedType);
        }
    }

    private @NotNull String displayName(@NotNull ItemModel model) {
        return ColorCodeUtil.toPlainText(model.getName(), model.getId());
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }

    private static final class RepairSession {
        private final Player owner;
        private final UUID accountId;
        private final PlayerInventoryState inventoryState;
        private final InventoryType previousDisplayedType;
        private ItemStack selectedEquipment;
        private InventoryEntryModel selectedEntry;
        private UUID inFlightOperationId;
        private CompletableFuture<EquipmentInstance> operationFuture;
        private InventoryService.InventoryStateSnapshot paymentSnapshot;
        private boolean closeRequested;
        private volatile boolean detached;
        private boolean entryRestoredForSave;

        private RepairSession(
            @NotNull Player owner,
            @NotNull UUID accountId,
            @NotNull PlayerInventoryState inventoryState,
            @Nullable InventoryType previousDisplayedType
        ) {
            this.owner = owner;
            this.accountId = accountId;
            this.inventoryState = inventoryState;
            this.previousDisplayedType = previousDisplayedType;
        }
    }

    private record SelectionResult(
        @NotNull SelectionState state,
        @Nullable ItemModel model,
        @Nullable EquipmentInstance instance,
        @Nullable RepairContext context
    ) {
    }

    private record RepairContext(
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        int missingDurability,
        long cost
    ) {
    }

    private enum SelectionState {
        NONE_SELECTED(PlayerMsgId.P_5271),
        INVALID_TARGET(PlayerMsgId.P_5272),
        NO_DURABILITY(PlayerMsgId.P_5273),
        ALREADY_FULL(PlayerMsgId.P_5274),
        READY(PlayerMsgId.P_5275);

        private final PlayerMsgId messageId;

        SelectionState(@NotNull PlayerMsgId messageId) {
            this.messageId = messageId;
        }

        private @NotNull String message() {
            return ColorCodeUtil.toPlainText(PlayerMsgResource.getMessage(messageId.getId()), messageId.getId());
        }

        private @NotNull PlayerMsgId messageId() {
            return messageId;
        }
    }
}

package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.service.EquipmentOperationInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentProcessingDisplayState;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceMaterial;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentProcessingMode;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentEnhancementMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentProcessingMenuScreenView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class EquipmentEnhancementService {

    private final Plugin plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final InventorySaveCoordinator inventorySaveCoordinator;
    private final PlayerInventoryStateRegistry inventoryStateRegistry;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final ParticleDisplayService particleDisplayService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final EquipmentEnhancementMenuScreenView view = new EquipmentEnhancementMenuScreenView();
    private final EquipmentProcessingMenuScreenView processingView = new EquipmentProcessingMenuScreenView();
    private final Map<UUID, EnhancementSession> sessions = new ConcurrentHashMap<>();
    private EquipmentRepairService equipmentRepairService;

    /**
     * 装備強化 GUI と非同期強化処理を初期化します。
     *
     * @param plugin 非同期処理を登録するプラグイン
     * @param menuView メニュー GUI の表示・判定サービス
     * @param inventoryService プレイヤーインベントリ操作サービス
     * @param inventorySaveCoordinator ログアウト保存との直列化サービス
     * @param inventoryStateRegistry ログイン世代ごとのインベントリ state レジストリ
     * @param itemService 装備インスタンス更新サービス
     * @param itemStackFactory 更新後の装備表示生成サービス
     * @param particleDisplayService 強化結果のパーティクル表示サービス
     */
    public EquipmentEnhancementService(
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
     * 装備加工画面から呼び出す、退避装備の修理サービスを接続します。
     *
     * @param equipmentRepairService 修理実行と修理情報表示を提供するサービス
     */
    public void setEquipmentRepairService(@NotNull EquipmentRepairService equipmentRepairService) {
        this.equipmentRepairService = equipmentRepairService;
    }

    public boolean isProcessingMenu(@Nullable Inventory inventory) {
        return menuView.isMenuInventory(inventory)
            && menuView.getMenuScreen(inventory) == MenuScreen.EQUIPMENT_PROCESSING;
    }

    public boolean isEnhancementMenu(@Nullable Inventory inventory) {
        return menuView.isMenuInventory(inventory)
            && menuView.getMenuScreen(inventory) == MenuScreen.EQUIPMENT_ENHANCE;
    }

    public void open(@NotNull Player player) {
        open(player, EquipmentProcessingMode.ENHANCEMENT);
    }

    /**
     * 指定モードの装備加工 GUI を開きます。画面タイトルと上部の色・アイコンは現在モードを常時示します。
     *
     * @param player GUI を開くプレイヤー
     * @param mode 初期表示する修理または強化モード
     */
    public void open(@NotNull Player player, @NotNull EquipmentProcessingMode mode) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }

        EnhancementSession session = getOrCreateSession(player, astPlayer);
        if (session == null) {
            GuiSound.DENY.play(player);
            return;
        }
        switchProcessingMode(player, session, mode);
        session.closeRequested = false;
        session.processingScreen = ProcessingScreen.MAIN;
        session.materialListPage = 0;

        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.EQUIPMENT_PROCESSING, -1, 0, mode.contentId()),
            BaseMenuScreenView.SIZE,
            processingView.processingTitle(EquipmentProcessingDisplayState.from(mode, false))
        );
        renderProcessing(player, inventory, session);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 装備加工 GUI 上段のクリックを処理します。必要素材一覧の表示中は戻る・ページ移動を優先します。
     *
     * @param player 操作したプレイヤー
     * @param rawSlot クリックされた GUI slot
     */
    public void handleTopClick(@NotNull Player player, int rawSlot) {
        if (isProcessingMenu(player.getOpenInventory().getTopInventory())) {
            handleProcessingTopClick(player, rawSlot);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            player.closeInventory();
            return;
        }
        EnhancementSession session = getOrCreateSession(player, astPlayer);
        if (session == null) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return;
        }
        if (session.inFlightOperationId != null) {
            GuiSound.DENY.play(player);
            return;
        }

        if (rawSlot == EquipmentEnhancementMenuScreenView.TARGET_SLOT) {
            if (!returnSelectedEquipment(astPlayer, session)) {
                GuiSound.DENY.play(player);
                return;
            }
            inventoryService.applyInventoryToGui(astPlayer, InventoryType.BAG);
            render(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == EquipmentEnhancementMenuScreenView.EXECUTE_SLOT) {
            executeEnhancement(player, astPlayer, session);
            return;
        }
        GuiSound.DENY.play(player);
    }

    /**
     * BAG またはホットバーの装備を強化対象へ移動します。
     *
     * @param player 操作したプレイヤー
     * @param bukkitSlot クリックされた Bukkit PlayerInventory スロット
     * @return 強化対象として処理した場合 true。対象外アイテムの場合 false
     */
    public boolean handlePlayerInventoryClick(@NotNull Player player, int bukkitSlot) {
        return handlePlayerInventoryClick(player, bukkitSlot, null);
    }

    /**
     * 装備加工 GUI の下段クリックを処理し、両モードで装備を一時退避してセットします。
     * 表示 ItemStack が正本 entry と一致しない場合は対象を移動しません。
     *
     * @param player 操作したプレイヤー
     * @param bukkitSlot クリックされた Bukkit PlayerInventory スロット
     * @param displayedItem クリック時に表示されていた ItemStack。照合できない場合は {@code null}
     * @return 加工対象として処理した場合 {@code true}。対象外アイテムの場合は {@code false}
     */
    public boolean handlePlayerInventoryClick(
        @NotNull Player player,
        int bukkitSlot,
        @Nullable ItemStack displayedItem
    ) {
        if (isProcessingMenu(player.getOpenInventory().getTopInventory())) {
            return handleProcessingPlayerInventoryClick(player, bukkitSlot, displayedItem);
        }
        return handleEnhancementInventoryClick(player, bukkitSlot, displayedItem, false);
    }

    private boolean handleEnhancementInventoryClick(
        @NotNull Player player,
        int bukkitSlot,
        @Nullable ItemStack displayedItem,
        boolean processing
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return true;
        }
        EnhancementSession session = getOrCreateSession(player, astPlayer);
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
        if (!isEquipmentModel(clickedModel)) {
            return false;
        }

        if (displayedItem != null && !matchesOwnedEntry(selectedEntry, displayedItem)) {
            GuiSound.DENY.play(player);
            return true;
        }

        ItemStack selected = inventoryService.takeOwnedItem(astPlayer, bukkitSlot);
        if (selected == null || selected.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return true;
        }

        SelectionResult selection = resolveSelection(selected);
        if (selection.state() == SelectionState.INVALID_TARGET) {
            if (!EquipmentOperationInventoryState.restoreEntry(session.inventoryState, selectedEntry)) {
                Logger.log(LogId.W_5203, "enhancement_invalid_target", session.accountId);
            }
            GuiSound.DENY.play(player);
            return true;
        }

        ItemStack previous = session.selectedEquipment;
        InventoryEntryModel previousEntry = session.selectedEntry;
        session.selectedEquipment = selected.clone();
        session.selectedEntry = selectedEntry;
        session.confirmationPending = false;
        if (previous != null && previous.getType() != Material.AIR) {
            if (!EquipmentOperationInventoryState.restoreEntry(session.inventoryState, previousEntry)) {
                EquipmentOperationInventoryState.restoreEntry(session.inventoryState, selectedEntry);
                session.selectedEquipment = previous;
                session.selectedEntry = previousEntry;
                GuiSound.DENY.play(player);
                return true;
            }
        }

        if (processing) {
            renderProcessing(player, player.getOpenInventory().getTopInventory(), session);
        } else {
            render(player, player.getOpenInventory().getTopInventory(), session);
        }
        GuiSound.SELECT.play(player);
        return true;
    }

    /**
     * 加工GUI下段のクリックを処理します。必要素材一覧の表示中は対象装備を差し替えません。
     *
     * @param player 操作したプレイヤー
     * @param bukkitSlot クリックされた Bukkit PlayerInventory スロット
     * @param displayedItem クリック時に表示されていた ItemStack。照合できない場合は {@code null}
     * @return 加工GUIの操作として消費した場合 {@code true}
     */
    private boolean handleProcessingPlayerInventoryClick(
        @NotNull Player player,
        int bukkitSlot,
        @Nullable ItemStack displayedItem
    ) {
        EnhancementSession session = sessions.get(player.getUniqueId());
        if (session != null && session.owner == player && session.processingScreen == ProcessingScreen.MATERIAL_LIST) {
            GuiSound.DENY.play(player);
            return true;
        }
        return handleEnhancementInventoryClick(player, bukkitSlot, displayedItem, true);
    }

    /**
     * 加工GUI上段のモード切替、素材一覧、対象返却、実行操作を処理します。
     *
     * @param player 操作したプレイヤー
     * @param rawSlot クリックされた GUI slot
     */
    private void handleProcessingTopClick(@NotNull Player player, int rawSlot) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            player.closeInventory();
            return;
        }
        EnhancementSession session = getOrCreateSession(player, astPlayer);
        if (session == null) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return;
        }
        if (session.inFlightOperationId != null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (session.processingScreen == ProcessingScreen.MATERIAL_LIST) {
            handleMaterialListTopClick(player, astPlayer, session, rawSlot);
            return;
        }
        if (rawSlot == EquipmentProcessingMenuScreenView.REPAIR_TAB_SLOT) {
            switchProcessingMode(player, session, EquipmentProcessingMode.REPAIR);
            return;
        }
        if (rawSlot == EquipmentProcessingMenuScreenView.ENHANCEMENT_TAB_SLOT) {
            switchProcessingMode(player, session, EquipmentProcessingMode.ENHANCEMENT);
            return;
        }
        if (rawSlot == EquipmentProcessingMenuScreenView.MATERIAL_LIST_SLOT) {
            openMaterialList(player, astPlayer, session);
            return;
        }
        if (rawSlot == EquipmentProcessingMenuScreenView.TARGET_SLOT) {
            if (!returnSelectedEquipment(astPlayer, session)) {
                GuiSound.DENY.play(player);
                return;
            }
            inventoryService.applyInventoryToGui(astPlayer, InventoryType.BAG);
            renderProcessing(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == EquipmentProcessingMenuScreenView.EXECUTE_SLOT) {
            if (session.mode == EquipmentProcessingMode.REPAIR) {
                executeProcessingRepair(player, astPlayer, session);
            } else {
                executeEnhancement(player, astPlayer, session);
            }
            return;
        }
        GuiSound.DENY.play(player);
    }

    /**
     * 強化対象の必要素材一覧を開きます。対象未選択時と修理モードでは一覧を開きません。
     *
     * @param player 操作したプレイヤー
     * @param astPlayer 操作したプレイヤーのゲーム状態
     * @param session 対象装備を保持する加工セッション
     */
    private void openMaterialList(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session
    ) {
        if (session.mode != EquipmentProcessingMode.ENHANCEMENT) {
            GuiSound.DENY.play(player);
            return;
        }
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        if (selection.context() == null) {
            GuiSound.DENY.play(player);
            return;
        }
        session.confirmationPending = false;
        session.processingScreen = ProcessingScreen.MATERIAL_LIST;
        session.materialListPage = 0;
        renderMaterialList(player, astPlayer, player.getOpenInventory().getTopInventory(), session);
        GuiSound.SELECT.play(player);
    }

    /**
     * 必要素材一覧の戻る・ページ移動操作を処理します。
     *
     * @param player 操作したプレイヤー
     * @param astPlayer 操作したプレイヤーのゲーム状態
     * @param session 対象装備を保持する加工セッション
     * @param rawSlot クリックされた GUI slot
     */
    private void handleMaterialListTopClick(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session,
        int rawSlot
    ) {
        if (rawSlot == EquipmentProcessingMenuScreenView.MATERIAL_LIST_BACK_SLOT) {
            session.processingScreen = ProcessingScreen.MAIN;
            session.materialListPage = 0;
            renderProcessing(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }

        SelectionResult selection = resolveSelection(session.selectedEquipment);
        if (selection.context() == null) {
            session.processingScreen = ProcessingScreen.MAIN;
            renderProcessing(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.DENY.play(player);
            return;
        }
        List<MaterialRequirement> requirements = collectMaterialRequirements(astPlayer, selection.context());
        int pageCount = materialListPageCount(requirements);
        if (rawSlot == EquipmentProcessingMenuScreenView.MATERIAL_LIST_PREVIOUS_SLOT && session.materialListPage > 0) {
            session.materialListPage--;
            renderMaterialList(player, astPlayer, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == EquipmentProcessingMenuScreenView.MATERIAL_LIST_NEXT_SLOT
            && session.materialListPage + 1 < pageCount) {
            session.materialListPage++;
            renderMaterialList(player, astPlayer, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    public void handleClose(@NotNull Player player) {
        EnhancementSession session = sessions.get(player.getUniqueId());
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
     * ログアウト保存より前に、対象ログイン世代の強化セッションを state へ回収します。
     * 進行中の API 処理は同一アカウントの保存キュー上で確定または補償されます。
     *
     * @param player ログアウトする Bukkit プレイヤー
     */
    public void prepareForPlayerSave(@NotNull Player player) {
        EnhancementSession session = sessions.get(player.getUniqueId());
        if (session == null || session.owner != player || !sessions.remove(player.getUniqueId(), session)) {
            return;
        }
        detachForSave(session);
    }

    /**
     * プラグイン停止前に全強化セッションを state へ回収し、進行処理を保存キューへ登録します。
     */
    public void prepareAllForShutdown() {
        for (EnhancementSession session : List.copyOf(sessions.values())) {
            if (sessions.remove(session.owner.getUniqueId(), session)) {
                detachForSave(session);
            }
        }
    }

    /**
     * 装備加工 GUI に一時退避した装備を修理し、更新済み装備を対象枠へ保持したまま再描画します。
     * 対象枠クリックまたは画面終了時に、強化モードと同じ返却経路で所持品へ戻します。
     */
    private void executeProcessingRepair(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session
    ) {
        if (equipmentRepairService == null) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack repairedEquipment = equipmentRepairService.repairHeldEquipment(player, session.selectedEquipment);
        if (repairedEquipment == null) {
            renderProcessing(player, player.getOpenInventory().getTopInventory(), session);
            return;
        }

        session.selectedEquipment = repairedEquipment;
        inventoryService.applyInventoryToGui(astPlayer, InventoryType.BAG);
        player.updateInventory();
        renderProcessing(player, player.getOpenInventory().getTopInventory(), session);
    }

    private void executeEnhancement(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session
    ) {
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        EnhancementContext context = selection.context();
        if (context == null) {
            session.confirmationPending = false;
            if (selection.state() == SelectionState.NONE_SELECTED) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5261);
            }
            GuiSound.DENY.play(player);
            return;
        }

        List<MaterialRequirement> requirements = collectMaterialRequirements(astPlayer, context);
        if (!hasEnoughRequirements(astPlayer, requirements, context.requiredCurrency())) {
            session.confirmationPending = false;
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5254);
            GuiSound.DENY.play(player);
            return;
        }

        if (requiresConfirmation(context) && !session.confirmationPending) {
            session.confirmationPending = true;
            renderCurrent(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }
        session.confirmationPending = false;

        double successRate = normalizeSuccessRate(context.successRate());
        boolean success = Math.random() < successRate;
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryService.InventoryStateSnapshot paymentSnapshot = inventoryService.snapshotState(accountId);
        if (paymentSnapshot == null
            || !consumeRequirements(astPlayer, requirements, context.requiredCurrency())) {
            restorePayment(paymentSnapshot, accountId, "enhancement_consume");
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5262);
            GuiSound.DENY.play(player);
            return;
        }

        // 消費結果をプレイヤーの表示インベントリへ即時反映する。
        inventoryService.applyInventoryToGui(astPlayer, InventoryType.BAG);
        player.updateInventory();

        UUID operationId = UUID.randomUUID();
        session.inFlightOperationId = operationId;
        session.paymentSnapshot = paymentSnapshot;
        session.closeRequested = false;
        renderCurrent(player, player.getOpenInventory().getTopInventory(), session);
        String updatedBy = accountId.toString();
        CompletableFuture<EnhancementResult> operationFuture = AsyncTaskUtil.supplyAsync(
            plugin,
            () -> applyEnhancementResult(context, success, updatedBy)
        );
        session.operationFuture = operationFuture;
        operationFuture.whenComplete((result, throwable) -> {
            if (session.detached) {
                return;
            }
            AsyncTaskUtil.runSync(
                plugin,
                () -> completeEnhancement(
                    player,
                    astPlayer,
                    session,
                    operationId,
                    paymentSnapshot,
                    context,
                    successRate,
                    result,
                    throwable
                )
            );
        });
    }

    private void completeEnhancement(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session,
        @NotNull UUID operationId,
        @NotNull InventoryService.InventoryStateSnapshot paymentSnapshot,
        @NotNull EnhancementContext context,
        double successRate,
        @Nullable EnhancementResult result,
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
        UUID accountId = astPlayer.getAccount().getUuid();
        if (throwable != null || result == null) {
            restorePayment(paymentSnapshot, accountId, "enhancement_api");
            if (player.isOnline()) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5262);
                GuiSound.DENY.play(player);
            }
            finishEnhancementOperation(player, astPlayer, session);
            return;
        }

        switch (result.type) {
            case SUCCESS -> {
                session.selectedEquipment = itemStackFactory.create(context.model, Objects.requireNonNull(result.updatedInstance), 1);
                if (player.isOnline()) {
                    if (context.isTranscendence()) {
                        PlayerMessageService.getInstance().send(
                            player,
                            PlayerMsgId.P_5287,
                            displayName(context.model),
                            context.transcendenceDisplayName()
                        );
                    } else {
                        PlayerMessageService.getInstance().send(
                            player,
                            PlayerMsgId.P_5257,
                            displayName(context.model),
                            result.updatedInstance.getEnhanceLevel(),
                            formatPercent(successRate)
                        );
                    }
                    playSuccessEffects(player);
                }
            }
            case FAIL_NONE -> {
                if (player.isOnline()) {
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5258, displayName(context.model));
                    playFailureEffects(player);
                }
            }
            case FAIL_DOWNGRADE -> {
                if (result.updatedInstance != null) {
                    session.selectedEquipment = itemStackFactory.create(context.model, result.updatedInstance, 1);
                }
                int downgradedLevel = result.updatedInstance == null
                    ? Math.max(0, context.instance.getEnhanceLevel() - 1)
                    : result.updatedInstance.getEnhanceLevel();
                if (player.isOnline()) {
                    PlayerMessageService.getInstance().send(
                        player,
                        PlayerMsgId.P_5259,
                        displayName(context.model),
                        downgradedLevel
                    );
                    playFailureEffects(player);
                }
            }
            case FAIL_DESTROY -> {
                session.selectedEquipment = null;
                session.selectedEntry = null;
                if (player.isOnline()) {
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5260, displayName(context.model));
                    playDestroyEffects(player);
                }
            }
        }

        finishEnhancementOperation(player, astPlayer, session);
    }

    private void finishEnhancementOperation(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session
    ) {
        if (session.closeRequested
            || sessions.get(player.getUniqueId()) != session
            || !player.isOnline()
            || (!isEnhancementMenu(player.getOpenInventory().getTopInventory())
                && !isProcessingMenu(player.getOpenInventory().getTopInventory()))) {
            releaseSession(astPlayer, session, player.isOnline());
            return;
        }
        inventoryService.applyInventoryToGui(astPlayer, InventoryType.BAG);
        player.updateInventory();
        renderCurrent(player, player.getOpenInventory().getTopInventory(), session);
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

    private @Nullable EnhancementResult applyEnhancementResult(
        @NotNull EnhancementContext context,
        boolean success,
        @NotNull String updatedBy
    ) {
        if (success) {
            EquipmentInstance updated = context.isTranscendence()
                ? itemService.transcendEquipmentInstance(
                    context.instance.getEquipmentInstanceId(),
                    Objects.requireNonNull(context.transcendence).getRank(),
                    updatedBy
                )
                : itemService.enhanceEquipmentInstance(
                    context.instance.getEquipmentInstanceId(),
                    Objects.requireNonNull(context.nextLevel).getLevel(),
                    updatedBy
                );
            return updated == null ? null : new EnhancementResult(EnhancementResultType.SUCCESS, updated);
        }

        ItemEquipmentEnhanceLevel nextLevel = Objects.requireNonNull(context.nextLevel);
        return switch (nextLevel.getFailAction()) {
            case NONE -> new EnhancementResult(EnhancementResultType.FAIL_NONE, null);
            case DOWNGRADE -> {
                if (context.instance.getEnhanceLevel() <= 0) {
                    yield new EnhancementResult(EnhancementResultType.FAIL_NONE, null);
                }
                EquipmentInstance downgraded = itemService.enhanceEquipmentInstance(
                    context.instance.getEquipmentInstanceId(),
                    context.instance.getEnhanceLevel() - 1,
                    updatedBy
                );
                yield downgraded == null ? null : new EnhancementResult(EnhancementResultType.FAIL_DOWNGRADE, downgraded);
            }
            case DESTROY -> itemService.deleteEquipmentInstance(context.instance.getEquipmentInstanceId())
                ? new EnhancementResult(EnhancementResultType.FAIL_DESTROY, null)
                : null;
        };
    }

    private boolean consumeRequirements(
        @NotNull AstPlayer astPlayer,
        @NotNull List<MaterialRequirement> requirements,
        int requiredCurrency
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        for (MaterialRequirement requirement : requirements) {
            if (!inventoryService.consumeNormalItem(accountId, requirement.itemId, requirement.amount)) {
                return false;
            }
        }
        return inventoryService.consumeGold(accountId, requiredCurrency);
    }

    private boolean hasEnoughRequirements(
        @NotNull AstPlayer astPlayer,
        @NotNull List<MaterialRequirement> requirements,
        int requiredCurrency
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        long ownedGold = inventoryService.getGoldAmount(accountId);
        if (ownedGold < requiredCurrency) {
            return false;
        }
        return requirements.stream().allMatch(MaterialRequirement::enough);
    }

    private void render(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull EnhancementSession session
    ) {
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        EnhancementContext context = selection.context();
        List<MaterialRequirement> requirements = context == null
            ? List.of()
            : collectMaterialRequirements(Objects.requireNonNull(AstPlayerCache.get(player)), context);
        view.render(
            inventory,
            session.selectedEquipment == null ? null : session.selectedEquipment.clone(),
            createMaterialSummaryItem(selection.state(), requirements),
            createGuideItem(),
            createInfoItem(player, selection),
            createExecuteItem(player, selection, requirements, session.inFlightOperationId != null, session.confirmationPending)
        );
    }

    private void renderCurrent(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull EnhancementSession session
    ) {
        if (isProcessingMenu(inventory)) {
            AstPlayer astPlayer = Objects.requireNonNull(AstPlayerCache.get(player));
            if (session.processingScreen == ProcessingScreen.MATERIAL_LIST) {
                renderMaterialList(player, astPlayer, inventory, session);
            } else {
                renderProcessing(player, inventory, session);
            }
        } else {
            render(player, inventory, session);
        }
    }

    /**
     * 通常の装備加工画面を描画し、現在の加工状態を含む画面タイトルを同期します。
     *
     * @param player 操作中のプレイヤー
     * @param inventory 描画先の装備加工 inventory
     * @param session 対象装備を保持する加工セッション
     */
    private void renderProcessing(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull EnhancementSession session
    ) {
        boolean repairMode = session.mode == EquipmentProcessingMode.REPAIR;
        ItemStack selectedEquipment = session.selectedEquipment;
        SelectionResult selection = repairMode ? null : resolveSelection(selectedEquipment);
        EquipmentProcessingDisplayState displayState = processingDisplayState(session.mode, selection);
        updateProcessingTitle(player, inventory, processingView.processingTitle(displayState));
        List<MaterialRequirement> requirements = selection == null || selection.context() == null
            ? List.of()
            : collectMaterialRequirements(Objects.requireNonNull(AstPlayerCache.get(player)), selection.context());
        ItemStack infoItem = repairMode && equipmentRepairService != null
            ? equipmentRepairService.createProcessingInfoItem(player, selectedEquipment)
            : repairMode
                ? createItem(Material.SPYGLASS, Component.text("修理情報", NamedTextColor.YELLOW), List.of(
                    Component.text("修理情報を取得できません。", NamedTextColor.RED)))
                : createInfoItem(player, Objects.requireNonNull(selection));
        ItemStack executeItem = repairMode
            ? equipmentRepairService == null
                ? createItem(Material.BARRIER, Component.text("修理実行", NamedTextColor.RED, TextDecoration.BOLD), List.of(
                    Component.text("修理情報を取得できません。", NamedTextColor.RED)))
                : equipmentRepairService.createProcessingExecuteItem(player, selectedEquipment)
            : createExecuteItem(
                player,
                Objects.requireNonNull(selection),
                requirements,
                session.inFlightOperationId != null,
                session.confirmationPending
            );
        processingView.render(
            inventory,
            session.mode,
            displayState,
            selectedEquipment == null ? null : selectedEquipment.clone(),
            createProcessingGuideItem(displayState),
            infoItem,
            repairMode ? List.of() : createMaterialItems(requirements, displayState),
            repairMode ? createRepairMaterialInfoItem() : createMaterialListItem(selection, requirements),
            executeItem
        );
    }

    /**
     * 強化対象の全必要素材をページ表示します。対象がなくなった場合は通常の加工画面へ戻します。
     *
     * @param player 操作中のプレイヤー
     * @param astPlayer 操作中のプレイヤーのゲーム状態
     * @param inventory 描画先の装備加工 inventory
     * @param session 対象装備を保持する加工セッション
     */
    private void renderMaterialList(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull Inventory inventory,
        @NotNull EnhancementSession session
    ) {
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        if (session.mode != EquipmentProcessingMode.ENHANCEMENT || selection.context() == null) {
            session.processingScreen = ProcessingScreen.MAIN;
            session.materialListPage = 0;
            renderProcessing(player, inventory, session);
            return;
        }
        List<MaterialRequirement> requirements = collectMaterialRequirements(astPlayer, selection.context());
        EquipmentProcessingDisplayState displayState = processingDisplayState(session.mode, selection);
        List<ItemStack> materialItems = createMaterialItems(requirements, displayState);
        int pageCount = materialListPageCount(requirements);
        session.materialListPage = Math.max(0, Math.min(session.materialListPage, pageCount - 1));
        updateProcessingTitle(player, inventory, processingView.materialListTitle(displayState));
        processingView.renderMaterialList(inventory, displayState, materialItems, session.materialListPage, pageCount);
    }

    /**
     * 選択中タブと次の実行内容から、装備加工 GUI の常時表示状態を決定します。
     *
     * @param mode 現在選択中の修理または強化タブ
     * @param selection 現在セットしている装備の操作可否
     * @return タイトル・帯・アイコンに使用する加工状態
     */
    private @NotNull EquipmentProcessingDisplayState processingDisplayState(
        @NotNull EquipmentProcessingMode mode,
        @Nullable SelectionResult selection
    ) {
        boolean transcendenceReady = selection != null
            && selection.context() != null
            && selection.context().isTranscendence();
        return EquipmentProcessingDisplayState.from(mode, transcendenceReady);
    }

    /**
     * 現在開かれている装備加工 GUI のタイトルを必要なときだけ更新します。
     *
     * @param player 操作中のプレイヤー
     * @param inventory 表示対象の inventory
     * @param title 設定する画面タイトル
     */
    private void updateProcessingTitle(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull String title
    ) {
        if (player.getOpenInventory().getTopInventory() == inventory
            && !title.equals(player.getOpenInventory().getTitle())) {
            player.getOpenInventory().setTitle(title);
        }
    }

    /**
     * 現在の加工状態の操作手順と常時表示の見分け方を示すガイドアイテムを生成します。
     *
     * @param displayState 現在プレイヤーへ表示する加工状態
     * @return 加工ガイド表示アイテム
     */
    private @NotNull ItemStack createProcessingGuideItem(@NotNull EquipmentProcessingDisplayState displayState) {
        if (displayState == EquipmentProcessingDisplayState.REPAIR) {
            return createItem(
                Material.BOOK,
                Component.text("装備加工ガイド", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(
                    Component.text("修理: 下の装備をクリックしてセット", NamedTextColor.GREEN),
                    Component.text("セットした装備の耐久と必要ゴールドを確認します。", NamedTextColor.GRAY),
                    Component.text("修理実行をクリックすると最大耐久まで回復します。", NamedTextColor.GRAY),
                    Component.text("画面タイトルと緑色の帯が修理モードを示します。", NamedTextColor.GRAY)
                )
            );
        }
        if (displayState == EquipmentProcessingDisplayState.TRANSCENDENCE) {
            return createItem(
                Material.END_CRYSTAL,
                Component.text("状態変化ガイド", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                    Component.text("現在の強化上限に到達したため、自動で状態変化に切り替わっています。", NamedTextColor.GRAY),
                    Component.text("必要素材・変化後の内容・必要ゴールドを確認します。", NamedTextColor.GRAY),
                    Component.text("素材一覧をクリックすると全素材を実アイテムで確認できます。", NamedTextColor.GRAY),
                    Component.text("画面タイトル、水色の帯、結晶アイコンが状態変化中を示します。", NamedTextColor.GRAY),
                    Component.text("状態変化実行をクリックすると次の段階へ進みます。", NamedTextColor.GRAY)
                )
            );
        }
        return createItem(
            Material.BOOK,
            Component.text("装備加工ガイド", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("強化: 下の装備をクリックしてセット", NamedTextColor.GREEN),
                Component.text("必要素材・次の効果・必要ゴールドを確認します。", NamedTextColor.GRAY),
                Component.text("素材一覧をクリックすると全素材を実アイテムで確認できます。", NamedTextColor.GRAY),
                Component.text("画面タイトルと紫色の帯が強化モードを示します。", NamedTextColor.GRAY),
                Component.text("実行ボタンをクリックすると強化します。", NamedTextColor.GRAY)
            )
        );
    }

    /**
     * 強化または状態変化で消費する全素材を、必要数と所持数の lore を付けた実アイテム表示へ変換します。
     *
     * @param requirements 実行に必要な素材
     * @param displayState 素材を消費する加工状態
     * @return 素材一覧と通常画面の先行表示に使う実アイテム表示
     */
    private @NotNull List<ItemStack> createMaterialItems(
        @NotNull List<MaterialRequirement> requirements,
        @NotNull EquipmentProcessingDisplayState displayState
    ) {
        List<ItemStack> items = new ArrayList<>();
        for (MaterialRequirement requirement : requirements) {
            ItemStack item = !requirement.displayable()
                ? createItem(Material.CHEST, Component.text("未登録の素材", NamedTextColor.RED), List.of(
                    Component.text("素材情報を取得できません。", NamedTextColor.RED)))
                : itemStackFactory.createDisplay(requirement.model(), 1);
            ItemMeta meta = item.getItemMeta();
            List<Component> lore = meta != null && meta.lore() != null
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text(
                "必要数: " + requirement.amount() + " / 所持: " + requirement.ownedAmount(),
                requirement.enough() ? NamedTextColor.GREEN : NamedTextColor.RED
            ));
            lore.add(Component.text(displayState.displayName() + "実行時に消費", NamedTextColor.GRAY));
            if (meta != null) {
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 強化の全必要素材を確認する一覧ボタンを生成します。対象未選択時は操作不可の表示を返します。
     *
     * @param selection 現在の対象装備に対する強化可否
     * @param requirements 強化に必要な素材
     * @return 必要素材一覧を開く操作アイテム
     */
    private @NotNull ItemStack createMaterialListItem(
        @NotNull SelectionResult selection,
        @NotNull List<MaterialRequirement> requirements
    ) {
        if (selection.context() == null) {
            return createItem(Material.BARRIER, Component.text("必要素材一覧", NamedTextColor.RED, TextDecoration.BOLD), List.of(
                Component.text("装備をセットすると素材を確認できます。", NamedTextColor.GRAY)));
        }
        ItemStack item = createItem(Material.CHEST, Component.text("必要素材一覧", NamedTextColor.YELLOW, TextDecoration.BOLD), List.of(
            Component.text("必要素材: " + requirements.size() + " 種類", NamedTextColor.GRAY),
            Component.text("クリックして全素材を実アイテムで確認", NamedTextColor.YELLOW)
        ));
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), requirements.size())));
        return item;
    }

    /**
     * 修理が素材ではなくゴールドだけを消費することを示す表示アイテムを生成します。
     *
     * @return 修理時の消費情報アイテム
     */
    private @NotNull ItemStack createRepairMaterialInfoItem() {
        return createItem(Material.GOLD_INGOT, Component.text("必要素材なし", NamedTextColor.GREEN, TextDecoration.BOLD), List.of(
            Component.text("修理ではゴールドのみを消費します。", NamedTextColor.GRAY),
            Component.text("必要ゴールドは修理実行で確認できます。", NamedTextColor.GRAY)));
    }

    /**
     * 必要素材数から一覧画面の全ページ数を算出します。
     *
     * @param requirements 強化に必要な素材
     * @return 少なくとも1となる一覧ページ数
     */
    private int materialListPageCount(@NotNull List<MaterialRequirement> requirements) {
        return Math.max(1, (requirements.size() + PagedGuiView.CONTENT_SLOT_COUNT - 1)
            / PagedGuiView.CONTENT_SLOT_COUNT);
    }

    private @NotNull ItemStack createMaterialSummaryItem(
        @NotNull SelectionState state,
        @NotNull List<MaterialRequirement> requirements
    ) {
        List<Component> lore = new ArrayList<>();
        if (requirements.isEmpty()) {
            if (state == SelectionState.READY) {
                lore.add(Component.text("この操作で消費するアイテムはありません。", NamedTextColor.GRAY));
                lore.add(Component.text("必要ゴールドは操作情報を確認してください。", NamedTextColor.GRAY));
            } else {
                lore.add(Component.text("装備をセットすると消費アイテムを一覧表示します。", NamedTextColor.GRAY));
                lore.add(Component.text("必要ゴールドは強化情報に表示されます。", NamedTextColor.GRAY));
            }
        } else {
            lore.add(Component.text("強化・状態変化の実行時に消費されるアイテムです。", NamedTextColor.GRAY));
            lore.add(Component.empty());
            for (MaterialRequirement requirement : requirements) {
                lore.add(Component.text(
                    materialRequirementLine(requirement),
                    requirement.enough() ? NamedTextColor.GREEN : NamedTextColor.RED
                ));
            }
        }
        return createItem(
            Material.CHEST,
            Component.text("消費アイテム", NamedTextColor.YELLOW, TextDecoration.BOLD),
            lore
        );
    }

    private @NotNull String materialRequirementLine(@NotNull MaterialRequirement requirement) {
        return materialDisplayName(requirement) + ": " + requirement.amount + " / 所持 " + requirement.ownedAmount;
    }

    private @NotNull ItemStack createGuideItem() {
        return createItem(
            Material.ANVIL,
            Component.text("強化ガイド", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("1. 下の装備インベントリから装備をクリックしてセットします。", NamedTextColor.GRAY),
                Component.text("2. 必要素材とゴールドが揃うと実行可能になります。", NamedTextColor.GRAY),
                Component.text("3. 強化上限では次の状態変化を実行できます。", NamedTextColor.GRAY)
            )
        );
    }

    private @NotNull ItemStack createInfoItem(@NotNull Player player, @NotNull SelectionResult selection) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            return createItem(
                Material.NETHER_STAR,
                Component.text("強化情報", NamedTextColor.YELLOW),
                List.of(Component.text("強化情報を取得できません。", NamedTextColor.RED))
            );
        }

        if (selection.state() == SelectionState.NONE_SELECTED) {
            return createItem(
                Material.NETHER_STAR,
                Component.text("強化情報", NamedTextColor.YELLOW),
                List.of(
                    Component.text("装備をセットすると次の強化情報を表示します。", NamedTextColor.GRAY),
                    Component.text("強化値 / 成功率 / 失敗時挙動を確認できます。", NamedTextColor.GRAY),
                    Component.text("必要素材とゴールドは実行ボタンに表示します。", NamedTextColor.GRAY)
                )
            );
        }

        EnhancementContext context = selection.context();
        if (context == null) {
            List<Component> lore = new ArrayList<>();
            if (selection.instance() != null) {
                lore.add(Component.text("現在強化値: +" + selection.instance().getEnhanceLevel(), NamedTextColor.GRAY));
            }
            lore.add(Component.text(selection.state().message(), NamedTextColor.RED));
            return createItem(
                Material.NETHER_STAR,
                Component.text("強化情報", NamedTextColor.YELLOW),
                lore
            );
        }

        if (context.isTranscendence()) {
            ItemEquipmentTranscendence transcendence = Objects.requireNonNull(context.transcendence);
            List<Component> lore = new ArrayList<>(List.of(
                Component.text("現在強化値: +" + context.instance.getEnhanceLevel(), NamedTextColor.GRAY),
                Component.text("現在耐久: " + context.instance.getDurabilityValue() + " / " + context.instance.getDurabilityMax(), NamedTextColor.GRAY),
                Component.text("状態変化: " + context.transcendenceDisplayName(), NamedTextColor.GRAY),
                Component.text("必要強化値: +" + transcendence.getRequiredEnhanceLevel(), NamedTextColor.GRAY)
            ));
            if (transcendence.getOverridesName() != null && !transcendence.getOverridesName().isBlank()) {
                lore.add(Component.text("変化後名称: " + ColorCodeUtil.toPlainText(transcendence.getOverridesName(), "状態変化"), NamedTextColor.AQUA));
            }
            if (transcendence.getOverridesEnhanceMaxLevel() != null) {
                lore.add(Component.text("強化上限: " + transcendence.getOverridesEnhanceMaxLevel(), NamedTextColor.AQUA));
            }
            if (transcendence.getOverridesEnchantMaxSlots() != null) {
                lore.add(Component.text("エンチャント枠: " + transcendence.getOverridesEnchantMaxSlots(), NamedTextColor.AQUA));
            }
            lore.add(Component.text("必要素材とゴールドは実行ボタンを確認してください。", NamedTextColor.GRAY));
            return createItem(
                Material.NETHER_STAR,
                Component.text("次の状態変化", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                lore
            );
        }
        ItemEquipmentEnhanceLevel nextLevel = Objects.requireNonNull(context.nextLevel);
        List<Component> lore = new ArrayList<>(List.of(
            Component.text("現在強化値: +" + context.instance.getEnhanceLevel(), NamedTextColor.GRAY),
            Component.text("次の強化値: +" + nextLevel.getLevel(), NamedTextColor.GRAY),
            Component.text("現在耐久: " + context.instance.getDurabilityValue() + " / " + context.instance.getDurabilityMax(), NamedTextColor.GRAY),
            Component.text("成功率: " + formatPercent(normalizeSuccessRate(nextLevel.getSuccessRate())) + "%", NamedTextColor.GRAY),
            Component.text("失敗時: " + failActionLabel(nextLevel.getFailAction()), nextLevel.getFailAction() == ItemEquipmentEnhanceFailAction.DESTROY ? NamedTextColor.RED : NamedTextColor.GRAY)
        ));
        appendEnhancementPreviewLore(lore, nextLevel);
        lore.add(Component.text("必要素材とゴールドは実行ボタンを確認してください。", NamedTextColor.GRAY));
        return createItem(
            Material.NETHER_STAR,
            Component.text("次の強化情報", NamedTextColor.AQUA, TextDecoration.BOLD),
            lore
        );
    }

    private void appendEnhancementPreviewLore(
        @NotNull List<Component> lore,
        @NotNull ItemEquipmentEnhanceLevel nextLevel
    ) {
        for (var increase : nextLevel.getStatIncrease()) {
            String range = formatNumber(increase.getMin()) + (Double.compare(increase.getMin(), increase.getMax()) == 0
                ? ""
                : "～" + formatNumber(increase.getMax()));
            lore.add(Component.text("増加: " + statusDisplayName(increase.getStatus()) + " +" + range, NamedTextColor.GREEN));
        }
        if (nextLevel.getDurabilityBonus() != null && nextLevel.getDurabilityBonus() != 0) {
            lore.add(Component.text("最大耐久: +" + nextLevel.getDurabilityBonus(), NamedTextColor.GREEN));
        }
    }

    private @NotNull ItemStack createExecuteItem(
        @NotNull Player player,
        @NotNull SelectionResult selection,
        @NotNull List<MaterialRequirement> requirements,
        boolean inFlight,
        boolean confirmationPending
    ) {
        if (inFlight) {
            return createItem(
                Material.BARRIER,
                PlayerMsgResource.getComponent(PlayerMsgId.P_5282.getId()).decorate(TextDecoration.BOLD),
                List.of(PlayerMsgResource.getComponent(PlayerMsgId.P_6700.getId()))
            );
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            return createItem(
                Material.BARRIER,
                Component.text("強化実行", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("強化情報を取得できません。", NamedTextColor.RED))
            );
        }

        EnhancementContext context = selection.context();
        if (selection.state() != SelectionState.READY || context == null) {
            return createItem(
                Material.BARRIER,
                Component.text("強化実行", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                    Component.text("クリックしてもこの装備はまだ強化できません。", NamedTextColor.GRAY),
                    Component.text(selection.state().message(), NamedTextColor.RED)
                )
            );
        }

        boolean executable = hasEnoughRequirements(astPlayer, requirements, context.requiredCurrency());
        String operationName = context.isTranscendence() ? "状態変化実行" : "強化実行";
        boolean requiresConfirmation = requiresConfirmation(context);
        long ownedGold = inventoryService.getGoldAmount(astPlayer.getAccount().getUuid());
        List<Component> requirementLore = createExecutionRequirementLore(context, requirements, ownedGold);
        if (executable && requiresConfirmation && confirmationPending) {
            List<Component> lore = new ArrayList<>(requirementLore);
            lore.add(Component.empty());
            lore.add(Component.text("失敗すると装備が破壊されます。", NamedTextColor.RED));
            lore.add(Component.text("確認のため、もう一度クリックしてください。", NamedTextColor.YELLOW));
            return createItem(
                Material.RED_CONCRETE,
                Component.text("もう一度クリックして実行", NamedTextColor.RED, TextDecoration.BOLD),
                lore
            );
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(
            context.isTranscendence()
                ? "クリックするとこの装備の状態変化を実行します。"
                : "クリックするとこの装備の強化を実行します。",
            NamedTextColor.GRAY
        ));
        lore.addAll(requirementLore);
        lore.add(Component.text(
            executable ? "必要素材とゴールドが揃っています。" : "必要素材またはゴールドが不足しています。",
            executable ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
        lore.add(requiresConfirmation
            ? Component.text("失敗時: 装備破壊（実行時に確認）", NamedTextColor.RED)
            : Component.text("実行前に内容を確認してください。", NamedTextColor.GRAY));
        return createItem(
            executable ? Material.LIME_CONCRETE : Material.BARRIER,
            Component.text(operationName, executable ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
            lore
        );
    }

    /** 実行ボタンに必要ゴールドと全消費素材をまとめて表示する lore を生成します。 */
    private @NotNull List<Component> createExecutionRequirementLore(
        @NotNull EnhancementContext context,
        @NotNull List<MaterialRequirement> requirements,
        long ownedGold
    ) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(
            "必要ゴールド: " + context.requiredCurrency() + " / 所持: " + ownedGold,
            ownedGold >= context.requiredCurrency() ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
        if (requirements.isEmpty()) {
            lore.add(Component.text("必要素材: なし", NamedTextColor.GRAY));
            return lore;
        }
        lore.add(Component.text("必要素材:", NamedTextColor.YELLOW));
        for (MaterialRequirement requirement : requirements) {
            lore.add(Component.text(
                "- " + materialRequirementLine(requirement),
                requirement.enough() ? NamedTextColor.GREEN : NamedTextColor.RED
            ));
        }
        return lore;
    }

    private boolean requiresConfirmation(@NotNull EnhancementContext context) {
        return !context.isTranscendence()
            && Objects.requireNonNull(context.nextLevel).getFailAction() == ItemEquipmentEnhanceFailAction.DESTROY;
    }

    private @NotNull SelectionResult resolveSelection(@Nullable ItemStack selectedEquipment) {
        if (selectedEquipment == null || selectedEquipment.getType() == Material.AIR) {
            return new SelectionResult(SelectionState.NONE_SELECTED, null, null, null);
        }

        ItemReference reference = itemReferenceResolver.resolve(selectedEquipment);
        if (reference == null || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return new SelectionResult(SelectionState.INVALID_TARGET, null, null, null);
        }

        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (!isEquipmentModel(model) || instance == null || !hasPlayerFacingName(model)) {
            return new SelectionResult(SelectionState.INVALID_TARGET, model, instance, null);
        }

        ItemEquipment equipment = Objects.requireNonNull(model.getEquipment());
        ItemEquipmentEnhance enhance = equipment.getEnhance();
        if (enhance == null) {
            return new SelectionResult(SelectionState.NO_ENHANCE_DATA, model, instance, null);
        }

        int effectiveMaxLevel = resolveEffectiveMaxLevel(equipment, instance);
        if (instance.getEnhanceLevel() >= effectiveMaxLevel) {
            ItemEquipmentTranscendence nextTranscendence = equipment.getTranscendence().stream()
                .filter(transcendence -> transcendence.getRank() > instance.getTranscendenceRank())
                .min(Comparator.comparingInt(ItemEquipmentTranscendence::getRank))
                .orElse(null);
            if (nextTranscendence == null) {
                return new SelectionResult(SelectionState.MAX_LEVEL, model, instance, null);
            }
            if (instance.getEnhanceLevel() < nextTranscendence.getRequiredEnhanceLevel()) {
                return new SelectionResult(SelectionState.TRANSCENDENCE_REQUIREMENT, model, instance, null);
            }
            return new SelectionResult(
                SelectionState.READY,
                model,
                instance,
                new EnhancementContext(model, instance, null, nextTranscendence)
            );
        }

        ItemEquipmentEnhanceLevel nextLevel = enhance.getLevels().stream()
            .filter(level -> level.getLevel() == instance.getEnhanceLevel() + 1)
            .min(Comparator.comparingInt(ItemEquipmentEnhanceLevel::getLevel))
            .orElse(null);
        if (nextLevel == null) {
            return new SelectionResult(SelectionState.NEXT_LEVEL_MISSING, model, instance, null);
        }

        return new SelectionResult(
            SelectionState.READY,
            model,
            instance,
            new EnhancementContext(model, instance, nextLevel, null)
        );
    }

    private @NotNull List<MaterialRequirement> collectMaterialRequirements(
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementContext context
    ) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (ItemEquipmentEnhanceMaterial material : context.requiredMaterials()) {
            if (material.getItemId() == null || material.getItemId().isBlank() || material.getAmount() <= 0) {
                continue;
            }
            merged.merge(material.getItemId(), material.getAmount(), Integer::sum);
        }

        List<MaterialRequirement> requirements = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            String itemId = entry.getKey();
            int amount = entry.getValue();
            ItemModel model = itemService.findLoadedById(itemId);
            if (model == null) {
                model = itemService.loadItem(itemId);
            }
            long ownedAmount = inventoryService.getNormalItemAmount(astPlayer.getAccount().getUuid(), itemId);
            requirements.add(new MaterialRequirement(itemId, amount, ownedAmount, model, hasPlayerFacingName(model)));
        }
        return requirements;
    }

    private int resolveEffectiveMaxLevel(@NotNull ItemEquipment equipment, @NotNull EquipmentInstance instance) {
        int maxLevel = equipment.getEnhance() == null ? 0 : equipment.getEnhance().getMaxLevel();
        for (ItemEquipmentTranscendence transcendence : equipment.getTranscendence().stream()
            .sorted(Comparator.comparingInt(ItemEquipmentTranscendence::getRank))
            .toList()) {
            if (transcendence.getRank() > instance.getTranscendenceRank()) {
                break;
            }
            if (transcendence.getOverridesEnhanceMaxLevel() != null) {
                maxLevel = transcendence.getOverridesEnhanceMaxLevel();
            }
        }
        return maxLevel;
    }

    private boolean isEquipmentModel(@Nullable ItemModel model) {
        return model != null && model.getEquipment() != null;
    }

    private synchronized @Nullable EnhancementSession getOrCreateSession(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        PlayerInventoryState inventoryState = inventoryStateRegistry.get(accountId);
        if (inventoryState == null) {
            return null;
        }
        EnhancementSession current = sessions.get(player.getUniqueId());
        if (current != null && current.owner == player && current.inventoryState == inventoryState) {
            return current;
        }
        if (current != null && sessions.remove(player.getUniqueId(), current)) {
            detachForSave(current);
        }
        EnhancementSession created = new EnhancementSession(
            player,
            accountId,
            inventoryState,
            inventoryService.getDisplayedInventoryType(accountId)
        );
        sessions.put(player.getUniqueId(), created);
        return created;
    }

    /**
     * 加工モードを切り替え、素材一覧表示と確認待ち状態を通常画面へ戻します。
     *
     * @param player 操作したプレイヤー
     * @param session 対象装備を保持する加工セッション
     * @param mode 切り替え先の加工モード
     */
    private void switchProcessingMode(
        @NotNull Player player,
        @NotNull EnhancementSession session,
        @NotNull EquipmentProcessingMode mode
    ) {
        if (session.mode != mode) {
            session.confirmationPending = false;
            session.mode = mode;
        }
        session.processingScreen = ProcessingScreen.MAIN;
        session.materialListPage = 0;
        if (isProcessingMenu(player.getOpenInventory().getTopInventory())) {
            renderProcessing(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
        }
    }

    private boolean matchesOwnedEntry(
        @Nullable InventoryEntryModel entry,
        @NotNull ItemStack displayedItem
    ) {
        if (entry == null || entry.getInstanceId() == null) {
            return false;
        }
        ItemReference reference = itemReferenceResolver.resolve(displayedItem);
        return reference != null
            && reference.hasEquipmentInstanceId()
            && entry.getInstanceId().toString().equalsIgnoreCase(reference.equipmentInstanceId());
    }

    private void detachForSave(@NotNull EnhancementSession session) {
        session.detached = true;
        session.closeRequested = true;
        session.entryRestoredForSave = EquipmentOperationInventoryState.restoreEntry(
            session.inventoryState,
            session.selectedEntry
        );
        if (!session.entryRestoredForSave) {
            Logger.log(LogId.W_5203, "enhancement_logout_restore", session.accountId);
        }
        restoreBukkitInventoryBeforeSave(session);

        CompletableFuture<EnhancementResult> operationFuture = session.operationFuture;
        if (session.inFlightOperationId == null || operationFuture == null) {
            inventorySaveCoordinator.enqueueLogoutReconciliation(session.accountId, () -> {
                boolean restored = EquipmentOperationInventoryState.restoreEntry(
                    session.inventoryState,
                    session.selectedEntry
                );
                clearHeldEquipment(session);
                if (!restored) {
                    Logger.log(LogId.W_5203, "enhancement_logout_entry", session.accountId);
                }
                return restored;
            });
            return;
        }
        inventorySaveCoordinator.enqueueLogoutReconciliation(
            session.accountId,
            () -> reconcileDetachedOperation(session, operationFuture)
        ).exceptionally(throwable -> {
            Logger.log(LogId.W_5203, "enhancement_logout_reconciliation", session.accountId);
            return false;
        });
    }

    private void restoreBukkitInventoryBeforeSave(@NotNull EnhancementSession session) {
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
        @NotNull EnhancementSession session,
        @NotNull CompletableFuture<EnhancementResult> operationFuture
    ) {
        EnhancementResult result;
        try {
            result = operationFuture.join();
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
                Logger.log(LogId.W_5203, "enhancement_logout_api", session.accountId);
            }
            return compensated && restored;
        }

        if (result == null) {
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
                Logger.log(LogId.W_5203, "enhancement_logout_result", session.accountId);
            }
            return compensated && restored;
        }

        boolean reconciled = session.entryRestoredForSave;
        if (result.type == EnhancementResultType.FAIL_DESTROY) {
            reconciled = EquipmentOperationInventoryState.removeEntry(
                session.inventoryState,
                session.selectedEntry
            );
        }
        clearHeldEquipment(session);
        if (!reconciled) {
            Logger.log(LogId.W_5203, "enhancement_logout_entry", session.accountId);
        }
        return reconciled;
    }

    private void clearHeldEquipment(@NotNull EnhancementSession session) {
        session.selectedEquipment = null;
        session.selectedEntry = null;
        session.inFlightOperationId = null;
        session.operationFuture = null;
        session.paymentSnapshot = null;
        session.confirmationPending = false;
    }

    private boolean returnSelectedEquipment(@NotNull AstPlayer astPlayer, @NotNull EnhancementSession session) {
        if (session.selectedEquipment == null || session.selectedEquipment.getType() == Material.AIR) {
            return false;
        }
        restoreHeldEquipment(astPlayer, session);
        session.selectedEquipment = null;
        session.selectedEntry = null;
        session.confirmationPending = false;
        return true;
    }

    private void releaseSession(
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session,
        boolean restoreDisplayedInventory
    ) {
        sessions.remove(session.owner.getUniqueId(), session);
        if (session.selectedEquipment != null && session.selectedEquipment.getType() != Material.AIR) {
            restoreHeldEquipment(astPlayer, session);
            session.selectedEquipment = null;
            session.selectedEntry = null;
        }
        if (restoreDisplayedInventory && session.owner == astPlayer.getBukkit()) {
            restoreDisplayedInventory(astPlayer, session);
        }
    }

    private void restoreDisplayedInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session
    ) {
        if (session.previousDisplayedType != null) {
            inventoryService.applyInventoryToGui(astPlayer, session.previousDisplayedType);
        }
    }

    /** 退避装備を元stateへ戻し、失敗時も現在の所持品へ返却します。 */
    private void restoreHeldEquipment(@NotNull AstPlayer astPlayer, @NotNull EnhancementSession session) {
        if (EquipmentOperationInventoryState.restoreEntry(session.inventoryState, session.selectedEntry)) return;
        if (inventoryService.returnItemToOwnedInventory(astPlayer, session.selectedEquipment.clone()) != null) return;
        astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedEquipment.clone());
        Logger.log(LogId.W_5203, "enhancement_close_restore", session.accountId);
    }

    private void playSuccessEffects(@NotNull Player player) {
        GuiSound.UPGRADE.play(player);
        particleDisplayService.spawnForNearbyViewers(
            player.getLocation().add(0.0, 1.0, 0.0),
            SharedParticleDefinitions.EQUIPMENT_ENHANCEMENT_SUCCESS
        );
    }

    private void playFailureEffects(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.7f, 0.9f);
        particleDisplayService.spawnForNearbyViewers(
            player.getLocation().add(0.0, 1.0, 0.0),
            SharedParticleDefinitions.EQUIPMENT_ENHANCEMENT_FAILURE
        );
    }

    private void playDestroyEffects(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.85f, 0.8f);
        particleDisplayService.spawnForNearbyViewers(
            player.getLocation().add(0.0, 1.0, 0.0),
            SharedParticleDefinitions.EQUIPMENT_ENHANCEMENT_DESTROY
        );
    }

    private double normalizeSuccessRate(double rawRate) {
        double normalized = rawRate > 1.0 ? rawRate / 100.0 : rawRate;
        return Math.clamp(normalized, 0.0, 1.0);
    }

    private @NotNull String failActionLabel(@NotNull ItemEquipmentEnhanceFailAction failAction) {
        return switch (failAction) {
            case NONE -> "変化なし";
            case DOWNGRADE -> "強化値低下";
            case DESTROY -> "装備破壊";
        };
    }

    private @NotNull String formatPercent(double rate) {
        return BigDecimal.valueOf(rate * 100.0).stripTrailingZeros().toPlainString();
    }

    private @NotNull String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private @NotNull String displayName(@NotNull ItemModel model) {
        return ColorCodeUtil.toPlainText(model.getName(), "未登録の装備");
    }

    private boolean hasPlayerFacingName(@Nullable ItemModel model) {
        return model != null && !ColorCodeUtil.toPlainText(model.getName(), "").isBlank();
    }

    private @NotNull String materialDisplayName(@NotNull MaterialRequirement requirement) {
        if (!requirement.displayable()) {
            return "未登録の素材";
        }
        return ColorCodeUtil.toPlainText(Objects.requireNonNull(requirement.model).getName(), "未登録の素材");
    }

    private @NotNull String statusDisplayName(@Nullable String statusId) {
        if (statusId == null || statusId.isBlank()) {
            return "未登録の能力値";
        }
        StatusType statusType = StatusType.fromId(statusId);
        return statusType == null ? "未登録の能力値" : statusType.getDisplayName();
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }

    private static final class EnhancementSession {
        private final Player owner;
        private final UUID accountId;
        private final PlayerInventoryState inventoryState;
        private final InventoryType previousDisplayedType;
        private EquipmentProcessingMode mode = EquipmentProcessingMode.ENHANCEMENT;
        private ProcessingScreen processingScreen = ProcessingScreen.MAIN;
        private int materialListPage;
        private ItemStack selectedEquipment;
        private InventoryEntryModel selectedEntry;
        private UUID inFlightOperationId;
        private CompletableFuture<EnhancementResult> operationFuture;
        private InventoryService.InventoryStateSnapshot paymentSnapshot;
        private boolean closeRequested;
        private boolean confirmationPending;
        private volatile boolean detached;
        private boolean entryRestoredForSave;

        private EnhancementSession(
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

    /** 装備加工 GUI 内で切り替える表示画面です。 */
    private enum ProcessingScreen {
        MAIN,
        MATERIAL_LIST
    }

    private record SelectionResult(
        @NotNull SelectionState state,
        @Nullable ItemModel model,
        @Nullable EquipmentInstance instance,
        @Nullable EnhancementContext context
    ) {
    }

    private record EnhancementContext(
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        @Nullable ItemEquipmentEnhanceLevel nextLevel,
        @Nullable ItemEquipmentTranscendence transcendence
    ) {
        private boolean isTranscendence() {
            return transcendence != null;
        }

        private @NotNull List<ItemEquipmentEnhanceMaterial> requiredMaterials() {
            return isTranscendence()
                ? Objects.requireNonNull(transcendence).getRequiredMaterials()
                : Objects.requireNonNull(nextLevel).getRequiredMaterials();
        }

        private int requiredCurrency() {
            return isTranscendence()
                ? Objects.requireNonNull(transcendence).getRequiredCurrency()
                : Objects.requireNonNull(nextLevel).getRequiredCurrency();
        }

        private double successRate() {
            return isTranscendence() ? 1.0D : Objects.requireNonNull(nextLevel).getSuccessRate();
        }

        private @NotNull String transcendenceDisplayName() {
            ItemEquipmentTranscendence definition = Objects.requireNonNull(transcendence);
            return definition.getName() == null || definition.getName().isBlank()
                ? "ランク " + definition.getRank()
                : ColorCodeUtil.toPlainText(definition.getName(), String.valueOf(definition.getRank()));
        }
    }

    private record MaterialRequirement(
        @NotNull String itemId,
        int amount,
        long ownedAmount,
        @Nullable ItemModel model,
        boolean displayable
    ) {
        private boolean enough() {
            return displayable && ownedAmount >= amount;
        }
    }

    private record EnhancementResult(
        @NotNull EnhancementResultType type,
        @Nullable EquipmentInstance updatedInstance
    ) {
    }

    private enum EnhancementResultType {
        SUCCESS,
        FAIL_NONE,
        FAIL_DOWNGRADE,
        FAIL_DESTROY
    }

    private enum SelectionState {
        NONE_SELECTED("強化する装備をセットしてください。"),
        INVALID_TARGET("選択した装備の情報を取得できません。"),
        NO_ENHANCE_DATA("この装備には強化データが定義されていません。"),
        MAX_LEVEL("この装備は現在の強化上限に達しています。"),
        TRANSCENDENCE_REQUIREMENT("状態変化に必要な強化値を満たしていません。"),
        NEXT_LEVEL_MISSING("次の強化レベル定義が見つかりません。"),
        READY("");

        private final String message;

        SelectionState(@NotNull String message) {
            this.message = message;
        }

        private @NotNull String message() {
            return message;
        }
    }
}

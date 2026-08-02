package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.service.EquipmentOperationInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceMaterial;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentEnhancementMenuScreenView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
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
    private static final Component TITLE = Component.text("装備強化", NamedTextColor.GOLD);

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
    private final Map<UUID, EnhancementSession> sessions = new ConcurrentHashMap<>();

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

    public boolean isEnhancementMenu(@Nullable Inventory inventory) {
        return menuView.isMenuInventory(inventory)
            && menuView.getMenuScreen(inventory) == MenuScreen.EQUIPMENT_ENHANCE;
    }

    public void open(@NotNull Player player) {
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
        session.closeRequested = false;

        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.EQUIPMENT_ENHANCE, -1, 0),
            BaseMenuScreenView.SIZE,
            TITLE
        );
        render(player, inventory, session);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void handleTopClick(@NotNull Player player, int rawSlot) {
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

    private void executeEnhancement(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session
    ) {
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        EnhancementContext context = selection.context();
        if (context == null) {
            if (selection.state() == SelectionState.NONE_SELECTED) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5261);
            }
            GuiSound.DENY.play(player);
            return;
        }

        List<MaterialRequirement> requirements = collectMaterialRequirements(astPlayer, context);
        if (!hasEnoughRequirements(astPlayer, requirements, context.requiredCurrency())) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5254);
            GuiSound.DENY.play(player);
            return;
        }

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
        render(player, player.getOpenInventory().getTopInventory(), session);
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
            || !isEnhancementMenu(player.getOpenInventory().getTopInventory())) {
            releaseSession(astPlayer, session, player.isOnline());
            return;
        }
        inventoryService.applyInventoryToGui(astPlayer, InventoryType.BAG);
        player.updateInventory();
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
            createExecuteItem(player, selection, requirements, session.inFlightOperationId != null)
        );
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
        String name = requirement.model == null ? requirement.itemId : displayName(requirement.model);
        return name + ": " + requirement.amount + " / 所持 " + requirement.ownedAmount;
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
                Material.BOOK,
                Component.text("強化情報", NamedTextColor.YELLOW),
                List.of(Component.text("強化情報を取得できません。", NamedTextColor.RED))
            );
        }

        if (selection.state() == SelectionState.NONE_SELECTED) {
            return createItem(
                Material.BOOK,
                Component.text("強化情報", NamedTextColor.YELLOW),
                List.of(
                    Component.text("装備をセットすると次の強化情報を表示します。", NamedTextColor.GRAY),
                    Component.text("強化値 / 成功率 / 失敗時挙動 / 必要ゴールド", NamedTextColor.GRAY)
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
                Material.BOOK,
                Component.text("強化情報", NamedTextColor.YELLOW),
                lore
            );
        }

        long ownedGold = inventoryService.getGoldAmount(astPlayer.getAccount().getUuid());
        if (context.isTranscendence()) {
            ItemEquipmentTranscendence transcendence = Objects.requireNonNull(context.transcendence);
            return createItem(
                Material.NETHER_STAR,
                Component.text("次の状態変化", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                List.of(
                    Component.text("現在強化値: +" + context.instance.getEnhanceLevel(), NamedTextColor.GRAY),
                    Component.text("状態変化: " + context.transcendenceDisplayName(), NamedTextColor.GRAY),
                    Component.text("必要強化値: +" + transcendence.getRequiredEnhanceLevel(), NamedTextColor.GRAY),
                    Component.text(
                        "必要ゴールド: " + context.requiredCurrency() + " / 所持: " + ownedGold,
                        ownedGold >= context.requiredCurrency() ? NamedTextColor.GREEN : NamedTextColor.RED
                    )
                )
            );
        }
        ItemEquipmentEnhanceLevel nextLevel = Objects.requireNonNull(context.nextLevel);
        return createItem(
            Material.KNOWLEDGE_BOOK,
            Component.text("次の強化情報", NamedTextColor.AQUA, TextDecoration.BOLD),
            List.of(
                Component.text("現在強化値: +" + context.instance.getEnhanceLevel(), NamedTextColor.GRAY),
                Component.text("次の強化値: +" + nextLevel.getLevel(), NamedTextColor.GRAY),
                Component.text("成功率: " + formatPercent(normalizeSuccessRate(nextLevel.getSuccessRate())) + "%", NamedTextColor.GRAY),
                Component.text("失敗時: " + failActionLabel(nextLevel.getFailAction()), NamedTextColor.GRAY),
                Component.text(
                    "必要ゴールド: " + context.requiredCurrency() + " / 所持: " + ownedGold,
                    ownedGold >= context.requiredCurrency() ? NamedTextColor.GREEN : NamedTextColor.RED
                )
            )
        );
    }

    private @NotNull ItemStack createExecuteItem(
        @NotNull Player player,
        @NotNull SelectionResult selection,
        @NotNull List<MaterialRequirement> requirements,
        boolean inFlight
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
        return createItem(
            executable ? Material.ANVIL : Material.BARRIER,
            Component.text(operationName, executable ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
            List.of(
                Component.text(
                    context.isTranscendence()
                        ? "クリックするとこの装備の状態変化を実行します。"
                        : "クリックするとこの装備の強化を実行します。",
                    NamedTextColor.GRAY
                ),
                Component.text(
                    executable ? "必要素材とゴールドが揃っています。" : "必要素材またはゴールドが不足しています。",
                    executable ? NamedTextColor.GREEN : NamedTextColor.RED
                )
            )
        );
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
        if (!isEquipmentModel(model) || instance == null) {
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
            requirements.add(new MaterialRequirement(itemId, amount, ownedAmount, model));
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
    }

    private boolean returnSelectedEquipment(@NotNull AstPlayer astPlayer, @NotNull EnhancementSession session) {
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
        @NotNull EnhancementSession session,
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
        @NotNull EnhancementSession session
    ) {
        if (session.previousDisplayedType != null) {
            inventoryService.applyInventoryToGui(astPlayer, session.previousDisplayedType);
        }
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

    private static final class EnhancementSession {
        private final Player owner;
        private final UUID accountId;
        private final PlayerInventoryState inventoryState;
        private final InventoryType previousDisplayedType;
        private ItemStack selectedEquipment;
        private InventoryEntryModel selectedEntry;
        private UUID inFlightOperationId;
        private CompletableFuture<EnhancementResult> operationFuture;
        private InventoryService.InventoryStateSnapshot paymentSnapshot;
        private boolean closeRequested;
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
        @Nullable ItemModel model
    ) {
        private boolean enough() {
            return ownedAmount >= amount;
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

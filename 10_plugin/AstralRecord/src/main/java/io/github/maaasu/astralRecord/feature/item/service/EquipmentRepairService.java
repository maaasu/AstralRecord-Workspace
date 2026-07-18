package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EquipmentRepairService {
    private static final Component TITLE = Component.text("装備修理", NamedTextColor.GOLD);

    private final MenuView menuView;
    private final InventoryService inventoryService;
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
     * @param itemService 装備インスタンス更新に使うアイテムサービス
     * @param itemStackFactory 更新後の装備 ItemStack 生成サービス
     * @param particleDisplayService 修理成功時の共通パーティクル表示サービス
     */
    public EquipmentRepairService(
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.menuView = menuView;
        this.inventoryService = inventoryService;
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
        RepairSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new RepairSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );
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
        RepairSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new RepairSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );
        if (rawSlot == EquipmentRepairMenuScreenView.TARGET_SLOT) {
            if (!returnSelectedEquipment(astPlayer, session)) {
                GuiSound.DENY.play(player);
                return;
            }
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
            inventoryService.returnItemToOwnedInventory(astPlayer, selected);
            GuiSound.DENY.play(player);
            return true;
        }

        RepairSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new RepairSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );
        ItemStack previous = session.selectedEquipment;
        session.selectedEquipment = selected.clone();
        if (previous != null && previous.getType() != Material.AIR) {
            if (inventoryService.returnItemToOwnedInventory(astPlayer, previous.clone()) == null) {
                inventoryService.returnItemToOwnedInventory(astPlayer, selected.clone());
                session.selectedEquipment = previous;
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
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            sessions.remove(player.getUniqueId());
            return;
        }
        releaseSession(astPlayer, true);
    }

    /**
     * 修理費用を計算します。
     * 耐久値が高い装備ほど軽い倍率を乗せ、欠損耐久 1 につき最低 1 Gold を要求します。
     *
     * @param durabilityMax 装備の最大耐久値
     * @param missingDurability 欠損している耐久値
     * @return 修理に必要な Gold。修理不要な場合は {@code 0}
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
        EquipmentInstance updated = itemService.updateEquipmentDurability(
            context.instance().getEquipmentInstanceId(),
            context.instance().getDurabilityMax(),
            astPlayer.getAccount().getUuid().toString()
        );
        if (updated == null) {
            restorePayment(paymentSnapshot, accountId, "repair_api");
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5277);
            return;
        }
        session.selectedEquipment = itemStackFactory.create(context.model(), updated, 1);
        inventoryService.saveNow(accountId);
        if (wasBroken && statusService != null) {
            statusService.refreshStatus(astPlayer);
        }
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5278, displayName(context.model()), context.cost());
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 1.2f);
        particleDisplayService.spawnForNearbyViewers(
            player.getLocation().add(0.0, 1.0, 0.0),
            SharedParticleDefinitions.EQUIPMENT_REPAIR_ENCHANT
        );
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
            createExecuteItem(player, selection)
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
                    "必要Gold: " + context.cost() + " / 所持 " + ownedGold,
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

    private @NotNull ItemStack createExecuteItem(@NotNull Player player, @NotNull SelectionResult selection) {
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

    private boolean returnSelectedEquipment(@NotNull AstPlayer astPlayer, @NotNull RepairSession session) {
        if (session.selectedEquipment == null || session.selectedEquipment.getType() == Material.AIR) {
            return false;
        }
        if (inventoryService.returnItemToOwnedInventory(astPlayer, session.selectedEquipment.clone()) == null) {
            astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedEquipment.clone());
        }
        session.selectedEquipment = null;
        return true;
    }

    private void releaseSession(@NotNull AstPlayer astPlayer, boolean restoreDisplayedInventory) {
        RepairSession session = sessions.remove(astPlayer.getBukkit().getUniqueId());
        if (session == null) {
            return;
        }
        if (session.selectedEquipment != null && session.selectedEquipment.getType() != Material.AIR) {
            if (inventoryService.returnItemToOwnedInventory(astPlayer, session.selectedEquipment.clone()) == null) {
                astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedEquipment.clone());
            }
            session.selectedEquipment = null;
        }
        if (restoreDisplayedInventory && session.previousDisplayedType != null) {
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
        private final InventoryType previousDisplayedType;
        private ItemStack selectedEquipment;

        private RepairSession(@Nullable InventoryType previousDisplayedType) {
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

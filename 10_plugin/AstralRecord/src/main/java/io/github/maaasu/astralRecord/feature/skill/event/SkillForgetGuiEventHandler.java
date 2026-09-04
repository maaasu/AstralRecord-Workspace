package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillForgetGui;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndEvent;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** NPC 専用スキル忘却 GUI の開閉と操作を処理します。 */
public final class SkillForgetGuiEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final SkillForgetGui gui;
    private final SkillService skillService;
    private final SkillOwnershipService ownershipService;
    private final LearnedSkillService learnedSkillService;
    private final PassiveSkillService passiveSkillService;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ShopService shopService;
    private final Map<UUID, UUID> forgetting = new ConcurrentHashMap<>();

    public SkillForgetGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull SkillForgetGui gui,
        @NotNull SkillService skillService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull PassiveSkillService passiveSkillService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull ShopService shopService
    ) {
        this.plugin = plugin;
        this.gui = gui;
        this.skillService = skillService;
        this.ownershipService = ownershipService;
        this.learnedSkillService = learnedSkillService;
        this.passiveSkillService = passiveSkillService;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.shopService = shopService;
    }

    /**
     * NPC からスキル忘却一覧を開きます。
     *
     * @param player 表示対象プレイヤー
     * @return 画面を開いた場合は {@code true}
     */
    public boolean open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !learnedSkillService.hasLoadedSkills(astPlayer.getAccount().getUuid())) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5848);
            return false;
        }
        openList(player, 0);
        GuiSound.OPEN.play(player);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            SkillForgetInventoryHolder holder = gui.holder(event.getView().getTopInventory());
            if (holder == null) return;
            event.setCancelled(true);
            if (forgetting.containsKey(player.getUniqueId())) {
                GuiSound.DENY.play(player);
                return;
            }
            if (holder.screen() == SkillForgetScreen.CONFIRM) {
                handleConfirmClick(player, holder, event.getRawSlot());
                return;
            }
            handleListClick(player, holder, event.getRawSlot(), event.getCurrentItem());
        }, LogId.E_5601, event.getWhoClicked().getName(), "skill_forget_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (gui.isInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) GuiSound.DENY.play(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuiSessionEnd(GuiSessionEndEvent event) {
        Player player = event.getPlayer();
        if (gui.holder(event.getInventory()) == null) return;
        if (event.getReason().isCloseSoundEnabled() && !forgetting.containsKey(player.getUniqueId())) {
            GuiSound.CLOSE.play(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        forgetting.remove(playerId);
    }

    private void handleListClick(
        @NotNull Player player,
        @NotNull SkillForgetInventoryHolder holder,
        int slot,
        org.bukkit.inventory.ItemStack currentItem
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        List<SkillManagerEntry> entries = entries(astPlayer);
        if (slot == SkillForgetGui.PREVIOUS_PAGE_SLOT || slot == SkillForgetGui.NEXT_PAGE_SLOT) {
            int pages = gui.totalPages(entries.size());
            int nextPage = slot == SkillForgetGui.PREVIOUS_PAGE_SLOT
                ? holder.pageIndex() - 1
                : holder.pageIndex() + 1;
            if (nextPage < 0 || nextPage >= pages) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.PAGE.play(player);
            openList(player, nextPage);
            return;
        }
        if (slot < 0 || slot >= SkillForgetGui.CONTENT_SLOT_COUNT) {
            GuiSound.DENY.play(player);
            return;
        }
        String learnedSkillId = gui.learnedSkillId(currentItem);
        SkillManagerEntry entry = entry(astPlayer, learnedSkillId);
        if (entry == null) {
            GuiSound.DENY.play(player);
            openList(player, holder.pageIndex());
            return;
        }
        gui.openConfirm(player, entry, holder.pageIndex());
        GuiSound.CONFIRM.play(player);
    }

    private void handleConfirmClick(
        @NotNull Player player,
        @NotNull SkillForgetInventoryHolder holder,
        int slot
    ) {
        if (slot == ConfirmDialogView.CANCEL_SLOT) {
            openList(player, holder.pageIndex());
            GuiSound.SELECT.play(player);
            return;
        }
        boolean paidForget = slot == SkillForgetGui.PAID_CONFIRM_SLOT;
        if (slot != ConfirmDialogView.CONFIRM_SLOT && !paidForget) {
            GuiSound.DENY.play(player);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        SkillManagerEntry entry = entry(astPlayer, holder.learnedSkillId());
        if (entry == null) {
            GuiSound.DENY.play(player);
            openList(player, holder.pageIndex());
            return;
        }
        UUID playerId = player.getUniqueId();
        UUID learnedSkillId;
        try {
            learnedSkillId = UUID.fromString(holder.learnedSkillId());
        } catch (IllegalArgumentException exception) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        forgetting.put(playerId, learnedSkillId);
        GuiSound.CONFIRM.play(player);
        PurchaseMaterial compensationMaterial = paidForget
            ? findPurchaseMaterial(entry.definition())
            : null;
        if (paidForget && compensationMaterial == null) {
            rejectPaidForget(player, playerId, PlayerMsgId.P_5870);
            return;
        }
        if (paidForget && !inventoryService.canAddItemToNormalInventory(
            astPlayer, compensationMaterial.item(), compensationMaterial.amount()
        )) {
            rejectPaidForget(player, playerId, PlayerMsgId.P_5870);
            return;
        }
        if (paidForget && !inventoryService.consumeCurrency(
            accountId, ItemService.ASTRALD_CURRENCY_ITEM_ID, 100
        )) {
            rejectPaidForget(player, playerId, PlayerMsgId.P_5868);
            return;
        }
        if (paidForget) {
            inventoryService.saveNow(accountId).whenComplete((saved, saveError) ->
                runOnMainThread(() -> {
                    if (saveError != null || !Boolean.TRUE.equals(saved)) {
                        refundPaidForget(player, accountId);
                        failForget(player, playerId, PlayerMsgId.P_5867);
                        return;
                    }
                    requestForget(
                        player, playerId, accountId, learnedSkillId, entry, holder.pageIndex(),
                        compensationMaterial
                    );
                })
            );
            return;
        }
        requestForget(player, playerId, accountId, learnedSkillId, entry, holder.pageIndex(), null);
    }

    private void requestForget(
        @NotNull Player player,
        @NotNull UUID playerId,
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull SkillManagerEntry entry,
        int returnPage,
        @Nullable PurchaseMaterial compensationMaterial
    ) {
        boolean accepted = learnedSkillService.forgetAsync(
            accountId,
            learnedSkillId,
            accountId,
            ignored -> {
                forgetting.remove(playerId);
                AstPlayer current = AstPlayerCache.get(player);
                if (current != null) {
                    passiveSkillService.reconcileNow(current);
                    if (compensationMaterial != null) {
                        inventoryService.addItemToNormalInventory(
                            current,
                            compensationMaterial.item(),
                            compensationMaterial.amount(),
                            "skill_forgetting_compensation"
                        );
                        inventoryService.saveNow(accountId);
                    }
                }
                openList(player, returnPage);
                GuiSound.SUCCESS.play(player);
                PlayerMessageService.getInstance().send(
                    player,
                    compensationMaterial == null ? PlayerMsgId.P_5866 : PlayerMsgId.P_5869,
                    SkillPresentationUtil.plainName(entry.definition(), entry.definition().getId())
                );
            },
            error -> {
                forgetting.remove(playerId);
                if (compensationMaterial != null) refundPaidForget(player, accountId);
                failForget(player, playerId, PlayerMsgId.P_5867);
            },
            () -> {
                if (!forgetting.containsKey(playerId) || !player.isOnline()) return;
                player.closeInventory();
                GuiSound.DENY.play(player);
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5874);
            }
        );
        if (!accepted) {
            if (compensationMaterial != null) refundPaidForget(player, accountId);
            failForget(player, playerId, PlayerMsgId.P_5867);
        }
    }

    private void rejectPaidForget(
        @NotNull Player player,
        @NotNull UUID playerId,
        @NotNull PlayerMsgId messageId
    ) {
        forgetting.remove(playerId);
        GuiSound.DENY.play(player);
        PlayerMessageService.getInstance().send(player, messageId);
    }

    private void failForget(
        @NotNull Player player,
        @NotNull UUID playerId,
        @NotNull PlayerMsgId messageId
    ) {
        forgetting.remove(playerId);
        GuiSound.DENY.play(player);
        PlayerMessageService.getInstance().send(player, messageId);
    }

    private void refundPaidForget(@NotNull Player player, @NotNull UUID accountId) {
        AstPlayer current = AstPlayerCache.get(player);
        ItemModel astrald = itemService.findLoadedById(ItemService.ASTRALD_CURRENCY_ITEM_ID);
        if (current != null && astrald != null) {
            inventoryService.addItemToNormalInventory(current, astrald, 100, "skill_forgetting_refund");
            inventoryService.saveNow(accountId);
        }
    }

    private @Nullable PurchaseMaterial findPurchaseMaterial(@NotNull SkillDefinition skill) {
        List<io.github.maaasu.astralRecord.feature.skill.model.SkillRequiredItemDefinition> costs = skill.getLearnRequiredItems();
        if (costs.size() != 1 || costs.get(0).getAmount() <= 0) return null;
        var cost = costs.get(0);
        ItemModel material = itemService.findLoadedById(cost.getItemId());
        return material == null ? null : new PurchaseMaterial(material, cost.getAmount());
    }

    private void runOnMainThread(@NotNull Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        AsyncTaskUtil.runSyncEventually(plugin, action);
    }

    private void openList(@NotNull Player player, int page) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        gui.open(player, entries(astPlayer), page);
    }

    private @NotNull List<SkillManagerEntry> entries(@NotNull AstPlayer player) {
        return ownershipService.learnedSkills(player).stream()
            .map(learned -> entry(player, learned.getLearnedSkillId().toString()))
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator
                .comparing((SkillManagerEntry entry) -> entry.definition().getId())
                .thenComparing(entry -> entry.learnedSkill().getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(entry -> entry.learnedSkill().getLearnedSkillId()))
            .toList();
    }

    private @Nullable SkillManagerEntry entry(@NotNull AstPlayer player, String learnedSkillId) {
        LearnedSkillInstance learned = ownershipService.findInstance(player, learnedSkillId);
        if (learned == null) return null;
        var definition = skillService.registry().getDefinition(learned.getSkillId());
        return definition == null ? null : new SkillManagerEntry(learned, definition, true);
    }

    private record PurchaseMaterial(@NotNull ItemModel item, int amount) {
    }
}

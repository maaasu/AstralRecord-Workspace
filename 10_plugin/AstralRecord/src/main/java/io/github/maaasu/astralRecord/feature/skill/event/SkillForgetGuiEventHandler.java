package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillForgetGui;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
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
    private final SkillForgetGui gui;
    private final SkillService skillService;
    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;
    private final LearnedSkillService learnedSkillService;
    private final PassiveSkillService passiveSkillService;
    private final Map<UUID, UUID> forgetting = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> suppressClose = ConcurrentHashMap.newKeySet();

    public SkillForgetGuiEventHandler(
        @NotNull SkillForgetGui gui,
        @NotNull SkillService skillService,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull PassiveSkillService passiveSkillService
    ) {
        this.gui = gui;
        this.skillService = skillService;
        this.presetService = presetService;
        this.ownershipService = ownershipService;
        this.learnedSkillService = learnedSkillService;
        this.passiveSkillService = passiveSkillService;
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
        openList(player, 0, false);
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
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (gui.holder(event.getInventory()) == null) return;
        boolean suppressed = suppressClose.remove(player.getUniqueId());
        if (!suppressed && !forgetting.containsKey(player.getUniqueId())) {
            GuiSound.CLOSE.play(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        forgetting.remove(playerId);
        suppressClose.remove(playerId);
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
            openList(player, nextPage, true);
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
            openList(player, holder.pageIndex(), true);
            return;
        }
        suppressClose.add(player.getUniqueId());
        gui.openConfirm(player, entry, holder.pageIndex());
        GuiSound.CONFIRM.play(player);
    }

    private void handleConfirmClick(
        @NotNull Player player,
        @NotNull SkillForgetInventoryHolder holder,
        int slot
    ) {
        if (slot == ConfirmDialogView.CANCEL_SLOT) {
            suppressClose.add(player.getUniqueId());
            openList(player, holder.pageIndex(), true);
            GuiSound.SELECT.play(player);
            return;
        }
        if (slot != ConfirmDialogView.CONFIRM_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        SkillManagerEntry entry = entry(astPlayer, holder.learnedSkillId());
        if (entry == null) {
            GuiSound.DENY.play(player);
            openList(player, holder.pageIndex(), true);
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
        boolean accepted = learnedSkillService.forgetAsync(
            accountId,
            learnedSkillId,
            accountId,
            ignored -> {
                forgetting.remove(playerId);
                presetService.clearBindings(accountId, learnedSkillId);
                AstPlayer current = AstPlayerCache.get(player);
                if (current != null) passiveSkillService.reconcileNow(current);
                suppressClose.add(playerId);
                openList(player, holder.pageIndex(), true);
                GuiSound.SUCCESS.play(player);
                PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5866,
                    SkillPresentationUtil.plainName(entry.definition(), entry.definition().getId())
                );
            },
            error -> {
                forgetting.remove(playerId);
                GuiSound.DENY.play(player);
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5867);
            }
        );
        if (!accepted) {
            forgetting.remove(playerId);
            GuiSound.DENY.play(player);
        }
    }

    private void openList(@NotNull Player player, int page, boolean suppressPreviousClose) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        if (suppressPreviousClose) suppressClose.add(player.getUniqueId());
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
}

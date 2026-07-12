package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * スキルバインド GUI の操作を処理します。
 */
public final class SkillBindGuiEventHandler extends AbstractEventHandler {
    private static final String ACTION_BACK = "back";
    private static final String ACTION_CLOSE = "close";
    private static final String ACTION_SWITCH_PRESET = "switch_preset";

    private final AstralRecord plugin;
    private final SkillBindGui gui;
    private final SkillService skillService;
    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;
    private final PassiveSkillService passiveSkillService;
    private final InventoryService inventoryService;
    private final MenuView menuView;
    private final Map<UUID, SkillBindSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> suppressClose = ConcurrentHashMap.newKeySet();

    public SkillBindGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull SkillBindGui gui,
        @NotNull SkillService skillService,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull PassiveSkillService passiveSkillService,
        @NotNull InventoryService inventoryService,
        @NotNull MenuView menuView
    ) {
        this.plugin = plugin;
        this.gui = gui;
        this.skillService = skillService;
        this.presetService = presetService;
        this.ownershipService = ownershipService;
        this.passiveSkillService = passiveSkillService;
        this.inventoryService = inventoryService;
        this.menuView = menuView;
    }

    /**
     * 指定のプレイヤーへスキルバインド GUI を開きます。
     */
    public void open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int initialPresetIndex = presetService.selectedPresetIndex(astPlayer.getAccount().getUuid());
        SkillBindSession session = new SkillBindSession(presetService.getPresets(astPlayer.getAccount().getUuid()), initialPresetIndex);
        if (!session.selectedPreset().isUnlocked()) {
            session.loadPreset(1);
        }
        presetService.selectPreset(astPlayer.getAccount().getUuid(), session.selectedPresetIndex());
        sessions.put(player.getUniqueId(), session);
        openMain(player, session, 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            Inventory topInventory = event.getView().getTopInventory();
            SkillBindInventoryHolder holder = gui.holder(topInventory);
            if (holder == null) {
                return;
            }
            if (event.getClickedInventory() instanceof PlayerInventory) {
                if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                    return;
                }
                event.setCancelled(true);
                GuiSound.DENY.play(player);
                return;
            }
            event.setCancelled(true);
            if (holder.screen() == SkillBindScreen.CONFIRM) {
                handleConfirmClick(player, holder, event.getRawSlot());
                return;
            }
            SkillBindSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                GuiSound.DENY.play(player);
                return;
            }
            handleTopClick(player, session, event.getRawSlot(), holder.pageIndex(), event.getCurrentItem());
        }, LogId.E_5802, event.getWhoClicked().getName(), "skill_bind_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (gui.isInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    GuiSound.DENY.play(player);
                }
            }
        }, LogId.E_5802, event.getWhoClicked().getName(), "skill_bind_drag");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        runSafely(() -> {
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            SkillBindInventoryHolder holder = gui.holder(event.getInventory());
            if (holder == null) {
                return;
            }
            UUID playerId = player.getUniqueId();
            if (suppressClose.remove(playerId)) {
                return;
            }
            SkillBindSession session = sessions.get(playerId);
            if (holder.screen() == SkillBindScreen.MAIN && session != null && session.isDirty()) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    openConfirm(player, session, ACTION_CLOSE, -1, Component.text("変更を破棄して閉じますか", NamedTextColor.YELLOW))
                );
                return;
            }
            sessions.remove(playerId);
            restorePlayerInventory(player);
        }, LogId.E_5802, event.getPlayer().getName(), "skill_bind_close");
    }

    private void handleTopClick(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        int rawSlot,
        int pageIndex,
        org.bukkit.inventory.ItemStack currentItem
    ) {
        if (rawSlot == SkillBindGui.SAVE_SLOT) {
            saveCurrentPreset(player, session, pageIndex);
            return;
        }
        if (rawSlot == SkillBindGui.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            if (session.isDirty()) {
                openConfirm(player, session, ACTION_BACK, -1, Component.text("スキル設定を閉じて戻りますか", NamedTextColor.YELLOW));
            } else {
                sessions.remove(player.getUniqueId());
                restorePlayerInventory(player);
                suppressClose.add(player.getUniqueId());
                menuView.open(player);
            }
            return;
        }
        if (rawSlot == SkillBindGui.PREVIOUS_SLOT) {
            if (gui.hasPreviousPage(pageIndex)) {
                GuiSound.SELECT.play(player);
                openMain(player, session, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == SkillBindGui.NEXT_SLOT) {
            List<SkillDefinition> skills = currentSkills(player);
            if (gui.hasNextPage(pageIndex, skills.size())) {
                GuiSound.SELECT.play(player);
                openMain(player, session, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        int presetIndex = SkillBindGui.presetIndexAtSlot(rawSlot);
        if (presetIndex > 0) {
            handlePresetClick(player, session, presetIndex, pageIndex);
            return;
        }
        if (rawSlot >= SkillBindGui.ACTIVE_BIND_SLOT_START
            && rawSlot < SkillBindGui.ACTIVE_BIND_SLOT_START + SkillBindPreset.SLOT_COUNT) {
            handleBindSlotClick(
                player,
                session,
                SkillBindType.ACTIVE,
                rawSlot - SkillBindGui.ACTIVE_BIND_SLOT_START,
                pageIndex
            );
            return;
        }

        String skillId = gui.skillId(currentItem);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (skillId == null || astPlayer == null || !ownershipService.owns(astPlayer, skillId)) {
            GuiSound.DENY.play(player);
            return;
        }
        SkillDefinition definition = skillService.registry().getDefinition(skillId);
        if (definition == null
            || definition.getKind().isPassive()
            || !session.assignSelectedOrNextSlot(skillId, definition.getKind())) {
            GuiSound.DENY.play(player);
            openMain(player, session, pageIndex);
            return;
        }
        GuiSound.SELECT.play(player);
        openMain(player, session, pageIndex);
    }

    private void handlePresetClick(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        int presetIndex,
        int pageIndex
    ) {
        if (!session.presets().get(presetIndex - 1).isUnlocked()) {
            GuiSound.DENY.play(player);
            return;
        }
        if (presetIndex == session.selectedPresetIndex()) {
            GuiSound.SELECT.play(player);
            return;
        }
        if (session.isDirty()) {
            openConfirm(
                player,
                session,
                ACTION_SWITCH_PRESET,
                presetIndex,
                Component.text("変更を破棄して切り替えますか", NamedTextColor.YELLOW)
            );
            return;
        }
        session.loadPreset(presetIndex);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            presetService.selectPreset(astPlayer.getAccount().getUuid(), presetIndex);
            passiveSkillService.reconcileNow(astPlayer);
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5808, presetIndex);
        }
        GuiSound.SELECT.play(player);
        openMain(player, session, pageIndex);
    }

    private void handleBindSlotClick(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        @NotNull SkillBindType type,
        int slotIndex,
        int pageIndex
    ) {
        String currentSkillId = session.skillIdAt(type, slotIndex);
        if (currentSkillId != null && !currentSkillId.isBlank()) {
            session.setSlot(type, slotIndex, null);
            session.clearSelectedBindSlot();
        } else if (session.isSelectedBindSlot(type, slotIndex)) {
            session.clearSelectedBindSlot();
        } else {
            session.selectBindSlot(type, slotIndex);
        }
        GuiSound.SELECT.play(player);
        openMain(player, session, pageIndex);
    }

    private void handleConfirmClick(@NotNull Player player, @NotNull SkillBindInventoryHolder holder, int rawSlot) {
        SkillBindSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            restoreAndClose(player);
            return;
        }
        if (rawSlot == ConfirmDialogView.CANCEL_SLOT) {
            GuiSound.SELECT.play(player);
            openMain(player, session, holder.pageIndex());
            return;
        }
        if (rawSlot != ConfirmDialogView.CONFIRM_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.SELECT.play(player);
        switch (holder.action()) {
            case ACTION_SWITCH_PRESET -> {
                session.loadPreset(holder.pendingPresetIndex());
                AstPlayer astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    presetService.selectPreset(astPlayer.getAccount().getUuid(), holder.pendingPresetIndex());
                    passiveSkillService.reconcileNow(astPlayer);
                    PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5808, holder.pendingPresetIndex());
                }
                openMain(player, session, 0);
            }
            case ACTION_BACK -> {
                sessions.remove(player.getUniqueId());
                restorePlayerInventory(player);
                suppressClose.add(player.getUniqueId());
                menuView.open(player);
            }
            case ACTION_CLOSE -> restoreAndClose(player);
            default -> openMain(player, session, 0);
        }
    }

    private void saveCurrentPreset(@NotNull Player player, @NotNull SkillBindSession session, int pageIndex) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !session.selectedPreset().isUnlocked()) {
            GuiSound.DENY.play(player);
            return;
        }
        try {
            var saved = presetService.save(
                astPlayer.getAccount().getUuid(),
                session.selectedPresetIndex(),
                session.activeDraft(),
                session.passiveDraft(),
                astPlayer.getAccount().getUuid()
            );
            session.replaceSelectedPreset(saved);
            passiveSkillService.reconcileNow(astPlayer);
            GuiSound.SELECT.play(player);
            openMain(player, session, pageIndex);
        } catch (RuntimeException e) {
            GuiSound.DENY.play(player);
        }
    }

    private void openMain(@NotNull Player player, @NotNull SkillBindSession session, int pageIndex) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        Set<String> ownedSkillIds = astPlayer == null ? Set.of() : ownershipService.ownedSkillIds(astPlayer);
        suppressCloseIfSwitchingWithinSkillGui(player);
        gui.open(player, session, currentSkills(ownedSkillIds), allSkillMap(), ownedSkillIds, pageIndex);
    }

    private void openConfirm(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        @NotNull String action,
        int pendingPresetIndex,
        @NotNull Component message
    ) {
        suppressCloseIfSwitchingWithinSkillGui(player);
        gui.openConfirm(player, session.selectedPresetIndex(), action, pendingPresetIndex, message);
    }

    private void suppressCloseIfSwitchingWithinSkillGui(@NotNull Player player) {
        if (gui.isInventory(player.getOpenInventory().getTopInventory())) {
            suppressClose.add(player.getUniqueId());
        }
    }

    private @NotNull List<SkillDefinition> currentSkills(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        Set<String> ownedSkillIds = astPlayer == null ? Set.of() : ownershipService.ownedSkillIds(astPlayer);
        return currentSkills(ownedSkillIds);
    }

    private @NotNull List<SkillDefinition> currentSkills(@NotNull Set<String> ownedSkillIds) {
        return gui.sortedSkills(skillService.registry().definitions()).stream()
            .filter(skill -> ownedSkillIds.contains(skill.getId()))
            .filter(skill -> skill.getKind() != SkillKind.PASSIVE || skill.getPassiveBindRequired())
            .toList();
    }

    private @NotNull Map<String, SkillDefinition> allSkillMap() {
        return skillService.registry().definitions().stream()
            .collect(java.util.stream.Collectors.toMap(
                SkillDefinition::getId,
                skill -> skill,
                (current, replacement) -> current
            ));
    }

    private void restoreAndClose(@NotNull Player player) {
        sessions.remove(player.getUniqueId());
        restorePlayerInventory(player);
        suppressClose.add(player.getUniqueId());
        player.closeInventory();
    }

    private void restorePlayerInventory(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.applyInventoriesToGui(astPlayer);
            player.updateInventory();
        }
    }
}

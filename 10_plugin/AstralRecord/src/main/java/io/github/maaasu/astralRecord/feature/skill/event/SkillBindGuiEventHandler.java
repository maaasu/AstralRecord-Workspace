package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
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
 * 繧ｹ繧ｭ繝ｫ繝舌う繝ｳ繝・GUI 縺ｮ謫堺ｽ懊ｒ蜃ｦ逅・＠縺ｾ縺吶・
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
    private final InventoryService inventoryService;
    private final MenuView menuView;
    private final Map<UUID, SkillBindSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedPresetIndexes = new ConcurrentHashMap<>();
    private final Set<UUID> suppressClose = ConcurrentHashMap.newKeySet();

    public SkillBindGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull SkillBindGui gui,
        @NotNull SkillService skillService,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull InventoryService inventoryService,
        @NotNull MenuView menuView
    ) {
        this.plugin = plugin;
        this.gui = gui;
        this.skillService = skillService;
        this.presetService = presetService;
        this.ownershipService = ownershipService;
        this.inventoryService = inventoryService;
        this.menuView = menuView;
    }

    /**
     * 謖・ｮ壹・繝ｬ繧､繝､繝ｼ縺ｸ繧ｹ繧ｭ繝ｫ繝舌う繝ｳ繝・GUI 繧帝幕縺阪∪縺吶・
     */
    public void open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int initialPresetIndex = selectedPresetIndexes.getOrDefault(player.getUniqueId(), 1);
        SkillBindSession session = new SkillBindSession(presetService.getPresets(astPlayer.getAccount().getUuid()), initialPresetIndex);
        if (!session.selectedPreset().isUnlocked()) {
            session.loadPreset(1);
        }
        selectedPresetIndexes.put(player.getUniqueId(), session.selectedPresetIndex());
        sessions.put(player.getUniqueId(), session);
        openMain(player, session, 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
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
            if (event.getClickedInventory() instanceof PlayerInventory) {
                handlePlayerInventoryClick(player, session, event.getSlot(), holder.pageIndex());
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
                    openConfirm(player, session, ACTION_CLOSE, -1, Component.text("螟画峩繧堤ｴ譽・＠縺ｦ髢峨§縺ｾ縺吶°", NamedTextColor.YELLOW))
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
        if (rawSlot == SkillBindGui.BACK_SLOT) {
            if (session.isDirty()) {
                openConfirm(player, session, ACTION_BACK, -1, Component.text("繧ｹ繧ｭ繝ｫ險ｭ螳壹ｒ髢峨§縺ｦ謌ｻ繧翫∪縺吶°", NamedTextColor.YELLOW));
            } else {
                sessions.remove(player.getUniqueId());
                restorePlayerInventory(player);
                suppressClose.add(player.getUniqueId());
                menuView.open(player);
            }
            return;
        }
        if (rawSlot == SkillBindGui.PREVIOUS_SLOT) {
            List<SkillDefinition> skills = currentSkills();
            if (gui.hasPreviousPage(pageIndex)) {
                GuiSound.SELECT.play(player);
                openMain(player, session, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == SkillBindGui.NEXT_SLOT) {
            List<SkillDefinition> skills = currentSkills();
            if (gui.hasNextPage(pageIndex, skills.size())) {
                GuiSound.SELECT.play(player);
                openMain(player, session, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        String skillId = gui.skillId(currentItem);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (skillId == null || astPlayer == null || !ownershipService.owns(astPlayer, skillId)) {
            GuiSound.DENY.play(player);
            return;
        }
        session.selectedSkillId(skillId);
        GuiSound.SELECT.play(player);
        openMain(player, session, pageIndex);
    }

    private void handlePlayerInventoryClick(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        int slot,
        int pageIndex
    ) {
        if (slot == SkillBindGui.SAVE_SLOT) {
            saveCurrentPreset(player, session, pageIndex);
            return;
        }
        if (slot >= SkillBindGui.PRESET_SLOT_START && slot <= SkillBindGui.PRESET_SLOT_END) {
            int presetIndex = slot - SkillBindGui.PRESET_SLOT_START + 1;
            if (!session.presets().get(presetIndex - 1).isUnlocked()) {
                GuiSound.DENY.play(player);
                return;
            }
            if (presetIndex == session.selectedPresetIndex()) {
                GuiSound.SELECT.play(player);
                return;
            }
            if (session.isDirty()) {
                openConfirm(player, session, ACTION_SWITCH_PRESET, presetIndex, Component.text("螟画峩繧堤ｴ譽・＠縺ｦ蛻・ｊ譖ｿ縺医∪縺吶°", NamedTextColor.YELLOW));
                return;
            }
            session.loadPreset(presetIndex);
            selectedPresetIndexes.put(player.getUniqueId(), presetIndex);
            GuiSound.SELECT.play(player);
            openMain(player, session, 0);
            return;
        }
        if (slot >= SkillBindGui.ACTIVE_BIND_SLOT_START && slot < SkillBindGui.ACTIVE_BIND_SLOT_START + SkillBindPreset.SLOT_COUNT) {
            bindSlot(player, session, SkillBindType.ACTIVE, slot - SkillBindGui.ACTIVE_BIND_SLOT_START, pageIndex);
            return;
        }
        if (slot == SkillBindGui.ACTIVE_CLEAR_SLOT) {
            session.clear(SkillBindType.ACTIVE);
            GuiSound.SELECT.play(player);
            openMain(player, session, pageIndex);
            return;
        }
        if (slot >= SkillBindGui.PASSIVE_BIND_SLOT_START && slot < SkillBindGui.PASSIVE_BIND_SLOT_START + SkillBindPreset.SLOT_COUNT) {
            bindSlot(player, session, SkillBindType.PASSIVE, slot - SkillBindGui.PASSIVE_BIND_SLOT_START, pageIndex);
            return;
        }
        if (slot == SkillBindGui.PASSIVE_CLEAR_SLOT) {
            session.clear(SkillBindType.PASSIVE);
            GuiSound.SELECT.play(player);
            openMain(player, session, pageIndex);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void bindSlot(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        @NotNull SkillBindType type,
        int slotIndex,
        int pageIndex
    ) {
        session.setSlot(type, slotIndex, session.selectedSkillId());
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
                selectedPresetIndexes.put(player.getUniqueId(), holder.pendingPresetIndex());
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
        gui.open(player, session, currentSkills(), ownedSkillIds, pageIndex);
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

    private @NotNull List<SkillDefinition> currentSkills() {
        return gui.sortedSkills(skillService.registry().definitions());
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

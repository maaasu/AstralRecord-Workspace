package io.github.maaasu.astralRecord.feature.trainingdummy.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.trainingdummy.gui.TrainingDummyGui;
import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import io.github.maaasu.astralRecord.feature.trainingdummy.service.TrainingDummyService;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

/** カカシ設定 GUI のクリックを処理します。 */
public final class TrainingDummyGuiEventHandler extends AbstractEventHandler {
    private final TrainingDummyGui gui;
    private final TrainingDummyService service;
    public TrainingDummyGuiEventHandler(@NotNull TrainingDummyGui gui, @NotNull TrainingDummyService service) { this.gui = gui; this.service = service; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!gui.isInventory(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= TrainingDummyGui.SIZE) return;
        String id = gui.dummyId(event.getView().getTopInventory());
        TrainingDummyDefinition definition = id == null ? null : service.find(id);
        if (definition == null) { GuiSound.DENY.play(player); player.closeInventory(); return; }
        TrainingDummyDefinition next = switch (event.getRawSlot()) {
            case TrainingDummyGui.DEFENSE_MINUS -> definition.withStats(definition.maxHealth(), definition.defense() - 1.0D, definition.magicDefense(), definition.shieldEnabled(), definition.shieldMax());
            case TrainingDummyGui.DEFENSE_PLUS -> definition.withStats(definition.maxHealth(), definition.defense() + 1.0D, definition.magicDefense(), definition.shieldEnabled(), definition.shieldMax());
            case TrainingDummyGui.MAGIC_DEFENSE_MINUS -> definition.withStats(definition.maxHealth(), definition.defense(), definition.magicDefense() - 1.0D, definition.shieldEnabled(), definition.shieldMax());
            case TrainingDummyGui.MAGIC_DEFENSE_PLUS -> definition.withStats(definition.maxHealth(), definition.defense(), definition.magicDefense() + 1.0D, definition.shieldEnabled(), definition.shieldMax());
            case TrainingDummyGui.SHIELD_TOGGLE -> definition.withStats(definition.maxHealth(), definition.defense(), definition.magicDefense(), !definition.shieldEnabled(), definition.shieldMax());
            case TrainingDummyGui.SHIELD_MAX_PLUS -> definition.withStats(definition.maxHealth(), definition.defense(), definition.magicDefense(), definition.shieldEnabled(), definition.shieldMax() + (event.isShiftClick() ? -10.0D : 10.0D));
            case TrainingDummyGui.CLOSE -> { GuiSound.CLOSE.play(player); player.closeInventory(); yield null; }
            default -> null;
        };
        if (event.getRawSlot() == TrainingDummyGui.CLOSE) {
            return;
        }
        if (next == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (service.update(next)) {
            gui.render(event.getView().getTopInventory(), next);
            if (event.getRawSlot() == TrainingDummyGui.SHIELD_TOGGLE) {
                GuiSound.TOGGLE.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
        } else {
            GuiSound.DENY.play(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) { if (gui.isInventory(event.getView().getTopInventory())) event.setCancelled(true); }
}

package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTask;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import org.jetbrains.annotations.NotNull;

public class InventorySaveTask implements PlayerSaveTask {

    private final InventoryService inventoryService;

    public InventorySaveTask(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public @NotNull String getTaskName() {
        return "inventory";
    }

    @Override
    public void save(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger) {
        if (!player.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        inventoryService.saveEquipSlotSnapshot(player);
        inventoryService.saveHotbarSnapshot(player);
        inventoryService.saveAccessorySlotSnapshot(player);
        inventoryService.syncCurrentEquipmentState(player);
    }
}

package io.github.maaasu.astralRecord.feature.inventory.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

public class InventoryCommand extends AstCommand {

    public InventoryCommand() {
        super("inventory", "Refresh inventory view.", "/inventory", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!player.getAccount().getMode().shouldReflectInventoryToGui()) {
            sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5250.getId()));
            return;
        }

        var inventoryService = AstralRecord.getInstance().getInventoryService();
        if (inventoryService == null) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5252.getId()));
            return;
        }

        inventoryService.applyInventoriesToGui(player);
    }
}

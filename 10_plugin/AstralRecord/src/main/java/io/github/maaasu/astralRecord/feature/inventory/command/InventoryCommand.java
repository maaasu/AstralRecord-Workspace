package io.github.maaasu.astralRecord.feature.inventory.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

public class InventoryCommand extends AstCommand {

    public InventoryCommand() {
        super("inventory", "Switch inventory view.", "/inventory <type>", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 1) {
            sendUsage(player.getBukkit());
            return;
        }

        if (!player.getAccount().getMode().shouldReflectInventoryToGui()) {
            sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5250.getId()));
            return;
        }

        InventoryType inventoryType = parseInventoryType(args[0]);
        if (inventoryType == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5251.getId(), supportedInventoryTypes()));
            return;
        }

        var inventoryService = AstralRecord.getInstance().getInventoryService();
        if (inventoryService == null) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5252.getId()));
            return;
        }

        inventoryService.applyInventoryToGui(player, inventoryType);
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5253.getId(), inventoryType.getDisplayNameJa()));
    }

    private InventoryType parseInventoryType(@NotNull String value) {
        return InventoryType.fromCommandInput(value);
    }

    private String supportedInventoryTypes() {
        return InventoryType.commandSwitchableEntries().stream()
            .map(type -> type.toString().toLowerCase())
            .collect(Collectors.joining(", "));
    }
}

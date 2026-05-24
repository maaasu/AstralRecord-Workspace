package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /item command.
 */
public class ItemCommand extends AstCommand {
    private final ItemService itemService;

    public ItemCommand(@NotNull ItemService itemService) {
        super("item", "Load and get items.", "/item <load|get> <itemId> [amount]", true);
        this.itemService = itemService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        var action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("load")) {
            handleLoad(player, args);
            return;
        }

        if (action.equals("get")) {
            handleGet(player, args);
            return;
        }

        sendUsage(player.getBukkit());
    }

    private void handleLoad(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }
        var loaded = itemService.loadItem(args[1]);
        if (loaded == null) {
            player.sendMessage(PlayerMsgId.P_5201, args[1]);
            return;
        }

        player.sendMessage(PlayerMsgId.P_5209, loaded.getCategory(), loaded.getId(), loaded.getName());
    }

    private void handleGet(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        var itemId = args[1];
        var model = itemService.findLoadedById(itemId);
        if (model == null) {
            player.sendMessage(PlayerMsgId.P_5213, itemId);
            return;
        }

        var plugin = AstralRecord.getInstance();
        var inventoryService = plugin.getInventoryService();
        if (inventoryService == null) {
            player.sendMessage(PlayerMsgId.P_5063);
            return;
        }

        var amount = parsePositiveInt(args, 2, 1);
        var granted = inventoryService.addItemToNormalInventory(player, model, amount);

        if (granted <= 0) {
            player.sendMessage(PlayerMsgId.P_5241);
            return;
        }

        InventoryType inventoryType = inventoryService.resolveInventoryType(model);
        if (inventoryType != InventoryType.CURRENCY) {
            inventoryService.applyInventoryToGui(player, inventoryType);
        }
        player.sendMessage(PlayerMsgId.P_5240, model.getName(), granted);
    }

    private int parsePositiveInt(@NotNull String[] args, int index, int defaultValue) {
        if (args.length <= index) {
            return defaultValue;
        }

        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}

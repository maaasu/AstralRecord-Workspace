package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /item のタブ補完クラス。
 */
public class ItemTabCompleter extends AstTabCompleter {

    private final ItemService itemService;

    public ItemTabCompleter(@NotNull ItemService itemService) {
        super(true);
        this.itemService = itemService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        List<String> subCommands = completeAtPosition(args, 0, "load", "get");
        if (!subCommands.isEmpty()) {
            return subCommands;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("load") || args[0].equalsIgnoreCase("get"))) {
            return itemService.getLoadedItemIds();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("get")) {
            return List.of("1", "8", "16", "32", "64");
        }

        return List.of();
    }
}

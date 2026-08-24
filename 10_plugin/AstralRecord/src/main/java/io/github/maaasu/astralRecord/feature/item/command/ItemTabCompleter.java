package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /item のタブ補完クラス。
 */
public class ItemTabCompleter extends AstTabCompleter {

    private final ItemService itemService;

    /**
     * ItemTabCompleter を初期化します。
     *
     * @param itemService ロード済みアイテムを取得するサービス
     */
    public ItemTabCompleter(@NotNull ItemService itemService) {
        super(false);
        this.itemService = itemService;
    }

    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            return List.of();
        }

        List<String> subCommands = completeAtPosition(args, 0, "load", "get");
        if (!subCommands.isEmpty()) {
            return subCommands;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("load") || args[0].equalsIgnoreCase("get"))) {
            return itemService.getLoadedItemIds();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("get")) {
            List<String> completions = new ArrayList<>(List.of("1", "8", "16", "32", "64"));
            completions.addAll(getOnlinePlayerNames());
            return completions;
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("get")) {
            return getOnlinePlayerNames();
        }

        return List.of();
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasAdminPermission();
    }
}

package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * /test コマンドのタブ補完実装クラス。
 */
public class TestTabCompleter extends AstTabCompleter {

    private final ItemService itemService;

    public TestTabCompleter(@NotNull ItemService itemService) {
        super(true);
        this.itemService = itemService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        // args[0]: サブコマンド種別 (item)
        if (args.length == 1) {
            return completeAtPosition(args, 0, "item");
        }

        if (!args[0].equalsIgnoreCase("item")) {
            return Collections.emptyList();
        }

        // args[1]: アクション (list | info | get)
        if (args.length == 2) {
            return completeAtPosition(args, 1, "list", "info", "get");
        }

        String action = args[1].toLowerCase();

        // args[2]: list → カテゴリ / info → アイテムID / get → アイテムID
        if (args.length == 3) {
            return switch (action) {
                case "list" -> completeAtPosition(args, 2, itemService.getLoadedCategories());
                case "info", "get" -> completeAtPosition(args, 2, itemService.getLoadedItemIds());
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }
}

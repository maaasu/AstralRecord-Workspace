package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /temp コマンドのタブ補完クラスです。
 */
public final class TempTabCompleter extends AstTabCompleter {

    private static final List<String> MODES = List.of("block", "drop");

    private final ItemService itemService;

    /**
     * TempTabCompleter を初期化します。
     *
     * @param itemService アイテム補完に使うサービス
     */
    public TempTabCompleter(@NotNull ItemService itemService) {
        super(true);
        this.itemService = itemService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return itemService.getLoadedItemIds().stream()
                .filter(itemId -> itemId.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return MODES.stream()
                .filter(mode -> mode.startsWith(prefix))
                .toList();
        }
        return List.of();
    }
}

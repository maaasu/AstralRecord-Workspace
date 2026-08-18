package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.feature.item.service.ItemChatShareService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * {@code /showitem} の所持アイテム名補完を提供します。
 */
public final class ItemChatShareTabCompleter extends AstTabCompleter {
    private final ItemChatShareService itemChatShareService;

    /**
     * ItemChatShareTabCompleter を初期化します。
     *
     * @param itemChatShareService アイテム共有サービス
     */
    public ItemChatShareTabCompleter(@NotNull ItemChatShareService itemChatShareService) {
        super(true);
        this.itemChatShareService = itemChatShareService;
    }

    /**
     * 入力済み部分を除いた所持アイテム名の候補を返します。
     *
     * @param player 補完対象プレイヤー
     * @param args 現在入力中の引数
     * @return 現在の所持品に一致する候補
     */
    @Override
    protected @NotNull List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        List<String> names = itemChatShareService.getShareableItemNames(
            player.getBukkit().getInventory().getContents()
        );
        if (args.length == 0) {
            return names;
        }

        String typed = String.join(" ", args);
        int lastSeparator = typed.lastIndexOf(' ');
        String completedPrefix = lastSeparator < 0 ? "" : typed.substring(0, lastSeparator + 1);
        String normalizedPrefix = completedPrefix.toLowerCase(Locale.ROOT);
        return names.stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
            .map(name -> completedPrefix.isEmpty() ? name : name.substring(completedPrefix.length()))
            .toList();
    }
}

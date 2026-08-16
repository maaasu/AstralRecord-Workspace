package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /player コマンドの補完を提供します。
 */
public final class PlayerInfoTabCompleter extends AstTabCompleter {
    private final boolean shortcut;

    /**
     * `/player` コマンドの補完を生成します。
     */
    public PlayerInfoTabCompleter() {
        this(false);
    }

    /**
     * プレイヤー情報コマンドの補完を指定形式で生成します。
     *
     * @param shortcut `/player info` を省略した短縮コマンドかどうか
     */
    public PlayerInfoTabCompleter(boolean shortcut) {
        super(true);
        this.shortcut = shortcut;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (shortcut) {
            if (args.length == 1) {
                return getOnlinePlayerNames();
            }
            return List.of();
        }
        if (args.length == 1) {
            return List.of("info");
        }
        if (args.length == 2 && "info".equalsIgnoreCase(args[0])) {
            return getOnlinePlayerNames();
        }
        return List.of();
    }
}

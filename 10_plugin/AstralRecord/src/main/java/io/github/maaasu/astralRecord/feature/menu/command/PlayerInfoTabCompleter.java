package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /player コマンドの補完を提供します。
 */
public final class PlayerInfoTabCompleter extends AstTabCompleter {

    public PlayerInfoTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("info");
        }
        if (args.length == 2 && "info".equalsIgnoreCase(args[0])) {
            return getOnlinePlayerNames();
        }
        return List.of();
    }
}

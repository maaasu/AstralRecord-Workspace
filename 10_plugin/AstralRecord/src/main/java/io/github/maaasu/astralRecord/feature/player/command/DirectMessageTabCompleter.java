package io.github.maaasu.astralRecord.feature.player.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /message コマンドのタブ補完を提供する。
 */
public final class DirectMessageTabCompleter extends AstTabCompleter {

    /**
     * DirectMessageTabCompleter を初期化する。
     */
    public DirectMessageTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames().stream()
                .filter(name -> !name.equalsIgnoreCase(player.getBukkit().getName()))
                .toList();
        }
        return List.of();
    }
}

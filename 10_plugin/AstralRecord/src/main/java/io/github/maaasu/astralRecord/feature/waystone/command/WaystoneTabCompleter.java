package io.github.maaasu.astralRecord.feature.waystone.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /waystone コマンドのタブ補完です。
 */
public final class WaystoneTabCompleter extends AstTabCompleter {

    /**
     * WaystoneTabCompleter を初期化します。
     */
    public WaystoneTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 2) {
            return List.of("0", "1", "true", "false");
        }
        if (args.length == 3) {
            return List.of("100", "1000", "10000");
        }
        return List.of();
    }
}

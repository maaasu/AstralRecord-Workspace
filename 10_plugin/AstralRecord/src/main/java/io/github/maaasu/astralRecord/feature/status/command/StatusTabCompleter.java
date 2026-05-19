package io.github.maaasu.astralRecord.feature.status.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /status コマンドのタブ補完実装クラスです。
 */
public class StatusTabCompleter extends AstTabCompleter {

    /**
     * StatusTabCompleter を初期化します。
     */
    public StatusTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        return completeAtPosition(args, 0, "show", "detail", "refresh");
    }
}

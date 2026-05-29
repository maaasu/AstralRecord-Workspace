package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /temp コマンドのタブ補完実装クラスです。
 */
public class TempTabCompleter extends AstTabCompleter {

    /**
     * TempTabCompleter を初期化します。
     */
    public TempTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        return List.of();
    }
}

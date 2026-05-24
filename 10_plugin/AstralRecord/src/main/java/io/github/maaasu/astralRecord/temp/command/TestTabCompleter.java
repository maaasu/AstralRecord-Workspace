package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /test コマンドのタブ補完実装クラス。
 */
public class TestTabCompleter extends AstTabCompleter {

    /**
     * TestTabCompleter を初期化します。
     */
    public TestTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        return completeAtPosition(args, 0, "info", "permission");
    }
}

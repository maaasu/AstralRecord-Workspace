package io.github.maaasu.astralRecord.test;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /test の対象プレイヤー名補完。
 */
public final class TestTabCompleter extends AstTabCompleter {

    public TestTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        return completeAtPosition(args, 0, getOnlinePlayerNames());
    }
}

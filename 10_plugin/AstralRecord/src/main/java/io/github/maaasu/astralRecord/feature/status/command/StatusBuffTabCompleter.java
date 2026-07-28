package io.github.maaasu.astralRecord.feature.status.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * /statusbuff コマンドのタブ補完実装クラスです。
 */
public final class StatusBuffTabCompleter extends AstTabCompleter {

    /**
     * StatusBuffTabCompleter を初期化します。
     */
    public StatusBuffTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.stream(StatusType.values()).map(StatusType::getId).toList();
        }
        if (args.length == 4) {
            return getOnlinePlayerNames();
        }
        return List.of();
    }
}

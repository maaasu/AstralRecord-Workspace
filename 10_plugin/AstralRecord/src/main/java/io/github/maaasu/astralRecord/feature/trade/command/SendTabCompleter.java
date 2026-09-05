package io.github.maaasu.astralRecord.feature.trade.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class SendTabCompleter extends AstTabCompleter {

    /** プレイヤー向け送信先補完を初期化します。 */
    public SendTabCompleter() {
        super(true);
    }

    /** @param player 実行者 @param args 引数 @return 自分以外のオンライン候補 */
    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> completions = new ArrayList<>();
        completions.addAll(getOnlinePlayerNames().stream()
            .filter(name -> !name.equalsIgnoreCase(player.getBukkit().getName()))
            .toList());
        return completions;
    }
}

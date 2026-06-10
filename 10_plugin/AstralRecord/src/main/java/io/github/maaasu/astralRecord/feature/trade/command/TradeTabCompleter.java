package io.github.maaasu.astralRecord.feature.trade.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class TradeTabCompleter extends AstTabCompleter {

    public TradeTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> completions = new ArrayList<>();
        completions.add("accept");
        completions.addAll(getOnlinePlayerNames().stream()
            .filter(name -> !name.equalsIgnoreCase(player.getBukkit().getName()))
            .toList());
        return completions;
    }
}

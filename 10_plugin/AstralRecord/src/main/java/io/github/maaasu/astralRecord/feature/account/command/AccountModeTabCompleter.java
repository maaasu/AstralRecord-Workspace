package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class AccountModeTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames();
        }
        if (args.length == 2) {
            return List.of("0", "1", "2");
        }
        return List.of();
    }
}

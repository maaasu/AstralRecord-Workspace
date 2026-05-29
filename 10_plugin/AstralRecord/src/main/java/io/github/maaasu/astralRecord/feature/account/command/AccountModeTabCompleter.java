package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class AccountModeTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (AccountMode m : AccountMode.getEntries()) {
                completions.add(m.name());
            }
            return completions;
        }
        if (args.length == 2) {
            return getOnlinePlayerNames();
        }
        return List.of();
    }
}

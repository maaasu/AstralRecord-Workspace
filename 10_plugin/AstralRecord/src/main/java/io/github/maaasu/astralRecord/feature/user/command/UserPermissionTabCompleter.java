package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class UserPermissionTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames();
        }
        if (args.length == 2) {
            return List.of("0", "10", "99");
        }
        return List.of();
    }
}

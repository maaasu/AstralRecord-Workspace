package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class UserPermissionTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (UserPermission p : UserPermission.values()) {
                completions.add(p.name());
            }
            for (UserPermission p : UserPermission.values()) {
                completions.add(String.valueOf(p.getValue()));
            }
            return completions;
        }
        if (args.length == 2) {
            return getOnlinePlayerNames();
        }
        return List.of();
    }
}

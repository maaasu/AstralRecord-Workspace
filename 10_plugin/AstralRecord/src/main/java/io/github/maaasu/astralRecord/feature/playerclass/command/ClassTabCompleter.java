package io.github.maaasu.astralRecord.feature.playerclass.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /class コマンドのタブ補完です。
 */
public final class ClassTabCompleter extends AstTabCompleter {

    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            return List.of();
        }

        List<String> subCommands = completeAtPosition(args, 0, "change");
        if (!subCommands.isEmpty()) {
            return subCommands;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("change")) {
            var classService = AstralRecord.getInstance().getPlayerClassService();
            if (classService == null) {
                return List.of();
            }
            return classService.getClassSuggestions();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("change")) {
            return getOnlinePlayerNames();
        }

        return List.of();
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getUser().getPermission() >= UserPermission.ADMIN.getValue();
    }
}

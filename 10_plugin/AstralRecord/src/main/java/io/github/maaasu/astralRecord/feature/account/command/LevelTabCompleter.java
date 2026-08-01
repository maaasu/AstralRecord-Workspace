package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /level コマンドのタブ補完です。
 */
public final class LevelTabCompleter extends AstTabCompleter {

    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("1", "10", "100", "+1", "-1");
        }
        if (args.length == 2) {
            return getOnlinePlayerNames();
        }
        return List.of();
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasAdminPermission();
    }
}

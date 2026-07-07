package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class UserPermissionCommandAccess {

    private UserPermissionCommandAccess() {
    }

    static boolean canExecute(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }

        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null && astPlayer.hasAdminPermission()) {
            return true;
        }

        return isDebugUser(player);
    }

    static boolean isDebugUser(@NotNull CommandSender sender) {
        return sender instanceof Player player && isDebugUser(player);
    }

    private static boolean isDebugUser(@NotNull Player player) {
        return ConfigProperties.getInstance().isDebugUser(player.getUniqueId());
    }
}

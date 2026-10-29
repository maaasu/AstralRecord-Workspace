package io.github.maaasu.astralRecord.feature.boss.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 一般挑戦を先に示し、管理操作は管理者だけに補完します。 */
public final class BossTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        if (!(sender instanceof Player player)) return List.of("instances", "list", "stop", "teleport");
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null && astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())) {
            return List.of("horologium", "cancel", "instances", "list", "stop", "teleport");
        }
        return List.of("horologium", "cancel");
    }
}

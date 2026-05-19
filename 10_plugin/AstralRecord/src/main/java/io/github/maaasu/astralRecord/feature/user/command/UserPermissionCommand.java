package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class UserPermissionCommand extends AstCommand {

    public UserPermissionCommand() {
        super("userpermission", "Set user permission.", "/user permission <player|uuid> <permission>", false, 99);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || astPlayer.getUser().getPermission() < 99) {
                sendError(sender, "このコマンドを実行する権限がありません。");
                return;
            }
        }

        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        UUID targetUuid = resolveUuid(args[0]);
        if (targetUuid == null) {
            sendError(sender, "対象プレイヤーまたはUUIDが見つかりません: " + args[0]);
            return;
        }

        int permission;
        try {
            permission = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sendError(sender, "permission は数値で指定してください。");
            return;
        }

        var userService = AstralRecord.getInstance().getUserService();
        if (userService == null) {
            sendError(sender, "UserService が初期化されていません。");
            return;
        }

        UserModel updated = userService.setPermission(targetUuid, permission, getUpdatedBy(sender));
        if (updated == null) {
            sendError(sender, "ユーザー更新後の取得に失敗しました: " + targetUuid);
            return;
        }

        var online = Bukkit.getPlayer(targetUuid);
        if (online != null) {
            var astPlayer = AstPlayerCache.get(online);
            if (astPlayer != null) {
                astPlayer.applyPermission(updated);
            }
        }

        sendSuccess(sender, "permission を更新しました: " + updated.getMcid() + " = " + updated.getPermission());
    }

    private UUID resolveUuid(@NotNull String value) {
        Player player = Bukkit.getPlayerExact(value);
        if (player != null) {
            return player.getUniqueId();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID getUpdatedBy(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return SystemUser.INSTANCE.getUuid();
    }
}

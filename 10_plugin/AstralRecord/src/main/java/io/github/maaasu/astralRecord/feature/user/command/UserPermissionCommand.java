package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class UserPermissionCommand extends AstCommand {

    private static final String SOURCE_PLAYER = "PLAYER";
    private static final String SOURCE_CONSOLE = "CONSOLE";

    public UserPermissionCommand() {
        super("userpermission", "Set user permission.", "/user permission <player|uuid> <permission>", false, 99);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || astPlayer.getUser().getPermission() < 99) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return;
            }
        }

        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        UUID targetUuid = resolveUuid(args[0]);
        if (targetUuid == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5300.getId(), args[0]));
            return;
        }

        int permission;
        try {
            permission = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5301.getId()));
            return;
        }
        if (permission >= 100) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5301.getId()));
            return;
        }

        UserService userService = AstralRecord.getInstance().getUserService();
        if (userService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5302.getId()));
            return;
        }

        // 監査ログ用に変更前 permission を取得（取得失敗時は null として記録する）
        UserModel before = userService.getUser(targetUuid);
        Integer previousPermission = (before != null) ? before.getPermission() : null;

        UUID executorUuid = getUpdatedBy(sender);
        UserModel updated = userService.setPermission(targetUuid, permission, executorUuid);
        if (updated == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5303.getId(), targetUuid));
            return;
        }

        String source = (sender instanceof Player) ? SOURCE_PLAYER : SOURCE_CONSOLE;
        Logger.log(LogId.I_5053, executorUuid, targetUuid, previousPermission, updated.getPermission(), source);

        var online = Bukkit.getPlayer(targetUuid);
        if (online != null) {
            var astPlayer = AstPlayerCache.get(online);
            if (astPlayer != null) {
                astPlayer.applyPermission(updated);
            }
        }

        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_5304.getId(), updated.getMcid(), updated.getPermission()));
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

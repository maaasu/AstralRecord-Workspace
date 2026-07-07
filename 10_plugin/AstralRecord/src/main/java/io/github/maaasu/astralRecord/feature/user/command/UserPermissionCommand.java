package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class UserPermissionCommand extends AstCommand {

    private static final String SOURCE_PLAYER = "PLAYER";
    private static final String SOURCE_CONSOLE = "CONSOLE";

    public UserPermissionCommand() {
        super("userpermission", "Set user permission.",
                "/user permission <permission> [<player|uuid>]", false, UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!UserPermissionCommandAccess.canExecute(sender)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
            return;
        }

        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        UserPermission permission = UserPermission.Companion.parse(args[0]);
        if (permission == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5301.getId()));
            return;
        }

        UUID targetUuid = resolveTargetUuid(sender, args);
        if (targetUuid == null) {
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
        UserModel updated = userService.setPermission(targetUuid, permission.getValue(), executorUuid);
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
                var skillTreeService = AstralRecord.getInstance().getSkillTreeService();
                if (skillTreeService != null) {
                    skillTreeService.markViewerContextDirty(online);
                }
                online.updateCommands();
            }
        }

        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_5304.getId(), updated.getMcid(), updated.getPermission()));
    }

    @Override
    protected boolean hasPermissionOverride(@NotNull CommandSender sender) {
        return UserPermissionCommandAccess.isDebugUser(sender);
    }

    /**
     * 対象プレイヤー UUID を解決します。
     * <p>
     * プレイヤー指定が省略された場合、送信者がプレイヤーであれば自身を対象にします。
     * コンソール実行で省略された場合はエラーメッセージを送信して `null` を返します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 対象 UUID。解決できない場合は `null`
     */
    @Nullable
    private UUID resolveTargetUuid(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player player) {
                return player.getUniqueId();
            }
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return null;
        }

        UUID resolved = resolveUuid(args[1]);
        if (resolved == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5300.getId(), args[1]));
        }
        return resolved;
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

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
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class UserPermissionCommand extends AstCommand {

    private static final String SOURCE_PLAYER = "PLAYER";
    private static final String SOURCE_CONSOLE = "CONSOLE";
    private final Set<UUID> pendingTargets = ConcurrentHashMap.newKeySet();

    public UserPermissionCommand() {
        this("userpermission", "/user permission <permission> [<player|uuid>]");
    }

    /**
     * 指定したルート名と使用方法で権限変更コマンドを初期化します。
     *
     * @param commandName 登録するコマンド名
     * @param usage 表示する使用方法
     */
    public UserPermissionCommand(@NotNull String commandName, @NotNull String usage) {
        super(commandName, "ユーザー権限を変更します。", usage, false, UserPermission.ADMIN.getValue());
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
        if (!pendingTargets.add(targetUuid)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5306.getId()));
            return;
        }
        UUID executorUuid = getUpdatedBy(sender);
        String source = (sender instanceof Player) ? SOURCE_PLAYER : SOURCE_CONSOLE;
        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> {
            UserModel before = userService.getUser(targetUuid);
            UserModel updated = userService.setPermission(targetUuid, permission.getValue(), executorUuid);
            return new PermissionUpdateResult(before, updated);
        }).whenComplete((result, throwable) -> AsyncTaskUtil.runSync(plugin, () -> {
            pendingTargets.remove(targetUuid);
            if (throwable != null) {
                Logger.log(LogId.E_5059, throwable, targetUuid);
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                return;
            }
            UserModel updated = result.updated();
            if (updated == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5303.getId(), targetUuid));
                return;
            }
            Integer previousPermission = result.before() == null ? null : result.before().getPermission();
            Logger.log(LogId.I_5053, executorUuid, targetUuid, previousPermission, updated.getPermission(), source);

            var online = Bukkit.getPlayer(targetUuid);
            if (online != null) {
                var astPlayer = AstPlayerCache.get(online);
                if (astPlayer != null) {
                    astPlayer.applyPermission(updated);
                    var skillTreeService = plugin.getSkillTreeService();
                    if (skillTreeService != null) {
                        skillTreeService.markViewerContextDirty(online);
                    }
                    online.updateCommands();
                }
            }
            sendSuccess(sender, PlayerMsgResource.format(
                PlayerMsgId.P_5304.getId(),
                updated.getMcid(),
                updated.getPermission()
            ));
        }));
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

    private record PermissionUpdateResult(@Nullable UserModel before, @Nullable UserModel updated) {
    }
}

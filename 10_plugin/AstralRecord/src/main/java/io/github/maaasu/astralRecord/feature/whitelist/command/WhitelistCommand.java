package io.github.maaasu.astralRecord.feature.whitelist.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.feature.whitelist.service.WhitelistService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * whitelist の有効・無効と許可ユーザーを管理するコマンドです。
 */
public final class WhitelistCommand extends AstCommand {
    private final WhitelistService whitelistService;
    private final @Nullable UserService userService;
    private final Set<String> pendingUserNames = ConcurrentHashMap.newKeySet();

    /**
     * whitelist コマンドを初期化します。
     *
     * @param whitelistService whitelist 状態サービス
     */
    public WhitelistCommand(@NotNull WhitelistService whitelistService) {
        this(whitelistService, null);
    }

    /**
     * whitelist コマンドを初期化します。
     *
     * @param whitelistService whitelist 状態サービス
     * @param userService DB ユーザー検索サービス。{@code null} の場合は実行時にプラグインから解決します
     */
    public WhitelistCommand(
        @NotNull WhitelistService whitelistService,
        @Nullable UserService userService
    ) {
        super(
            "whitelist",
            "サーバーのwhitelistを切り替え、ユーザーを管理します。",
            "/whitelist [enable <true|false>|user <add|remove> <playerName>]",
            false,
            UserPermission.ADMIN.getValue()
        );
        this.whitelistService = whitelistService;
        this.userService = userService;
    }

    /**
     * 引数を解釈して whitelist 状態または whitelist ユーザーを更新します。
     * 引数なしでは現在値を反転し、ユーザー追加・削除では DB の登録名から UUID を解決します。
     *
     * @param sender コマンド実行者
     * @param args コマンド引数
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 0) {
            setEnabled(sender, !whitelistService.isEnabled());
            return;
        }

        if ("enable".equalsIgnoreCase(args[0])) {
            if (args.length != 2) {
                sendInvalidArgument(sender);
                return;
            }
            Boolean enabled = parseBoolean(args[1]);
            if (enabled == null) {
                sendInvalidArgument(sender);
                return;
            }
            setEnabled(sender, enabled);
            return;
        }

        if ("user".equalsIgnoreCase(args[0])) {
            handleUserCommand(sender, args);
            return;
        }

        sendInvalidArgument(sender);
    }

    private void setEnabled(@NotNull CommandSender sender, boolean enabled) {
        whitelistService.setEnabled(enabled);
        sendSuccess(
            sender,
            PlayerMsgResource.getMessage(enabled ? PlayerMsgId.P_7110.getId() : PlayerMsgId.P_7111.getId())
        );
    }

    private void handleUserCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length != 3) {
            sendInvalidArgument(sender);
            return;
        }

        boolean add;
        if ("add".equalsIgnoreCase(args[1])) {
            add = true;
        } else if ("remove".equalsIgnoreCase(args[1])) {
            add = false;
        } else {
            sendInvalidArgument(sender);
            return;
        }

        String playerName = args[2].trim();
        if (playerName.isEmpty()) {
            sendInvalidArgument(sender);
            return;
        }

        UserService resolvedUserService = resolveUserService();
        if (resolvedUserService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5302.getId()));
            return;
        }

        String pendingKey = playerName.toLowerCase(Locale.ROOT);
        if (!pendingUserNames.add(pendingKey)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7118.getId()));
            return;
        }

        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> {
            UserModel user = resolvedUserService.getUserByMcid(playerName);
            return user == null ? null : user.getUuid();
        }).whenComplete((playerUuid, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            pendingUserNames.remove(pendingKey);
            if (failure != null) {
                Logger.log(LogId.E_7111, failure, playerName);
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                return;
            }
            if (playerUuid == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_7115.getId(), playerName));
                return;
            }

            try {
                if (add) {
                    whitelistService.addWhitelistUser(playerUuid);
                } else {
                    whitelistService.removeWhitelistUser(playerUuid);
                }
                sendSuccess(
                    sender,
                    PlayerMsgResource.format(
                        add ? PlayerMsgId.P_7116.getId() : PlayerMsgId.P_7117.getId(),
                        playerName
                    )
                );
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_7111, exception, playerName);
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
            }
        }));
    }

    @Nullable
    private UserService resolveUserService() {
        if (userService != null) {
            return userService;
        }
        AstralRecord plugin = AstralRecord.getInstance();
        return plugin == null ? null : plugin.getUserService();
    }

    @Nullable
    private Boolean parseBoolean(@NotNull String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return null;
    }

    private void sendInvalidArgument(@NotNull CommandSender sender) {
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7114.getId()));
        sendUsage(sender);
    }
}

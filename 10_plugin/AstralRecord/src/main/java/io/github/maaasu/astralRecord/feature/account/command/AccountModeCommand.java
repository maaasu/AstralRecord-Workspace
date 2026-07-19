package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
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

public class AccountModeCommand extends AstCommand {
    private final Set<UUID> pendingTargets = ConcurrentHashMap.newKeySet();

    public AccountModeCommand() {
        this("accountmode", "/account mode <mode> [<player|accountUuid>]");
    }

    /**
     * 指定したルート名と使用方法でアカウントモード変更コマンドを初期化します。
     *
     * @param commandName 登録するコマンド名
     * @param usage 表示する使用方法
     */
    public AccountModeCommand(@NotNull String commandName, @NotNull String usage) {
        super(commandName, "アカウントモードを変更します。", usage, false, 99);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !astPlayer.hasAdminPermission()) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return;
            }
        }

        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        var accountService = AstralRecord.getInstance().getAccountService();
        var userService = AstralRecord.getInstance().getUserService();
        var accountModeApplicationService = AstralRecord.getInstance().getAccountModeApplicationService();
        if (accountService == null || userService == null || accountModeApplicationService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5330.getId()));
            return;
        }

        AccountMode mode = AccountMode.Companion.parse(args[0]);
        if (mode == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5331.getId()));
            return;
        }

        TargetAccountRequest request = resolveTargetAccountRequest(sender, args);
        if (request == null) {
            return;
        }
        UUID pendingKey = request.pendingKey();
        if (!pendingTargets.add(pendingKey)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5334.getId()));
            return;
        }

        AstralRecord plugin = AstralRecord.getInstance();
        UUID updatedBy = getUpdatedBy(sender);
        AsyncTaskUtil.supplyAsync(plugin, () -> {
            UUID accountUuid = request.accountUuid() != null
                ? request.accountUuid()
                : resolveRemoteAccountUuid(request.lookupUuid(), accountService, userService);
            return accountUuid == null
                ? null
                : accountModeApplicationService.persistModeChange(accountUuid, mode, updatedBy);
        }).whenComplete((persisted, throwable) -> AsyncTaskUtil.runSync(plugin, () -> {
            pendingTargets.remove(pendingKey);
            if (throwable != null) {
                Logger.log(LogId.E_5154, throwable, pendingKey);
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                return;
            }
            if (persisted == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5333.getId(), request.label()));
                return;
            }
            if (!accountModeApplicationService.applyPersistedMode(persisted)) {
                return;
            }
            AccountModel updated = persisted.account();
            sendSuccess(sender, PlayerMsgResource.format(
                PlayerMsgId.P_5332.getId(),
                updated.getAccountName(),
                updated.getMode().getDisplayName()
            ));
        }));
    }

    /**
     * 対象アカウント UUID を解決します。
     * <p>
     * プレイヤー指定が省略された場合、送信者がプレイヤーであれば自身の選択中アカウントを対象にします。
     * コンソール実行で省略された場合はエラーメッセージを送信して `null` を返します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 対象アカウント UUID。解決できない場合は `null`
     */
    @Nullable
    private TargetAccountRequest resolveTargetAccountRequest(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player player) {
                var astPlayer = AstPlayerCache.get(player);
                if (astPlayer == null) {
                    sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5333.getId(), player.getName()));
                    return null;
                }
                UUID accountUuid = astPlayer.getAccount().getUuid();
                return new TargetAccountRequest(accountUuid, null, player.getName());
            }
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return null;
        }

        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            var astPlayer = AstPlayerCache.get(online);
            if (astPlayer != null) {
                UUID accountUuid = astPlayer.getAccount().getUuid();
                return new TargetAccountRequest(accountUuid, null, args[1]);
            }
        }
        UUID lookupUuid;
        try {
            lookupUuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException ignored) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5333.getId(), args[1]));
            return null;
        }
        return new TargetAccountRequest(null, lookupUuid, args[1]);
    }

    private UUID resolveRemoteAccountUuid(
        @NotNull UUID uuid,
        @NotNull AccountService accountService,
        @NotNull UserService userService
    ) {
        AccountModel account = accountService.getAccount(uuid);
        if (account != null) {
            return account.getUuid();
        }
        var user = userService.getUser(uuid);
        if (user != null) {
            AccountModel selected = accountService.getSelectedAccount(user.getUuid(), user.getAccountId());
            return selected == null ? null : selected.getUuid();
        }
        return null;
    }

    private UUID getUpdatedBy(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return SystemUser.INSTANCE.getUuid();
    }

    private record TargetAccountRequest(
        @Nullable UUID accountUuid,
        @Nullable UUID lookupUuid,
        @NotNull String label
    ) {
        private @NotNull UUID pendingKey() {
            return accountUuid != null ? accountUuid : java.util.Objects.requireNonNull(lookupUuid);
        }
    }
}

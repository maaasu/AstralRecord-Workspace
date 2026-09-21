package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /account create の実行を扱います。 */
public final class AccountCreateCommand extends AstCommand {
    private final Set<UUID> pendingUserIds = ConcurrentHashMap.newKeySet();

    public AccountCreateCommand() {
        super("accountcreate", "アカウントを作成します。", "/account create [player]", false,
            UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length > 1) {
            sendUsage(sender);
            return;
        }
        if (sender instanceof Player player) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !astPlayer.hasAdminPermission()) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return;
            }
        }
        if (args.length == 0 && !(sender instanceof Player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return;
        }
        AccountService accountService = AstralRecord.getInstance().getAccountService();
        UserService userService = AstralRecord.getInstance().getUserService();
        if (accountService == null || userService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7402.getId()));
            return;
        }
        String targetName = args.length == 0 ? ((Player) sender).getName() : args[0];
        UUID createdBy = sender instanceof Player player ? player.getUniqueId() : SystemUser.INSTANCE.getUuid();
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        AstPlayer onlineTargetAstPlayer = onlineTarget == null ? null : AstPlayerCache.get(onlineTarget);
        AsyncTaskUtil.supplyAsync(AstralRecord.getInstance(), () -> {
            UserModel user = onlineTargetAstPlayer == null
                ? userService.getUserByMcid(targetName)
                : onlineTargetAstPlayer.getUser();
            if (user == null) {
                return null;
            }
            if (!pendingUserIds.add(user.getUuid())) {
                throw new IllegalStateException("Account creation is already pending for user " + user.getUuid());
            }
            try {
                return new CreatedAccount(user.getUuid(), targetName,
                    accountService.createAccountAutoAssigned(user.getUuid(), user.getMcid(), createdBy));
            } finally {
                pendingUserIds.remove(user.getUuid());
            }
        }).whenComplete((created, failure) -> AsyncTaskUtil.runSync(AstralRecord.getInstance(), () -> {
            if (failure != null || created == null) {
                sendError(sender, PlayerMsgResource.getMessage(failure == null ? PlayerMsgId.P_7403.getId() : PlayerMsgId.P_5062.getId()));
                return;
            }
            sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_7400.getId(), created.targetName(),
                created.account().getSlotIndex(), AccountDisplayNameFormatter.toLegacy(created.account())));
            Player target = Bukkit.getPlayerExact(created.targetName());
            AstPlayer targetAstPlayer = target == null ? null : AstPlayerCache.get(target);
            if (targetAstPlayer != null && target != sender) {
                PlayerMessageService.getInstance().send(targetAstPlayer, PlayerMsgId.P_7401,
                    created.account().getSlotIndex(), AccountDisplayNameFormatter.toLegacy(created.account()));
            }
        }));
    }

    private record CreatedAccount(@NotNull UUID userId, @NotNull String targetName, @NotNull AccountModel account) { }
}

package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountNameConflictException;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

/** /account rename の実行を扱います。 */
public final class AccountRenameCommand extends AstCommand {
    private static final String ACCOUNT_NAME_PATTERN = "[A-Za-z]{1,50}";
    private final Set<UUID> pendingAccountIds = ConcurrentHashMap.newKeySet();

    public AccountRenameCommand() {
        super("accountrename", "アカウント名を変更します。", "/account rename <accountName>", false,
            AstCommand.PERMISSION_NONE);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
            return;
        }
        if (args.length != 1) {
            sendUsage(sender);
            return;
        }
        String accountName = args[0];
        if (!accountName.matches(ACCOUNT_NAME_PATTERN)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5347.getId()));
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        AccountService accountService = AstralRecord.getInstance().getAccountService();
        if (astPlayer == null || accountService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5330.getId()));
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        if (!pendingAccountIds.add(accountId)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5334.getId()));
            return;
        }

        UUID updatedBy = player.getUniqueId();
        AsyncTaskUtil.supplyAsync(AstralRecord.getInstance(), () ->
            accountService.renameAccount(accountId, accountName, updatedBy)
        ).whenComplete((updated, failure) -> AsyncTaskUtil.runSync(AstralRecord.getInstance(), () -> {
            pendingAccountIds.remove(accountId);
            if (failure != null || updated == null) {
                if (rootCause(failure) instanceof AccountNameConflictException) {
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5348.getId()));
                } else {
                    Logger.error(LogId.E_5162, failure, accountId);
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                }
                return;
            }

            AstPlayer current = AstPlayerCache.get(player);
            if (current == null || !current.getAccount().getUuid().equals(accountId)) {
                return;
            }
            current.setAccount(updated);
            AstralRecord.getInstance().getPlayerClassService().updatePlayerListName(current);
            sendSuccess(sender, PlayerMsgResource.format(
                PlayerMsgId.P_5349.getId(),
                AccountDisplayNameFormatter.toLegacy(updated)
            ));
        }));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

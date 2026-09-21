package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountManagementRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 選択中、または識別子で指定したアカウントのUUIDを表示します。 */
public final class AccountUuidCommand extends AstCommand {
    private final AccountManagementRepository repository = new AccountManagementRepository();

    /** UUID照会コマンドを初期化します。自身の選択中UUID以外の照会は管理者専用です。 */
    public AccountUuidCommand() {
        super("accountuuid", "アカウントUUIDを確認します。", "/account uuid [<UUID|accountName|slot|player>] [accountName|slot]",
            false, AstCommand.PERMISSION_NONE);
    }

    /**
     * 自身のUUIDはキャッシュから、指定先は非同期API照会で取得します。
     * @param sender 実行者
     * @param args 省略、単独識別子、またはユーザー名とアカウント識別子
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        var player = sender instanceof Player value ? AstPlayerCache.get(value) : null;
        if (args.length == 0) {
            if (player == null) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
                return;
            }
            display(sender, player.getAccount());
            return;
        }
        if (sender instanceof Player && (player == null || !player.hasAdminPermission())) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
            return;
        }
        if (args.length > 2) { sendUsage(sender); return; }
        String selector = args.length == 2 ? args[1] : args[0];
        if (args.length == 1 && selector.matches("[0-9]{1,2}") && !(sender instanceof Player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return;
        }
        String owner = args.length == 2 ? args[0]
            : selector.matches("[0-9]{1,2}") && sender instanceof Player value ? value.getName() : null;
        AsyncTaskUtil.supplyAsync(AstralRecord.getInstance(), () -> {
            try {
                AccountModel account = args.length == 1 && owner == null ? repository.resolve(null, selector) : null;
                if (account == null) account = repository.resolve(selector, owner);
                return account;
            } catch (IOException exception) { throw new UncheckedIOException(exception); }
        }).whenComplete((account, failure) -> AsyncTaskUtil.runSync(AstralRecord.getInstance(), () -> {
            if (failure != null) {
                Logger.error(LogId.E_5164, failure, selector);
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
            } else if (account == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5333.getId(), selector));
            } else display(sender, account);
        }));
    }

    /** UUIDを名称・スロットとともに表示します。 */
    private void display(CommandSender sender, AccountModel account) {
        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_7420.getId(),
            account.getAccountName(), account.getSlotIndex(), account.getUuid()));
    }
}

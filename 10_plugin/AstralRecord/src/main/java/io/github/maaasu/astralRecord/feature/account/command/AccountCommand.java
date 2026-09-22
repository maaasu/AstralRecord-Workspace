package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;

/**
 * /account 配下のアカウント管理サブコマンドを振り分けます。
 */
public class AccountCommand extends AstCommand {
    private final AccountModeCommand modeCommand = new AccountModeCommand();
    private final AccountDeleteCommand deleteCommand = new AccountDeleteCommand();
    private final AccountSwitchCommand switchCommand = new AccountSwitchCommand();
    private final AccountRenameCommand renameCommand = new AccountRenameCommand();
    private final AccountCreateCommand createCommand = new AccountCreateCommand();
    private final AccountCloneCommand cloneCommand = new AccountCloneCommand();
    private final AccountUuidCommand uuidCommand = new AccountUuidCommand();

    /**
     * アカウント管理コマンドを初期化します。
     */
    public AccountCommand() {
        super("account", "アカウントを管理します。", "/account <create|rename|mode|delete|switch|uuid|clone|confirm> ...", false,
            UserPermission.ADMIN.getValue());
    }

    /**
     * アカウント削除コマンドのイベントハンドラを取得します。
     *
     * @return アカウント削除コマンド
     */
    public AccountDeleteCommand getDeleteCommand() {
        return deleteCommand;
    }

    /**
     * アカウント切替コマンドのイベントハンドラを取得します。
     *
     * @return アカウント切替コマンド
     */
    public AccountSwitchCommand getSwitchCommand() {
        return switchCommand;
    }

    /**
     * 複製中の操作凍結を扱うイベントハンドラを取得します。
     * @return アカウント複製コマンド
     */
    public AccountCloneCommand getCloneCommand() {
        return cloneCommand;
    }

    /**
     * /account の第一引数に応じて対象サブコマンドへ委譲します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("uuid")) {
            uuidCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("clone")) {
            cloneCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("confirm")) {
            cloneCommand.confirm(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("mode")) {
            modeCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("delete")) {
            deleteCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("switch")) {
            switchCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("rename")) {
            renameCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("create")) {
            createCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        sendUsage(sender);
    }
}

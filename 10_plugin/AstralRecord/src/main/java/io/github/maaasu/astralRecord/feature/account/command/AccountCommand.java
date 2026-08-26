package io.github.maaasu.astralRecord.feature.account.command;

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

    /**
     * アカウント管理コマンドを初期化します。
     */
    public AccountCommand() {
        super("account", "アカウントを管理します。", "/account <mode|delete> ...", false, 99);
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
        if (action.equals("mode")) {
            modeCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (action.equals("delete")) {
            deleteCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        sendUsage(sender);
    }
}

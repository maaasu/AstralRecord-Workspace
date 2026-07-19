package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;

/**
 * /user 配下のユーザー管理サブコマンドを振り分けます。
 */
public class UserCommand extends AstCommand {
    private final UserPermissionCommand permissionCommand = new UserPermissionCommand();

    /**
     * ユーザー管理コマンドを初期化します。
     */
    public UserCommand() {
        super("user", "ユーザーを管理します。", "/user permission <permission> [<player|uuid>]", false, 99);
    }

    /**
     * /user の第一引数に応じて対象サブコマンドへ委譲します。
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
        if (action.equals("permission")) {
            permissionCommand.executeCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        sendUsage(sender);
    }

    @Override
    protected boolean hasPermissionOverride(@NotNull CommandSender sender) {
        return UserPermissionCommandAccess.isDebugUser(sender);
    }
}

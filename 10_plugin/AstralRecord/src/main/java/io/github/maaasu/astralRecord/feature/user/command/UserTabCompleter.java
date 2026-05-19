package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * /user 配下のサブコマンド補完を提供します。
 */
public class UserTabCompleter extends AstTabCompleter {
    private final UserPermissionTabCompleter permissionTabCompleter = new UserPermissionTabCompleter();

    /**
     * /user の入力位置に応じて補完候補を返します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 補完候補
     */
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("permission");
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("permission")) {
            return permissionTabCompleter.onTabComplete(sender, null, "user", Arrays.copyOfRange(args, 1, args.length));
        }
        return List.of();
    }
}

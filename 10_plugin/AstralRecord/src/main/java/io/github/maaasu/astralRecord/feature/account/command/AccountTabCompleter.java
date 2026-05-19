package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * /account 配下のサブコマンド補完を提供します。
 */
public class AccountTabCompleter extends AstTabCompleter {
    private final AccountModeTabCompleter modeTabCompleter = new AccountModeTabCompleter();

    /**
     * /account の入力位置に応じて補完候補を返します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 補完候補
     */
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("mode");
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("mode")) {
            return modeTabCompleter.onTabComplete(sender, null, "account", Arrays.copyOfRange(args, 1, args.length));
        }
        return List.of();
    }
}

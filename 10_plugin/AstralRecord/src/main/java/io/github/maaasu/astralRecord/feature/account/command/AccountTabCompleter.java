package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * /account 配下のサブコマンド補完を提供します。
 */
public class AccountTabCompleter extends AstTabCompleter {
    private final AccountModeTabCompleter modeTabCompleter = new AccountModeTabCompleter();
    private final AccountDeleteTabCompleter deleteTabCompleter = new AccountDeleteTabCompleter();
    private final AccountSwitchTabCompleter switchTabCompleter = new AccountSwitchTabCompleter();

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
            if (hasAdminPermission(sender)) {
                return List.of("rename", "mode", "delete", "switch");
            }
            return List.of("rename");
        }
        if (!hasAdminPermission(sender) && !args[0].equalsIgnoreCase("rename")) {
            return List.of();
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("mode")) {
            return modeTabCompleter.onTabComplete(sender, null, "account", Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("delete")) {
            return deleteTabCompleter.onTabComplete(sender, null, "account", Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("switch")) {
            return switchTabCompleter.onTabComplete(sender, null, "account", Arrays.copyOfRange(args, 1, args.length));
        }
        return List.of();
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasAdminPermission();
    }
}

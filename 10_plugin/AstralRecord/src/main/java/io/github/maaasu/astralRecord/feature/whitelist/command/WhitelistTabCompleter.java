package io.github.maaasu.astralRecord.feature.whitelist.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * whitelist コマンドのサブコマンド・値・プレイヤー名補完を提供します。
 */
public final class WhitelistTabCompleter extends AstTabCompleter {

    /**
     * whitelist の入力位置に応じて補完候補を返します。
     *
     * @param sender コマンド送信者
     * @param args 入力済みのコマンド引数
     * @return 入力位置に対応する補完候補
     */
    @Override
    protected @NotNull List<String> getCompletions(
        @NotNull CommandSender sender,
        @NotNull String[] args
    ) {
        if (!hasAdminPermission(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("enable", "user");
        }
        if (args.length == 2 && "enable".equalsIgnoreCase(args[0])) {
            return getBooleanCompletions();
        }
        if (args.length == 2 && "user".equalsIgnoreCase(args[0])) {
            return List.of("add", "remove");
        }
        if (args.length == 3
            && "user".equalsIgnoreCase(args[0])
            && ("add".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
            return getOnlinePlayerNames();
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

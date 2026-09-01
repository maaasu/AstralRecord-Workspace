package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /account switch の対象プレイヤー名と作成済みスロットの補完を提供します。 */
public final class AccountSwitchTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return getOnlinePlayerNames();
        }
        if (args.length != 2) {
            return List.of();
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            return List.of();
        }
        var astPlayer = AstPlayerCache.get(target);
        AccountService accountService = AstralRecord.getInstance().getAccountService();
        if (astPlayer == null || accountService == null) {
            return List.of();
        }
        return accountService.getCachedSlotIndexes(astPlayer.getUser().getUuid()).stream()
            .map(String::valueOf)
            .toList();
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasAdminPermission();
    }
}

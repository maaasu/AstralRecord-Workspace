package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /account delete のプレイヤー名とキャッシュ済みアカウント名の補完を提供します。 */
public final class AccountDeleteTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames();
        }
        if (args.length == 2) {
            Player target = Bukkit.getPlayerExact(args[0]);
            AccountService accountService = AstralRecord.getInstance().getAccountService();
            AstPlayer astPlayer = target == null ? null : AstPlayerCache.get(target);
            if (astPlayer == null || accountService == null) {
                return List.of();
            }
            return accountService.getCachedAccountNames(astPlayer.getUser().getUuid());
        }
        return List.of();
    }
}

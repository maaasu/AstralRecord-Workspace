package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /account delete のプレイヤー名とスロット番号の補完を提供します。 */
public final class AccountDeleteTabCompleter extends AstTabCompleter {
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames();
        }
        if (args.length == 2) {
            return getNumberCompletions(0, 9);
        }
        return List.of();
    }
}

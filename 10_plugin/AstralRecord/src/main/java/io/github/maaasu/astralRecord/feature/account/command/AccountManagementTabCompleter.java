package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** UUID照会・複製の候補を、通信を行わずローカルの名称キャッシュから返します。 */
public final class AccountManagementTabCompleter extends AstTabCompleter {
    /**
     * /accountから渡されたサブコマンド込みの引数を補完します。
     * @param sender 実行者
     * @param args uuidまたはcloneから始まる引数
     * @return アカウント名・ユーザー名候補。未作成先スロットは補完しない
     */
    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            var ast = AstPlayerCache.get(player);
            if (ast == null || !ast.hasAdminPermission()) return List.of();
        }
        if (args.length == 2) {
            List<String> candidates = new ArrayList<>(getOnlinePlayerNames());
            for (Player player : Bukkit.getOnlinePlayers()) candidates.addAll(names(player));
            return candidates.stream().distinct().toList();
        }
        if (args.length == 3) {
            Player sourceUser = Bukkit.getPlayerExact(args[1]);
            List<String> candidates = new ArrayList<>(sourceUser == null ? List.of() : names(sourceUser));
            if (args[0].equalsIgnoreCase("clone")) candidates.addAll(getOnlinePlayerNames());
            return candidates.stream().distinct().toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("clone") && Bukkit.getPlayerExact(args[1]) != null)
            return getOnlinePlayerNames();
        return List.of();
    }

    /** 読込済みユーザーのアカウント名称だけを取得します。 */
    private List<String> names(Player player) {
        var ast = AstPlayerCache.get(player);
        var service = AstralRecord.getInstance().getAccountService();
        return ast == null || service == null ? List.of() : service.getCachedAccountNames(ast.getUser().getUuid());
    }
}

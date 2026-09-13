package io.github.maaasu.astralRecord.feature.account.command;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender; import org.bukkit.entity.Player; import org.jetbrains.annotations.NotNull; import java.util.List;
/** /expのタブ補完です。 */
public final class ExpTabCompleter extends AstTabCompleter {
 @Override protected List<String> getCompletions(@NotNull CommandSender sender,@NotNull String[] args){ if(sender instanceof Player p && (AstPlayerCache.get(p)==null || !AstPlayerCache.get(p).hasAdminPermission())) return List.of(); return args.length==1?List.of("100","1000","10000"):args.length==2?getOnlinePlayerNames():List.of(); }
}

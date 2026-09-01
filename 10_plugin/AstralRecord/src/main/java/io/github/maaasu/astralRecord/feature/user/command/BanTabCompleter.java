package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.BanDuration;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BAN コマンドの MCID・期間・日数候補を提供します。
 */
public final class BanTabCompleter extends AstTabCompleter {
    private volatile List<String> cachedMcids = List.of();
    private final @Nullable UserService userService;

    /**
     * BAN 補完を初期化します。
     *
     * @param userService ユーザーサービス。{@code null} の場合は実行時にプラグインから解決します
     */
    public BanTabCompleter(@Nullable UserService userService) {
        this.userService = userService;
    }

    @Override
    protected @NotNull List<String> getCompletions(
            @NotNull CommandSender sender,
            @NotNull String[] args
    ) {
        if (!hasAdminPermission(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return cachedMcids;
        }
        if (args.length == 2) {
            return List.of(
                    BanDuration.INDEFINITE.getArgument(),
                    BanDuration.TEMPORARY.getArgument()
            );
        }
        if (args.length == 3 && BanDuration.TEMPORARY.getArgument().equalsIgnoreCase(args[1])) {
            return List.of("1", "7", "30");
        }
        return List.of();
    }

    @Override
    protected @NotNull CompletableFuture<List<String>> getCompletionsAsync(
            @NotNull CommandSender sender,
            @NotNull String[] args
    ) {
        if (!hasAdminPermission(sender) || args.length != 1) {
            return CompletableFuture.completedFuture(getCompletions(sender, args));
        }

        AstralRecord plugin = AstralRecord.getInstance();
        UserService resolvedUserService = resolveUserService();
        if (plugin == null || resolvedUserService == null) {
            return CompletableFuture.completedFuture(cachedMcids);
        }

        return AsyncTaskUtil.supplyAsync(plugin, () ->
                        List.copyOf(resolvedUserService.getMcidSuggestions(args[0])))
                .thenApply(mcids -> {
                    cachedMcids = mcids;
                    return mcids;
                })
                .exceptionally(failure -> {
                    Logger.log(LogId.W_5054, args[0], failure.getMessage());
                    return cachedMcids;
                });
    }

    @Nullable
    private UserService resolveUserService() {
        if (userService != null) {
            return userService;
        }
        AstralRecord plugin = AstralRecord.getInstance();
        return plugin == null ? null : plugin.getUserService();
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasAdminPermission();
    }
}

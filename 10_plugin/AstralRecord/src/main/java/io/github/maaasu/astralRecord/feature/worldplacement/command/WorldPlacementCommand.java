package io.github.maaasu.astralRecord.feature.worldplacement.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** API 管理の配置スナップショットを手動で再読込します。 */
public final class WorldPlacementCommand extends AstCommand {
    private final AstralRecord plugin;

    /** @param plugin プラグイン本体 */
    public WorldPlacementCommand(@NotNull AstralRecord plugin) {
        super("worldplacements", "API 管理のワールド配置を再読込します。", "/worldplacements reload", false, 99);
        this.plugin = plugin;
    }

    /**
     * 管理者の明示操作でのみ配置 API を再取得します。
     * @param sender 実行者
     * @param args サブコマンド
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length != 1 || !"reload".equalsIgnoreCase(args[0])) {
            sendUsage(sender);
            return;
        }
        if (!"api".equalsIgnoreCase(plugin.getConfig().getString("worldPlacements.mode", "local"))) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6414.getId()));
            return;
        }
        sendInfo(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6411.getId()));
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        plugin.reloadWorldPlacements().whenComplete((snapshot, failure) -> {
            if (!plugin.isEnabled()) return;
            AsyncTaskUtil.runSync(plugin, () -> {
                CommandSender destination = playerId == null ? sender : Bukkit.getPlayer(playerId);
                if (destination == null) return;
                if (failure != null) {
                    sendError(destination, PlayerMsgResource.getMessage(PlayerMsgId.P_6413.getId()));
                } else {
                    sendSuccess(destination, PlayerMsgResource.format(PlayerMsgId.P_6412.getId(),
                        snapshot.spawners().size(), snapshot.npcs().size(), snapshot.waystones().size()));
                }
            });
        });
    }
}

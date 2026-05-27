package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.view.PlayerHudView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class PlayerHudService {
    private final StatusService statusService;
    private final PlayerHudView playerHudView;
    private BukkitTask task;

    public PlayerHudService(StatusService statusService) {
        this.statusService = statusService;
        this.playerHudView = new PlayerHudView();
    }

    public void start(AstralRecord plugin) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 10L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void updateAll() {
        double tps = Math.min(Bukkit.getServer().getTPS()[0], 20.0);
        for (var astPlayer : AstPlayerCache.getAll()) {
            Player player = astPlayer.getBukkit();
            if (!player.isOnline()) {
                continue;
            }

            StatusSnapshot snapshot = statusService.getStatus(astPlayer);
            if (astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
                playerHudView.renderActionBar(player, snapshot);
            }
            playerHudView.renderBars(player, snapshot);
            playerHudView.renderSidebar(
                player,
                astPlayer.getAccount().getMode().name(),
                astPlayer.getUser().getPermission(),
                tps,
                0,
                0L
            );
            playerHudView.renderTabList(player, tps);
        }
    }
}

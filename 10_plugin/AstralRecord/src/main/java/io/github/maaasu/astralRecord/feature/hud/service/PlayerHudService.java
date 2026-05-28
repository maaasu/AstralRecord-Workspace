package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.view.PlayerHudView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class PlayerHudService {
    private final StatusService statusService;
    private final PlayerClassService playerClassService;
    private final PlayerHudView playerHudView;
    private BukkitTask task;

    /**
     * HUD サービスを構築します。
     *
     * @param statusService       ステータスサービス
     * @param playerClassService  職業サービス（サイドバーの職業名・レベル表示に使用）
     */
    public PlayerHudService(StatusService statusService, PlayerClassService playerClassService) {
        this.statusService = statusService;
        this.playerClassService = playerClassService;
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
            String className = playerClassService.getDisplayName(astPlayer.getClassId());
            playerHudView.renderSidebar(
                player,
                astPlayer.getAccount().getMode().name(),
                astPlayer.getUser().getPermission(),
                tps,
                astPlayer.getClassLevel(),
                0L,
                className
            );
            playerHudView.renderTabList(player, tps);
        }
    }
}

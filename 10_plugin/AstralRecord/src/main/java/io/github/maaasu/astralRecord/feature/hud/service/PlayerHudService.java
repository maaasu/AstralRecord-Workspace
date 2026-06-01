package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.view.PlayerHudView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public class PlayerHudService {
    private final StatusService statusService;
    private final PlayerClassService playerClassService;
    private final PlayerHudView playerHudView;
    private final Map<UUID, BukkitTask> actionBarOverrideTasks = new HashMap<>();
    private AstralRecord plugin;
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
        this.plugin = plugin;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 10L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (BukkitTask actionBarOverrideTask : actionBarOverrideTasks.values()) {
            actionBarOverrideTask.cancel();
        }
        actionBarOverrideTasks.clear();
        plugin = null;
    }

    /**
     * ドッジ受付バーの表示を開始します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void showDodgeWindow(AstPlayer astPlayer) {
        if (plugin == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }

        showActionBarOverride(astPlayer, () -> renderDodgeWindow(astPlayer), () -> isDodgeWindowActive(astPlayer));
    }

    /**
     * 壁張り付きバーの表示を開始します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void showWallClingWindow(AstPlayer astPlayer) {
        if (plugin == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }

        showActionBarOverride(astPlayer, () -> renderWallClingWindow(astPlayer), () -> isWallClingActive(astPlayer));
    }

    /**
     * ドッジ受付バーを解除し、通常のリソース表示へ戻します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void restoreStatusActionBar(AstPlayer astPlayer) {
        cancelActionBarOverrideTask(astPlayer.getBukkit().getUniqueId());
        astPlayer.setSneakDodgeWindowExpiresAtMs(0L);
        astPlayer.setWallClingExpiresAtMs(0L);
        renderStatusActionBar(astPlayer);
    }

    private void updateAll() {
        double tps = Math.min(Bukkit.getServer().getTPS()[0], 20.0);
        for (var astPlayer : AstPlayerCache.getAll()) {
            Player player = astPlayer.getBukkit();
            if (!player.isOnline()) {
                continue;
            }

            StatusSnapshot snapshot = statusService.getStatus(astPlayer);
            if (astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                if (isWallClingActive(astPlayer)) {
                    renderWallClingWindow(astPlayer);
                } else if (isDodgeWindowActive(astPlayer)) {
                    renderDodgeWindow(astPlayer);
                } else {
                    playerHudView.renderActionBar(player, snapshot);
                }
                String className = playerClassService.getDisplayName(astPlayer.getClassId());
                playerHudView.renderSidebar(
                    player,
                    astPlayer.getAccount().getMode().name(),
                    tps,
                    astPlayer.getAccount().getLevel(),
                    astPlayer.getAccount().getTotalExperience(),
                    astPlayer.getClassLevel(),
                    className
                );
            }
            playerHudView.renderBars(player, snapshot);
            playerHudView.renderTabList(player, tps);
        }
    }

    private void renderStatusActionBar(AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        playerHudView.renderActionBar(player, statusService.getStatus(astPlayer));
    }

    private void renderDodgeWindow(AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        long remaining = Math.max(0L, astPlayer.getSneakDodgeWindowExpiresAtMs() - System.currentTimeMillis());
        double progress = (double) remaining / (double) io.github.maaasu.astralRecord.feature.player.service.DodgeService.QUICK_SNEAK_WINDOW_MS;
        playerHudView.renderDodgeWindowActionBar(player, progress);
    }

    private void renderWallClingWindow(AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        long remaining = Math.max(0L, astPlayer.getWallClingExpiresAtMs() - System.currentTimeMillis());
        double progress = (double) remaining / (double) io.github.maaasu.astralRecord.feature.player.service.AirActionService.WALL_CLING_DURATION_MS;
        playerHudView.renderWallClingActionBar(player, progress);
    }

    private boolean isDodgeWindowActive(AstPlayer astPlayer) {
        return astPlayer.getSneakDodgeWindowExpiresAtMs() > System.currentTimeMillis();
    }

    private boolean isWallClingActive(AstPlayer astPlayer) {
        return astPlayer.getWallClingExpiresAtMs() > System.currentTimeMillis();
    }

    private void showActionBarOverride(AstPlayer astPlayer, Runnable renderer, BooleanSupplier activeChecker) {
        cancelActionBarOverrideTask(astPlayer.getBukkit().getUniqueId());
        renderer.run();
        BukkitTask actionBarOverrideTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!activeChecker.getAsBoolean()) {
                cancelActionBarOverrideTask(astPlayer.getBukkit().getUniqueId());
                renderStatusActionBar(astPlayer);
                return;
            }
            renderer.run();
        }, 1L, 1L);
        actionBarOverrideTasks.put(astPlayer.getBukkit().getUniqueId(), actionBarOverrideTask);
    }

    private void cancelActionBarOverrideTask(UUID playerUuid) {
        BukkitTask task = actionBarOverrideTasks.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
    }
}

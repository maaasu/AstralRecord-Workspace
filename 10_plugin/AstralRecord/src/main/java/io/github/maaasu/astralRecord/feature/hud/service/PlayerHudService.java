package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.view.PlayerHudView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class PlayerHudService {
    private final StatusService statusService;
    private final PlayerClassService playerClassService;
    private final AccountService accountService;
    private final PlayerSettingService playerSettingService;
    private final BossChallengeService bossChallengeService;
    private final PlayerHudView playerHudView;
    private final Map<UUID, BukkitTask> actionBarOverrideTasks = new HashMap<>();
    private final Map<UUID, Function<AstPlayer, Component>> primaryActionBarRenderers = new HashMap<>();
    private AstralRecord plugin;
    private BukkitTask task;

    /**
     * HUD サービスを構築します。
     *
     * @param statusService       ステータスサービス
     * @param playerClassService  職業サービス（サイドバーの職業名・レベル表示に使用）
     * @param accountService      アカウント経験値サービス
     * @param playerSettingService プレイヤー設定サービス
     * @param bossChallengeService ボス挑戦サービス
     */
    public PlayerHudService(
        StatusService statusService,
        PlayerClassService playerClassService,
        AccountService accountService,
        PlayerSettingService playerSettingService,
        BossChallengeService bossChallengeService
    ) {
        this.statusService = statusService;
        this.playerClassService = playerClassService;
        this.accountService = accountService;
        this.playerSettingService = playerSettingService;
        this.bossChallengeService = bossChallengeService;
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
        refreshActionBar(astPlayer);
    }

    /**
     * プレイヤー固有の ActionBar 描画関数を設定します。
     *
     * @param playerId プレイヤー UUID
     * @param renderer 通常 HUD の代わりに使う描画関数
     */
    public void setPrimaryActionBarRenderer(@NotNull UUID playerId, @NotNull Function<AstPlayer, Component> renderer) {
        primaryActionBarRenderers.put(playerId, renderer);
    }

    /**
     * プレイヤー固有の ActionBar 描画関数を解除します。
     *
     * @param playerId プレイヤー UUID
     */
    public void clearPrimaryActionBarRenderer(@NotNull UUID playerId) {
        primaryActionBarRenderers.remove(playerId);
    }

    /**
     * 現在の状態に応じた ActionBar を即時再描画します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void refreshActionBar(@NotNull AstPlayer astPlayer) {
        if (isWallClingActive(astPlayer)) {
            renderWallClingWindow(astPlayer);
            return;
        }
        if (isDodgeWindowActive(astPlayer)) {
            renderDodgeWindow(astPlayer);
            return;
        }
        renderPrimaryActionBar(astPlayer, statusService.getStatus(astPlayer));
    }

    private void updateAll() {
        double mspt = Bukkit.getServer().getAverageTickTime();
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
                renderPrimaryActionBar(astPlayer, snapshot);
                }
                double experienceProgress = accountService.experienceProgress(
                    astPlayer.getAccount().getUuid(),
                    astPlayer.getAccount().getLevel(),
                    astPlayer.getAccount().getTotalExperience()
                );
                String className = playerClassService.getDisplayName(astPlayer.getClassId());
                boolean showPerformanceInfo = playerSettingService.isPerformanceInfoDisplayEnabled(
                    astPlayer.getUser().getUuid()
                );
                playerHudView.renderSidebar(
                    player,
                    mspt,
                    astPlayer.getAccount().getLevel(),
                    experienceProgress,
                    astPlayer.getClassLevel(),
                    className,
                    showPerformanceInfo,
                    bossChallengeService.findSidebarInfo(player.getUniqueId())
                );
            }
            playerHudView.renderBars(player, snapshot);
            playerHudView.renderTabList(
                player,
                mspt,
                playerSettingService.isPerformanceInfoDisplayEnabled(astPlayer.getUser().getUuid())
            );
        }
    }

    private void renderStatusActionBar(AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        renderPrimaryActionBar(astPlayer, statusService.getStatus(astPlayer));
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
                refreshActionBar(astPlayer);
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

    private void renderPrimaryActionBar(@NotNull AstPlayer astPlayer, @NotNull StatusSnapshot snapshot) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        Component override = resolvePrimaryActionBar(astPlayer);
        if (override != null) {
            player.sendActionBar(override);
            return;
        }
        playerHudView.renderActionBar(player, snapshot);
    }

    private @Nullable Component resolvePrimaryActionBar(@NotNull AstPlayer astPlayer) {
        Function<AstPlayer, Component> renderer = primaryActionBarRenderers.get(astPlayer.getBukkit().getUniqueId());
        return renderer == null ? null : renderer.apply(astPlayer);
    }
}

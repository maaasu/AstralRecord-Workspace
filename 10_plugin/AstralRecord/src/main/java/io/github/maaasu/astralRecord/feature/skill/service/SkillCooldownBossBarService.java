package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * スキルクールダウンをプレイヤーごとに1本のボスバーで表示します。
 */
public final class SkillCooldownBossBarService {
    private static final long UPDATE_INTERVAL_TICKS = 1L;

    private final SkillService skillService;
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private BukkitTask task;

    public SkillCooldownBossBarService(@NotNull SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * サービスを開始します。
     *
     * @param plugin プラグイン
     */
    public void start(@NotNull AstralRecord plugin) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 2L, UPDATE_INTERVAL_TICKS);
    }

    /**
     * サービスを停止します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearAll();
    }

    private void updateAll() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            Player player = astPlayer.getBukkit();
            if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                removeBossBar(player);
                continue;
            }

            var activeCooldowns = skillService.getActiveCooldowns(player.getUniqueId());
            if (activeCooldowns.isEmpty()) {
                removeBossBar(player);
                continue;
            }

            var latest = activeCooldowns.get(0);
            BossBar bossBar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> createBossBar(player));
            bossBar.addPlayer(player);
            bossBar.setTitle(buildBossBarTitle(latest, activeCooldowns.size() - 1));
            bossBar.setColor(BarColor.WHITE);
            bossBar.setStyle(BarStyle.SEGMENTED_10);
            long totalTicks = Math.max(1L, latest.totalTicks());
            long remainingTicks = Math.max(0L, latest.remainingTicks());
            bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, (double) remainingTicks / (double) totalTicks)));
        }
    }

    @NotNull BossBar createBossBar(@NotNull Player player) {
        BossBar bossBar = Bukkit.createBossBar(
                "",
                BarColor.WHITE,
                BarStyle.SEGMENTED_10
        );
        bossBar.setVisible(true);
        bossBar.addPlayer(player);
        return bossBar;
    }

    private void removeBossBar(@NotNull Player player) {
        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar == null) {
            return;
        }
        bossBar.removeAll();
        bossBar.setVisible(false);
    }

    private void clearAll() {
        for (BossBar bossBar : bossBars.values()) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
        bossBars.clear();
    }

    private @NotNull String buildBossBarTitle(@NotNull SkillService.ActiveCooldown cooldown, int hiddenCount) {
        String remainingSeconds = String.format(Locale.ROOT, "%.1fs", cooldown.remainingTicks() / 20.0D);
        String displayName = SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID.equals(cooldown.cooldownKey())
                ? ColorCodeUtil.WHITE + "通常攻撃"
                : cooldown.skillName();
        String base = displayName
                + ColorCodeUtil.GRAY + " "
                + ColorCodeUtil.WHITE + remainingSeconds;
        if (hiddenCount > 0) {
            return base
                    + ColorCodeUtil.DARK_GRAY + " ・"
                    + ColorCodeUtil.YELLOW + "他"
                    + hiddenCount
                    + ColorCodeUtil.DARK_GRAY + "件";
        }
        return base;
    }
}

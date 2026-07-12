package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public class PlayerHudView {
    private static final String OBJECTIVE_NAME = "astral_info";
    private static final int TRANSIENT_BAR_LENGTH = 28;
    private static final int SIDEBAR_BAR_LENGTH = 50;

    public void renderActionBar(Player player, StatusSnapshot snapshot) {
        double maxHp = snapshot.getMaxValue(StatusType.MAX_HEALTH);
        double maxMp = snapshot.getMaxValue(StatusType.MAX_MANA);
        double maxEnergy = snapshot.getMaxValue(StatusType.MAX_ENERGY);
        player.sendActionBar(Component.empty()
            .append(statText("HP", snapshot.getCurrentHp(), maxHp, NamedTextColor.RED))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(statText("MP", snapshot.getCurrentMp(), maxMp, NamedTextColor.AQUA))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(statText("ENG", snapshot.getCurrentEnergy(), maxEnergy, NamedTextColor.YELLOW))
            .append(shieldActionText(snapshot)));
    }

    /**
     * ドッジ受付中のアクションバーを描画します。
     *
     * @param player 対象プレイヤー
     * @param progressRemaining 残り受付割合（0.0-1.0）
     */
    public void renderDodgeWindowActionBar(Player player, double progressRemaining) {
        renderTransientActionBar(player, "DODGE", progressRemaining, NamedTextColor.GOLD, NamedTextColor.GREEN);
    }

    /**
     * 壁張り付き中のアクションバーを描画します。
     *
     * @param player 対象プレイヤー
     * @param progressRemaining 残り時間割合（0.0-1.0）
     */
    public void renderWallClingActionBar(Player player, double progressRemaining) {
        renderTransientActionBar(player, "WALL", progressRemaining, NamedTextColor.AQUA, NamedTextColor.WHITE);
    }

    public void renderBars(Player player, StatusSnapshot snapshot) {
        setHealthBar(player, ratio(snapshot.getCurrentHp(), snapshot.getMaxValue(StatusType.MAX_HEALTH)));
        player.setFoodLevel((int) Math.round(ratio(snapshot.getCurrentEnergy(), snapshot.getMaxValue(StatusType.MAX_ENERGY)) * 20.0D));
        player.setSaturation(0.0F);
        player.setExp((float) ratio(snapshot.getCurrentMp(), snapshot.getMaxValue(StatusType.MAX_MANA)));
        setArmorBar(player, ratio(snapshot.getCurrentShield(), snapshot.getMaxValue(StatusType.MAX_SHIELD)));
    }

    /**
     * サイドバーを描画します。
     *
     * @param player 対象プレイヤー
     * @param mspt 現在のMSPT
     * @param playerLevel アカウント単位のプレイヤーレベル
     * @param experienceProgress 現在レベル内の経験値進捗（0.0-1.0）
     * @param classLevel 現在のクラスレベル
     * @param className 現在のクラス表示名
     * @param showPerformanceInfo MSPT・Ping を表示するか
     * @param bossInfo 挑戦中ボス情報。挑戦していない場合は null
     */
    public void renderSidebar(
        Player player,
        double mspt,
        int playerLevel,
        double experienceProgress,
        int classLevel,
        String className,
        boolean showPerformanceInfo,
        BossChallengeSidebarInfo bossInfo
    ) {
        Scoreboard scoreboard = player.getScoreboard();
        if (scoreboard == Bukkit.getScoreboardManager().getMainScoreboard()) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(scoreboard);
        }

        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(
                OBJECTIVE_NAME,
                Criteria.DUMMY,
                Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
            );
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        int ping = player.getPing();
        clearSidebar(scoreboard);
        objective.getScore(ColorCodeUtil.AQUA + "オンライン" + ColorCodeUtil.GRAY + ": " + ColorCodeUtil.WHITE
                + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()).setScore(11);
        if (showPerformanceInfo) {
            objective.getScore(msptLegacyColor(mspt) + "MSPT" + ColorCodeUtil.GRAY + ": " + ColorCodeUtil.WHITE + String.format("%.1f", mspt)).setScore(10);
            objective.getScore(pingLegacyColor(ping) + "Ping" + ColorCodeUtil.GRAY + ": " + ColorCodeUtil.WHITE + ping + "ms").setScore(9);
        }
        objective.getScore(buildSeparator("player")).setScore(8);
        objective.getScore(ColorCodeUtil.GOLD + "レベル" + ColorCodeUtil.GRAY + ": " + "Lv." + ColorCodeUtil.YELLOW + playerLevel).setScore(7);
        objective.getScore(buildExperienceBar("EXP", experienceProgress, ColorCodeUtil.GREEN)).setScore(6);
        objective.getScore(buildSeparator("class")).setScore(5);
        objective.getScore(ColorCodeUtil.DARK_AQUA + "クラス" + ColorCodeUtil.GRAY + ": " + className
                + ColorCodeUtil.GRAY + " Lv." + ColorCodeUtil.YELLOW + classLevel).setScore(4);
        if (bossInfo != null) {
            renderBossInfo(objective, bossInfo);
        }
    }

    /**
     * Tabキー押下時のプレイヤーリストにMSPT・Pingをヘッダー/フッター表示します。
     *
     * @param player 対象プレイヤー
     * @param mspt 現在のサーバーMSPT（平均値）
     */
    public void renderTabList(Player player, double mspt, boolean showPerformanceInfo) {
        int ping = player.getPing();
        Component header = Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(showPerformanceInfo
                ? Component.newline().append(Component.text("MSPT ", NamedTextColor.GRAY))
                    .append(Component.text(String.format("%.1f", mspt), msptTextColor(mspt)))
                : Component.empty())
            .build();
        Component footer = showPerformanceInfo
            ? Component.text().append(Component.text("Ping ", NamedTextColor.GRAY))
                .append(Component.text(ping + "ms", pingTextColor(ping))).build()
            : Component.empty();
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    private void renderBossInfo(Objective objective, BossChallengeSidebarInfo info) {
        objective.getScore(buildSeparator("boss")).setScore(3);
        objective.getScore(ColorCodeUtil.RED + "ボス" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.toLegacyText(info.bossDisplayName(), "Boss")).setScore(2);
        objective.getScore(ColorCodeUtil.RED + "デス" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.WHITE + info.deathCount() + "/" + info.deathLimit()).setScore(1);
        objective.getScore(ColorCodeUtil.GOLD + "時間" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.WHITE + info.elapsedSeconds() + "/" + info.timeLimitSeconds() + "s").setScore(0);
        objective.getScore(ColorCodeUtil.LIGHT_PURPLE + "参加者" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.WHITE + String.join("、", info.participantNames())).setScore(-1);
    }

    private void setHealthBar(Player player, double ratio) {
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getBaseValue() != 20.0D) {
            maxHealth.setBaseValue(20.0D);
        }
        double health = Math.clamp(ratio * 20.0D, 0.5D, 20.0D);
        if (player.getHealth() != health) {
            player.setHealth(health);
        }
    }

    private void setArmorBar(Player player, double ratio) {
        var armor = player.getAttribute(Attribute.ARMOR);
        if (armor != null) {
            armor.setBaseValue(Math.clamp(ratio, 0.0D, 1.0D) * 20.0D);
        }
    }

    private void clearSidebar(Scoreboard scoreboard) {
        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }
    }

    private String buildExperienceBar(String label, double ratio, String fillColor) {
        int progressPercent = (int) Math.round(Math.clamp(ratio, 0.0D, 1.0D) * 100.0D);
        int filledLength = (progressPercent * SIDEBAR_BAR_LENGTH) / 100;

        StringBuilder bar = new StringBuilder();
        bar.append(ColorCodeUtil.DARK_GRAY).append(label).append(" ");
        bar.append(fillColor);
        bar.repeat("|", Math.max(0, filledLength));
        bar.append(ColorCodeUtil.DARK_GRAY);
        bar.repeat("|", Math.max(0, SIDEBAR_BAR_LENGTH - filledLength));
        bar.append(ColorCodeUtil.WHITE).append(" ").append(progressPercent).append("%");

        return bar.toString();
    }

    private String buildSeparator(String label) {
        return ColorCodeUtil.DARK_AQUA + "◈─── " + label + " ───◈";
    }

    private String msptLegacyColor(double mspt) {
        if (mspt <= 25.0D) return ColorCodeUtil.GREEN;
        if (mspt <= 40.0D) return ColorCodeUtil.YELLOW;
        return ColorCodeUtil.RED;
    }

    private String pingLegacyColor(int ping) {
        if (ping < 50) return ColorCodeUtil.GREEN;
        if (ping < 100) return ColorCodeUtil.YELLOW;
        return ColorCodeUtil.RED;
    }

    private NamedTextColor msptTextColor(double mspt) {
        if (mspt <= 25.0D) return NamedTextColor.GREEN;
        if (mspt <= 40.0D) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    private NamedTextColor pingTextColor(int ping) {
        if (ping < 50) return NamedTextColor.GREEN;
        if (ping < 100) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    private double ratio(double current, double max) {
        if (max <= 0.0D) {
            return 0.0D;
        }
        return Math.clamp(current / max, 0.0D, 1.0D);
    }

    private Component statText(String label, double current, double max, NamedTextColor color) {
        return Component.text(label + " ", color, TextDecoration.BOLD)
            .append(Component.text(String.format("%.0f", current), NamedTextColor.WHITE))
            .append(Component.text("/", NamedTextColor.DARK_GRAY))
            .append(Component.text(String.format("%.0f", max), NamedTextColor.GRAY));
    }

    private Component shieldActionText(StatusSnapshot snapshot) {
        double maxShield = snapshot.getMaxValue(StatusType.MAX_SHIELD);
        if (maxShield <= 0.0D) {
            return Component.empty();
        }
        return Component.text("  ", NamedTextColor.DARK_GRAY)
            .append(statText("SH", snapshot.getCurrentShield(), maxShield, NamedTextColor.BLUE));
    }

    private void renderTransientActionBar(Player player, String label, double progressRemaining, NamedTextColor labelColor, NamedTextColor fillColor) {
        int filledLength = (int) Math.round(Math.clamp(progressRemaining, 0.0D, 1.0D) * TRANSIENT_BAR_LENGTH);
        Component bar = Component.text("[", NamedTextColor.DARK_GRAY);
        for (int i = 0; i < TRANSIENT_BAR_LENGTH; i++) {
            bar = bar.append(Component.text("=", i < filledLength ? fillColor : NamedTextColor.DARK_GRAY));
        }
        bar = bar.append(Component.text("]", NamedTextColor.DARK_GRAY));

        player.sendActionBar(Component.empty()
            .append(Component.text(label, labelColor, TextDecoration.BOLD))
            .append(Component.text(" ", NamedTextColor.DARK_GRAY))
            .append(bar));
    }
}

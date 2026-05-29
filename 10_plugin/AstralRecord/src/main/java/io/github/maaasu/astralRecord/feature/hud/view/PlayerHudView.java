package io.github.maaasu.astralRecord.feature.hud.view;

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

    public void renderActionBar(Player player, StatusSnapshot snapshot) {
        double maxHp = snapshot.getMaxValue(StatusType.MAX_HEALTH);
        double maxMp = snapshot.getMaxValue(StatusType.MAX_MANA);
        double maxEnergy = snapshot.getMaxValue(StatusType.MAX_ENERGY);
        player.sendActionBar(Component.empty()
            .append(statText("HP", snapshot.getCurrentHp(), maxHp, NamedTextColor.RED))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(statText("MP", snapshot.getCurrentMp(), maxMp, NamedTextColor.AQUA))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(statText("ENG", snapshot.getCurrentEnergy(), maxEnergy, NamedTextColor.YELLOW)));
    }

    /**
     * ドッジ受付中のアクションバーを描画します。
     *
     * @param player 対象プレイヤー
     * @param progressRemaining 受付残量（0.0-1.0）
     */
    public void renderDodgeWindowActionBar(Player player, double progressRemaining) {
        renderTransientActionBar(player, "DODGE", progressRemaining, NamedTextColor.GOLD, NamedTextColor.GREEN);
    }

    /**
     * 壁張り付き中のアクションバーを描画します。
     *
     * @param player 対象プレイヤー
     * @param progressRemaining 残り進捗（0.0-1.0）
     */
    public void renderWallClingActionBar(Player player, double progressRemaining) {
        renderTransientActionBar(player, "WALL", progressRemaining, NamedTextColor.AQUA, NamedTextColor.WHITE);
    }

    public void renderBars(Player player, StatusSnapshot snapshot) {
        setHealthBar(player, ratio(snapshot.getCurrentHp(), snapshot.getMaxValue(StatusType.MAX_HEALTH)));
        player.setFoodLevel((int) Math.round(ratio(snapshot.getCurrentEnergy(), snapshot.getMaxValue(StatusType.MAX_ENERGY)) * 20.0D));
        player.setSaturation(0.0F);
        player.setExp((float) ratio(snapshot.getCurrentMp(), snapshot.getMaxValue(StatusType.MAX_MANA)));
    }

    /**
     * サイドバーを描画します。
     *
     * @param player          対象プレイヤー
     * @param mode            アカウントモード名
     * @param permission      権限レベル
     * @param tps             現在の TPS
     * @param classLevel      職業レベル
     * @param totalExperience 累計経験値（進捗バー算出用）
     * @param className       職業表示名（カラーコード付き可）
     */
    public void renderSidebar(Player player, String mode, int permission, double tps, int classLevel, long totalExperience, String className) {
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
        objective.getScore(ColorCodeUtil.DARK_GRAY + "━━━━━━━━━━━━━━━━━━").setScore(8);
        objective.getScore(ColorCodeUtil.AQUA + "オンライン " + ColorCodeUtil.WHITE + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()).setScore(7);
        objective.getScore(tpsLegacyColor(tps) + "TPS " + ColorCodeUtil.WHITE + String.format("%.1f", tps)).setScore(6);
        objective.getScore(pingLegacyColor(ping) + "遅延 " + ColorCodeUtil.WHITE + ping + "ms").setScore(5);
        objective.getScore(ColorCodeUtil.GRAY + "職業 " + className).setScore(4);
        objective.getScore(ColorCodeUtil.YELLOW + "レベル " + ColorCodeUtil.GOLD + classLevel).setScore(3);
        objective.getScore(buildLevelBar(totalExperience)).setScore(2);
        objective.getScore(ColorCodeUtil.BLACK + "  ").setScore(1);
        objective.getScore(ColorCodeUtil.BLUE + "モード " + ColorCodeUtil.WHITE + mode).setScore(0);
    }

    /**
     * Tabキー押下時のプレイヤーリストにTPS・pingをヘッダー/フッターとして表示します。
     *
     * @param player 対象プレイヤー
     * @param tps 現在のサーバーTPS（平均値）
     */
    public void renderTabList(Player player, double tps) {
        int ping = player.getPing();
        Component header = Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("TPS ", NamedTextColor.GRAY))
            .append(Component.text(String.format("%.1f", tps), tpsTextColor(tps)))
            .build();
        Component footer = Component.text()
            .append(Component.text("Ping ", NamedTextColor.GRAY))
            .append(Component.text(ping + "ms", pingTextColor(ping)))
            .build();
        player.sendPlayerListHeaderAndFooter(header, footer);
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

    private void clearSidebar(Scoreboard scoreboard) {
        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }
    }

    /**
     * レベル進行度をプログレスバー形式で表示するテキストを生成します。
     * 経験値の上位ビットを使用して進捗を算出し、パーセンテージと視覚的なバーを表示します。
     *
     * @param totalExperience 累計経験値
     * @return プログレスバー形式のテキスト
     */
    private String buildLevelBar(long totalExperience) {
        int progressPercent = (int) Math.min(100, (totalExperience / 100000L) % 100);
        int barLength = 10;
        int filledLength = (progressPercent * barLength) / 100;

        StringBuilder bar = new StringBuilder();
        bar.append(ColorCodeUtil.DARK_GRAY + "進捗 ");
        bar.append(ColorCodeUtil.GREEN);
        for (int i = 0; i < filledLength; i++) {
            bar.append("█");
        }
        bar.append(ColorCodeUtil.DARK_GRAY);
        for (int i = filledLength; i < barLength; i++) {
            bar.append("█");
        }
        bar.append(ColorCodeUtil.WHITE).append(" ").append(progressPercent).append("%");

        return bar.toString();
    }

    private String tpsLegacyColor(double tps) {
        if (tps >= 18.0) return ColorCodeUtil.GREEN;
        if (tps >= 15.0) return ColorCodeUtil.YELLOW;
        return ColorCodeUtil.RED;
    }

    private String pingLegacyColor(int ping) {
        if (ping < 50) return ColorCodeUtil.GREEN;
        if (ping < 100) return ColorCodeUtil.YELLOW;
        return ColorCodeUtil.RED;
    }

    private NamedTextColor tpsTextColor(double tps) {
        if (tps >= 18.0) return NamedTextColor.GREEN;
        if (tps >= 15.0) return NamedTextColor.YELLOW;
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

    /**
     * アクションバー用の値表示テキストを生成します。
     *
     * @param label 表示ラベル
     * @param current 現在値
     * @param max 最大値
     * @param color メインカラー
     * @return 表示用コンポーネント
     */
    private Component statText(String label, double current, double max, NamedTextColor color) {
        return Component.text(label + " ", color, TextDecoration.BOLD)
            .append(Component.text(String.format("%.0f", current), NamedTextColor.WHITE))
            .append(Component.text("/", NamedTextColor.DARK_GRAY))
            .append(Component.text(String.format("%.0f", max), NamedTextColor.GRAY));
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

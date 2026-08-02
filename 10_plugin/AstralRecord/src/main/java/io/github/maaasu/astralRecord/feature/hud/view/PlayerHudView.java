package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class PlayerHudView {
    private static final String OBJECTIVE_NAME = "astral_info";
    private static final int TRANSIENT_BAR_LENGTH = 28;
    private static final int SIDEBAR_BAR_LENGTH = 40;
    private static final int SIDEBAR_LINE_LIMIT = 15;
    private static final int BUFF_DISPLAY_LIMIT = 5;
    private static final String SIDEBAR_BAR_CHAR = "|";

    public void renderActionBar(Player player, StatusSnapshot snapshot) {
        renderActionBar(player, snapshot, List.of(), null, 0.0D);
    }

    /**
     * 通常リソースと状態異常を同じアクションバーへ描画します。
     *
     * @param player 対象プレイヤー
     * @param snapshot 現在のステータス
     * @param activeConditions 現在有効な状態異常
     */
    public void renderActionBar(
        Player player,
        StatusSnapshot snapshot,
        Collection<ActiveCondition> activeConditions
    ) {
        renderActionBar(player, snapshot, activeConditions, null, 0.0D);
    }

    /**
     * 通常リソース・状態異常・シールドリチャージ残り時間を同じアクションバーへ描画します。
     *
     * @param player 対象プレイヤー
     * @param snapshot 現在のステータス
     * @param activeConditions 現在有効な状態異常
     * @param shieldRechargeState シールドリチャージ状態。通常時は {@code null}
     */
    public void renderActionBar(
        Player player,
        StatusSnapshot snapshot,
        Collection<ActiveCondition> activeConditions,
        ShieldRechargeState shieldRechargeState
    ) {
        renderActionBar(player, snapshot, activeConditions, shieldRechargeState, 0.0D);
    }

    /**
     * 通常リソース・状態異常・DPSを同じアクションバーへ描画します。
     *
     * @param player 対象プレイヤー
     * @param snapshot 現在のステータス
     * @param activeConditions 現在有効な状態異常
     * @param shieldRechargeState シールドリチャージ状態。通常時は {@code null}
     * @param currentDps 直近1秒の秒間与ダメージ
     */
    public void renderActionBar(
        Player player,
        StatusSnapshot snapshot,
        Collection<ActiveCondition> activeConditions,
        ShieldRechargeState shieldRechargeState,
        double currentDps
    ) {
        double maxHp = snapshot.getMaxValue(StatusType.MAX_HEALTH);
        double maxMp = snapshot.getMaxValue(StatusType.MAX_MANA);
        double maxEnergy = snapshot.getMaxValue(StatusType.MAX_ENERGY);
        player.sendActionBar(Component.empty()
            .append(statText("HP", snapshot.getCurrentHp(), maxHp, NamedTextColor.RED))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(statText("MP", snapshot.getCurrentMp(), maxMp, NamedTextColor.AQUA))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(statText("ENG", snapshot.getCurrentEnergy(), maxEnergy, NamedTextColor.YELLOW))
            .append(shieldActionText(snapshot, shieldRechargeState))
            .append(conditionActionText(activeConditions))
            .append(dpsActionText(currentDps)));
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
     * @param tps 直前10秒のTPS平均
     * @param playerLevel アカウント単位のプレイヤーレベル
     * @param experienceProgress 現在レベル内の経験値進捗（0.0-1.0）
     * @param classLevel 現在のクラスレベル
     * @param className 現在のクラス表示名
     * @param worldName 現在のワールド表示名
     * @param regionName 現在の地域表示名
     * @param regionLevel 現在の地域レベル
     * @param showPerformanceInfo MSPT・Ping を表示するか
     * @param bossInfo 挑戦中ボス情報。挑戦していない場合は null
     */
    public void renderSidebar(
        Player player,
        double tps,
        int playerLevel,
        double experienceProgress,
        int classLevel,
        String className,
        String worldName,
        String regionName,
        int regionLevel,
        boolean showPerformanceInfo,
        BossChallengeSidebarInfo bossInfo
    ) {
        renderSidebar(
            player,
            tps,
            playerLevel,
            experienceProgress,
            classLevel,
            className,
            worldName,
            regionName,
            regionLevel,
            showPerformanceInfo,
            bossInfo,
            false,
            List.of()
        );
    }

    /**
     * サイドバーを描画し、設定が有効な場合は獲得順のバフを最大5件表示します。
     * 表示行数が15行を超えないよう、バフを優先して性能情報の表示を調整します。
     *
     * @param player 対象プレイヤー
     * @param tps 直前10秒のTPS平均
     * @param playerLevel アカウント単位のプレイヤーレベル
     * @param experienceProgress 現在レベル内の経験値進捗（0.0-1.0）
     * @param classLevel 現在のクラスレベル
     * @param className 現在のクラス表示名
     * @param worldName 現在のワールド表示名
     * @param regionName 現在の地域表示名
     * @param regionLevel 現在の地域レベル
     * @param showPerformanceInfo TPS・Ping を表示するか
     * @param bossInfo 挑戦中ボス情報。挑戦していない場合は null
     * @param showBuffInfo バフ情報を表示するか
     * @param activeBuffs 獲得順の有効バフ一覧
     */
    public void renderSidebar(
        Player player,
        double tps,
        int playerLevel,
        double experienceProgress,
        int classLevel,
        String className,
        String worldName,
        String regionName,
        int regionLevel,
        boolean showPerformanceInfo,
        BossChallengeSidebarInfo bossInfo,
        boolean showBuffInfo,
        List<ActiveBuff> activeBuffs
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
        clearSidebar(objective);
        List<String> buffLines = buildBuffLines(activeBuffs, showBuffInfo, bossInfo != null);
        int fixedLineCount = 8 + (bossInfo == null ? 0 : 5) + buffLines.size();
        boolean renderPerformance = showPerformanceInfo && SIDEBAR_LINE_LIMIT - fixedLineCount >= 2;

        List<String> lines = new ArrayList<>(SIDEBAR_LINE_LIMIT);
        lines.add(ColorCodeUtil.AQUA + "オンライン" + ColorCodeUtil.GRAY + ": " + ColorCodeUtil.WHITE
                + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        if (renderPerformance) {
            lines.add(tpsLegacyColor(tps) + "TPS(10S)" + ColorCodeUtil.GRAY + ": "
                    + ColorCodeUtil.WHITE + String.format("%.1f", tps));
            lines.add(pingLegacyColor(ping) + "通信遅延" + ColorCodeUtil.GRAY + ": " + ColorCodeUtil.WHITE + ping + "ms");
        }
        lines.add(ColorCodeUtil.BLUE + "ワールド" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.toLegacyText(worldName, "不明"));
        lines.add(ColorCodeUtil.GREEN + "地域" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.toLegacyText(regionName, "不明"));
        lines.add(ColorCodeUtil.GOLD + "地域レベル" + ColorCodeUtil.GRAY + ": "
                + "Lv." + ColorCodeUtil.YELLOW + Math.max(0, regionLevel));
        lines.add(buildSeparator("player"));
        lines.add(ColorCodeUtil.GOLD + "レベル" + ColorCodeUtil.GRAY + ": " + "Lv." + ColorCodeUtil.YELLOW + playerLevel);
        lines.add(buildExperienceBar("経験値", experienceProgress, ColorCodeUtil.GREEN));
        lines.add(ColorCodeUtil.DARK_AQUA + "クラス" + ColorCodeUtil.GRAY + ": " + className
                + ColorCodeUtil.GRAY + " Lv." + ColorCodeUtil.YELLOW + classLevel);
        lines.addAll(buffLines);
        if (bossInfo != null) {
            appendBossInfo(lines, bossInfo);
        }

        for (int index = 0; index < Math.min(lines.size(), SIDEBAR_LINE_LIMIT); index++) {
            objective.getScore(lines.get(index)).setScore(SIDEBAR_LINE_LIMIT - index);
        }
    }

    /**
     * Tabキー押下時のプレイヤーリストにMSPT・Pingをヘッダー/フッター表示します。
     *
     * @param player 対象プレイヤー
     * @param tps 直前10秒のTPS平均
     */
    public void renderTabList(Player player, double tps, boolean showPerformanceInfo) {
        int ping = player.getPing();
        Component header = Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(showPerformanceInfo
                ? Component.newline().append(Component.text("TPS(10S) ", NamedTextColor.GRAY))
                    .append(Component.text(String.format("%.1f", tps), tpsTextColor(tps)))
                : Component.empty())
            .build();
        Component footer = showPerformanceInfo
            ? Component.text().append(Component.text("通信遅延 ", NamedTextColor.GRAY))
                .append(Component.text(ping + "ms", pingTextColor(ping))).build()
            : Component.empty();
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    private void appendBossInfo(List<String> lines, BossChallengeSidebarInfo info) {
        lines.add(buildSeparator("boss"));
        lines.add(ColorCodeUtil.RED + "ボス" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.toLegacyText(info.bossDisplayName(), "ボス"));
        lines.add(ColorCodeUtil.RED + "デス" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.WHITE + info.deathCount() + "/" + info.deathLimit());
        lines.add(ColorCodeUtil.GOLD + "時間" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.WHITE + info.elapsedSeconds() + "/" + info.timeLimitSeconds() + "s");
        lines.add(ColorCodeUtil.LIGHT_PURPLE + "参加者" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.WHITE + String.join("、", info.participantNames()));
    }

    private List<String> buildBuffLines(List<ActiveBuff> activeBuffs, boolean showBuffInfo, boolean hasBossInfo) {
        if (!showBuffInfo || activeBuffs.isEmpty()) {
            return List.of();
        }

        int availableEntries = Math.max(0, SIDEBAR_LINE_LIMIT - 8 - (hasBossInfo ? 5 : 0) - 1);
        int displayCount = Math.min(Math.min(BUFF_DISPLAY_LIMIT, activeBuffs.size()), availableEntries);
        if (displayCount == 0) {
            return List.of();
        }

        List<String> lines = new ArrayList<>(displayCount + 1);
        lines.add(buildSeparator("buff"));
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < displayCount; index++) {
            ActiveBuff buff = activeBuffs.get(index);
            int hiddenCount = index == displayCount - 1 ? activeBuffs.size() - displayCount : 0;
            lines.add(formatBuffLine(buff, index + 1, hiddenCount, now));
        }
        return lines;
    }

    private String formatBuffLine(ActiveBuff buff, int position, int hiddenCount, LocalDateTime now) {
        String color = buff.getType().isDebuff() ? ColorCodeUtil.RED : ColorCodeUtil.GREEN;
        String displayName = ColorCodeUtil.toLegacyText(buff.getType().getDisplayName(), buff.getType().getId());
        long remainingSeconds = Math.max(0L, Duration.between(now, buff.getExpiresAt()).toSeconds());
        String overflow = hiddenCount > 0
                ? ColorCodeUtil.DARK_GRAY + " …ほか" + ColorCodeUtil.WHITE + hiddenCount + ColorCodeUtil.GRAY + "件"
                : "";
        return ColorCodeUtil.DARK_GRAY + position + ". " + color + ColorCodeUtil.BOLD + "◆ "
                + ColorCodeUtil.RESET + displayName + ColorCodeUtil.GRAY + " [" + ColorCodeUtil.YELLOW
                + formatRemaining(remainingSeconds) + ColorCodeUtil.GRAY + "]" + overflow;
    }

    private String formatRemaining(long remainingSeconds) {
        if (remainingSeconds < 60L) {
            return remainingSeconds + "s";
        }
        return String.format("%d:%02d", remainingSeconds / 60L, remainingSeconds % 60L);
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

    /**
     * AstralRecord が表示したサイドバーだけを解除します。
     *
     * @param player 対象プレイヤー
     */
    public void removeSidebar(Player player) {
        Objective objective = player.getScoreboard().getObjective(OBJECTIVE_NAME);
        if (objective != null) {
            objective.unregister();
        }
    }

    private void clearSidebar(Objective objective) {
        for (String entry : objective.getScoreboard().getEntries()) {
            var score = objective.getScore(entry);
            if (score.isScoreSet()) {
                score.resetScore();
            }
        }
    }

    private String buildExperienceBar(String label, double ratio, String fillColor) {
        int progressPercent = (int) Math.round(Math.clamp(ratio, 0.0D, 1.0D) * 100.0D);
        int filledLength = (progressPercent * SIDEBAR_BAR_LENGTH) / 100;

        StringBuilder bar = new StringBuilder();
        bar.append(ColorCodeUtil.DARK_GRAY).append(label).append(" ");
        bar.append(fillColor);
        bar.repeat(SIDEBAR_BAR_CHAR, Math.max(0, filledLength));
        bar.append(ColorCodeUtil.DARK_GRAY);
        bar.repeat(SIDEBAR_BAR_CHAR, Math.max(0, SIDEBAR_BAR_LENGTH - filledLength));
        bar.append(ColorCodeUtil.WHITE).append(" ").append(progressPercent).append("%");

        return bar.toString();
    }

    private String buildSeparator(String label) {
        return ColorCodeUtil.DARK_AQUA + "◈─── " + label + " ───◈";
    }

    private String tpsLegacyColor(double tps) {
        if (tps >= 19.0D) return ColorCodeUtil.GREEN;
        if (tps >= 16.0D) return ColorCodeUtil.YELLOW;
        return ColorCodeUtil.RED;
    }

    private String pingLegacyColor(int ping) {
        if (ping < 50) return ColorCodeUtil.GREEN;
        if (ping < 100) return ColorCodeUtil.YELLOW;
        return ColorCodeUtil.RED;
    }

    private NamedTextColor tpsTextColor(double tps) {
        if (tps >= 19.0D) return NamedTextColor.GREEN;
        if (tps >= 16.0D) return NamedTextColor.YELLOW;
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

    private Component shieldActionText(StatusSnapshot snapshot, ShieldRechargeState rechargeState) {
        double maxShield = snapshot.getMaxValue(StatusType.MAX_SHIELD);
        if (maxShield <= 0.0D) {
            return Component.empty();
        }
        if (rechargeState != null) {
            double remainingSeconds = rechargeState.remainingMs(System.currentTimeMillis()) / 1000.0D;
            return Component.text("  SH RC ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(String.format("%.1fs", remainingSeconds), NamedTextColor.WHITE));
        }
        return Component.text("  ", NamedTextColor.DARK_GRAY)
            .append(statText("SH", snapshot.getCurrentShield(), maxShield, NamedTextColor.BLUE));
    }

    private Component conditionActionText(Collection<ActiveCondition> activeConditions) {
        if (activeConditions.isEmpty()) {
            return Component.empty();
        }
        Component summary = Component.text("  ❖ ", NamedTextColor.DARK_GRAY);
        List<ActiveCondition> conditions = activeConditions.stream()
                .sorted(Comparator.comparingInt(condition -> conditionPriority(condition.type())))
                .limit(3)
                .toList();
        for (int index = 0; index < conditions.size(); index++) {
            if (index > 0) {
                summary = summary.append(Component.text("  ", NamedTextColor.DARK_GRAY));
            }
            summary = summary.append(conditionText(conditions.get(index)));
        }
        int hiddenCount = Math.max(0, activeConditions.size() - conditions.size());
        if (hiddenCount > 0) {
            summary = summary.append(Component.text("  +" + hiddenCount, NamedTextColor.GRAY));
        }
        return summary;
    }

    private Component conditionText(ActiveCondition condition) {
        long remainingSeconds = Math.max(0L,
                (condition.expiresAtMs() - System.currentTimeMillis() + 999L) / 1000L);
        NamedTextColor color = conditionColor(condition.type());
        return Component.text(conditionIcon(condition.type()) + " ", color, TextDecoration.BOLD)
                .append(Component.text(condition.type().displayName(), color, TextDecoration.BOLD))
                .append(Component.text(" " + remainingSeconds + "s", NamedTextColor.GRAY));
    }

    private String conditionIcon(ConditionType type) {
        return switch (type) {
            case BURNING -> "[火]";
            case FROZEN -> "[氷]";
            case CHILLED -> "[冷]";
            case SHOCKED -> "[雷]";
            case POISON -> "[毒]";
            case BLINDNESS -> "[盲]";
            case WEAKNESS -> "[衰]";
            case HEALING_INHIBITION -> "[阻]";
        };
    }

    private Component dpsActionText(double currentDps) {
        String dps = formatOneDecimal(currentDps);
        return Component.text("  DPS ", NamedTextColor.DARK_GRAY)
            .append(Component.text(dps, NamedTextColor.GOLD))
            .append(Component.text("/s", NamedTextColor.GRAY));
    }

    private String formatOneDecimal(double value) {
        String raw = String.format(Locale.ROOT, "%.1f", value);
        return raw.endsWith(".0") ? raw.substring(0, raw.length() - 2) : raw;
    }

    private NamedTextColor conditionColor(ConditionType type) {
        return switch (type) {
            case BURNING -> NamedTextColor.RED;
            case FROZEN -> NamedTextColor.AQUA;
            case CHILLED -> NamedTextColor.BLUE;
            case SHOCKED -> NamedTextColor.YELLOW;
            case POISON -> NamedTextColor.DARK_PURPLE;
            case BLINDNESS -> NamedTextColor.DARK_GRAY;
            case WEAKNESS -> NamedTextColor.GRAY;
            case HEALING_INHIBITION -> NamedTextColor.LIGHT_PURPLE;
        };
    }

    private int conditionPriority(ConditionType type) {
        return switch (type) {
            case FROZEN -> 0;
            case SHOCKED -> 1;
            case BURNING -> 2;
            case POISON -> 3;
            case CHILLED -> 4;
            case BLINDNESS -> 5;
            case WEAKNESS -> 6;
            case HEALING_INHIBITION -> 7;
        };
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

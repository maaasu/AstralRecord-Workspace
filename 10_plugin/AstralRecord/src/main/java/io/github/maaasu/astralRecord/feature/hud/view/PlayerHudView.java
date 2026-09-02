package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonSidebarInfo;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeWaitingStatus;
import io.github.maaasu.astralRecord.shared.challenge.ParticipantNameLineFormatter;
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
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PlayerHudView {
    private static final String OBJECTIVE_NAME = "astral_info";
    private static final int TRANSIENT_BAR_LENGTH = 28;
    private static final int SIDEBAR_BAR_LENGTH = 10;
    private static final int SIDEBAR_LINE_LIMIT = 15;
    private static final int SIDEBAR_BASE_LINE_COUNT = 9;
    private static final int SKILL_TREE_POINT_LINE_COUNT = 1;
    private static final int PERFORMANCE_LINE_COUNT = 2;
    private static final int BUFF_DISPLAY_LIMIT = 5;
    private static final String PARTICIPANT_CONTINUATION_PREFIX = "     ";
    private static final String SIDEBAR_BAR_CHAR = "▰";

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

    /**
     * HP、MP、ENG、Shield およびアカウント経験値を vanilla HUD へ描画します。
     *
     * @param player 対象プレイヤー
     * @param snapshot 現在のステータス
     * @param playerLevel アカウントプレイヤーレベル
     * @param experienceProgress 現在レベル内の経験値進捗（0.0-1.0）
     */
    public void renderBars(
        Player player,
        StatusSnapshot snapshot,
        int playerLevel,
        double experienceProgress
    ) {
        setHealthBar(player, ratio(snapshot.getCurrentHp(), snapshot.getMaxValue(StatusType.MAX_HEALTH)));
        player.setFoodLevel((int) Math.round(ratio(snapshot.getCurrentEnergy(), snapshot.getMaxValue(StatusType.MAX_ENERGY)) * 20.0D));
        player.setSaturation(0.0F);
        setManaBar(player, ratio(snapshot.getCurrentMp(), snapshot.getMaxValue(StatusType.MAX_MANA)));
        player.sendExperienceChange((float) ratio(experienceProgress, 1.0D), Math.max(0, playerLevel));
        setArmorBar(player, ratio(snapshot.getCurrentShield(), snapshot.getMaxValue(StatusType.MAX_SHIELD)));
    }

    /**
     * サイドバーを描画します。
     *
     * @param player 対象プレイヤー
     * @param mspt 現在のMSPT
     * @param playerLevel アカウント単位のプレイヤーレベル
     * @param classExperienceProgress 現在クラスレベル内の経験値進捗（0.0-1.0）
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
        double mspt,
        int playerLevel,
        double classExperienceProgress,
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
        renderSidebar(player, mspt, playerLevel, classExperienceProgress, classLevel, className,
                0L, null, 0, 0, worldName, regionName, regionLevel, showPerformanceInfo,
                bossInfo, null, showBuffInfo, activeBuffs);
    }

    /** Gold 所持量とスキルツリーの CP / PP を指定しない互換用サイドバー描画です。 */
    public void renderSidebar(
        Player player,
        double mspt,
        int playerLevel,
        double classExperienceProgress,
        int classLevel,
        String className,
        String worldName,
        String regionName,
        int regionLevel,
        boolean showPerformanceInfo,
        BossChallengeSidebarInfo bossInfo,
        DungeonSidebarInfo dungeonInfo,
        boolean showBuffInfo,
        List<ActiveBuff> activeBuffs
    ) {
        renderSidebar(
            player,
            mspt,
            playerLevel,
            classExperienceProgress,
            classLevel,
            className,
            0L,
            null,
            0,
            0,
            worldName,
            regionName,
            regionLevel,
            showPerformanceInfo,
            bossInfo,
            dungeonInfo,
            showBuffInfo,
            activeBuffs
        );
    }

    /** Gold 所持量を指定し、スキルツリーの CP / PP を指定しない互換用サイドバー描画です。 */
    public void renderSidebar(
        Player player,
        double mspt,
        int playerLevel,
        double classExperienceProgress,
        int classLevel,
        String className,
        long goldAmount,
        String worldName,
        String regionName,
        int regionLevel,
        boolean showPerformanceInfo,
        BossChallengeSidebarInfo bossInfo,
        DungeonSidebarInfo dungeonInfo,
        boolean showBuffInfo,
        List<ActiveBuff> activeBuffs
    ) {
        renderSidebar(
            player,
            mspt,
            playerLevel,
            classExperienceProgress,
            classLevel,
            className,
            goldAmount,
            null,
            0,
            0,
            worldName,
            regionName,
            regionLevel,
            showPerformanceInfo,
            bossInfo,
            dungeonInfo,
            showBuffInfo,
            activeBuffs
        );
    }

    /** Dungeon 情報を含めてサイドバーを描画します。 */
    public void renderSidebar(
        Player player,
        double mspt,
        int playerLevel,
        double classExperienceProgress,
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
            mspt,
            playerLevel,
            classExperienceProgress,
            classLevel,
            className,
            0L,
            null,
            0,
            0,
            worldName,
            regionName,
            regionLevel,
            showPerformanceInfo,
            bossInfo,
            null,
            false,
            List.of()
        );
    }

    /**
     * サイドバーを描画し、設定が有効な場合は獲得順のバフを最大5件表示します。
     * 表示行数が15行を超えないよう、挑戦名と参加者を優先して任意情報・バフ・性能情報を調整します。
     *
     * @param player 対象プレイヤー
     * @param mspt 現在のMSPT
     * @param playerLevel アカウント単位のプレイヤーレベル
     * @param classExperienceProgress 現在クラスレベル内の経験値進捗（0.0-1.0）
     * @param classLevel 現在のクラスレベル
     * @param className 現在のクラス表示名
     * @param goldAmount 現在のゴールド所持量
     * @param skillTreeClassPointLabel スキルツリーワールドで表示する現在クラスの CP 表示名。通常は null
     * @param availableClassPoints スキルツリーワールドで表示する現在クラスの未使用 CP
     * @param availablePassivePoints スキルツリーワールドで表示する未使用 PP
     * @param worldName 現在のワールド表示名
     * @param regionName 現在の地域表示名
     * @param regionLevel 現在の地域レベル
     * @param showPerformanceInfo MSPT・Ping を表示するか
     * @param bossInfo 挑戦中ボス情報。挑戦していない場合は null
     * @param showBuffInfo バフ情報を表示するか
     * @param activeBuffs 獲得順の有効バフ一覧
     */
    public void renderSidebar(
        Player player,
        double mspt,
        int playerLevel,
        double classExperienceProgress,
        int classLevel,
        String className,
        long goldAmount,
        @Nullable String skillTreeClassPointLabel,
        int availableClassPoints,
        int availablePassivePoints,
        String worldName,
        String regionName,
        int regionLevel,
        boolean showPerformanceInfo,
        BossChallengeSidebarInfo bossInfo,
        DungeonSidebarInfo dungeonInfo,
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
        int requiredChallengeLineCount = bossInfo != null
                ? bossInfo.requiredSidebarLineCount()
                : dungeonInfo != null ? dungeonInfo.requiredSidebarLineCount() : 0;
        boolean showSkillTreePoints = skillTreeClassPointLabel != null;
        int baseLineCount = SIDEBAR_BASE_LINE_COUNT
                + (showSkillTreePoints ? SKILL_TREE_POINT_LINE_COUNT : 0);
        List<String> buffLines = buildBuffLines(
                activeBuffs,
                showBuffInfo,
                requiredChallengeLineCount,
                baseLineCount
        );

        List<String> lines = new ArrayList<>(SIDEBAR_LINE_LIMIT);
        lines.add(ColorCodeUtil.AQUA + "オンライン" + ColorCodeUtil.GRAY + ": " + ColorCodeUtil.WHITE
                + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        lines.add(ColorCodeUtil.BLUE + "ワールド" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.toLegacyText(worldName, "不明"));
        lines.add(ColorCodeUtil.GREEN + "エリア" + ColorCodeUtil.GRAY + ": "
                + ColorCodeUtil.toLegacyText(regionName, "不明"));
        lines.add(ColorCodeUtil.GOLD + "エリアレベル" + ColorCodeUtil.GRAY + ": "
                + "Lv." + ColorCodeUtil.YELLOW + Math.max(0, regionLevel));
        lines.add(buildSeparator("player"));
        lines.add(ColorCodeUtil.GOLD + "レベル" + ColorCodeUtil.GRAY + ": " + "Lv." + ColorCodeUtil.YELLOW + playerLevel);
        lines.add(ColorCodeUtil.DARK_AQUA + "クラス" + ColorCodeUtil.GRAY + ": " + className
                + ColorCodeUtil.GRAY + " Lv." + ColorCodeUtil.YELLOW + classLevel);
        lines.add(buildExperienceBar("EXP", classExperienceProgress, ColorCodeUtil.AQUA));
        lines.add(ColorCodeUtil.GOLD + "Gold" + ColorCodeUtil.GRAY + ": " + ColorCodeUtil.WHITE
                + Math.max(0L, goldAmount) + ColorCodeUtil.YELLOW + ColorCodeUtil.BOLD + " G");
        if (showSkillTreePoints) {
            lines.add(ColorCodeUtil.AQUA + skillTreeClassPointLabel + ColorCodeUtil.GRAY + ": "
                    + ColorCodeUtil.WHITE + Math.max(0, availableClassPoints)
                    + ColorCodeUtil.GRAY + " / " + ColorCodeUtil.LIGHT_PURPLE
                    + SkillTreePointType.PASSIVE_POINT.displayName() + ColorCodeUtil.GRAY + ": "
                    + ColorCodeUtil.WHITE + Math.max(0, availablePassivePoints));
        }
        lines.addAll(buffLines);
        if (bossInfo != null) {
            appendBossInfo(lines, bossInfo);
        } else if (dungeonInfo != null) {
            appendDungeonInfo(lines, dungeonInfo);
        }

        if (showPerformanceInfo && SIDEBAR_LINE_LIMIT - lines.size() >= PERFORMANCE_LINE_COUNT) {
            lines.add(1, msptLegacyColor(mspt) + "MSPT" + ColorCodeUtil.GRAY + ": "
                    + ColorCodeUtil.WHITE + String.format("%.1f", mspt));
            lines.add(2, pingLegacyColor(ping) + "PING" + ColorCodeUtil.GRAY + ": "
                    + ColorCodeUtil.WHITE + ping + "ms");
        }

        for (int index = 0; index < Math.min(lines.size(), SIDEBAR_LINE_LIMIT); index++) {
            objective.getScore(lines.get(index)).setScore(SIDEBAR_LINE_LIMIT - index);
        }
    }

    /**
     * Tabキー押下時のプレイヤーリストにMSPT・Pingをヘッダー/フッター表示します。
     *
     * @param player 対象プレイヤー
     * @param mspt 現在のMSPT（平均値）
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
            ? Component.text().append(Component.text("通信遅延 ", NamedTextColor.GRAY))
                .append(Component.text(ping + "ms", pingTextColor(ping))).build()
            : Component.empty();
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    private void appendBossInfo(List<String> lines, BossChallengeSidebarInfo info) {
        List<SidebarLineCandidate> candidates = new ArrayList<>();
        addOptionalCandidate(candidates, buildSeparator("boss"), 0, 100);
        addRequiredCandidate(candidates,
                ColorCodeUtil.RED + "ボス" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.toLegacyText(info.bossDisplayName(), "ボス"),
                10);
        addWaitingStatusCandidate(candidates, info.waitingStatus(), 20);
        addOptionalCandidate(candidates,
                ColorCodeUtil.RED + "デス" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.WHITE + info.deathCount() + "/" + info.deathLimit(),
                30, 20);
        addOptionalCandidate(candidates,
                ColorCodeUtil.GOLD + "時間" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.WHITE + info.elapsedSeconds() + "/" + info.timeLimitSeconds() + "s",
                40, 30);
        addRequiredParticipantCandidates(
                candidates,
                formatParticipantInfoLines(info.participantNames(), info.waitingParticipantNames()),
                50
        );
        appendFittingChallengeLines(lines, candidates);
    }

    private void appendDungeonInfo(List<String> lines, DungeonSidebarInfo info) {
        List<SidebarLineCandidate> candidates = new ArrayList<>();
        addOptionalCandidate(candidates, buildSeparator("dungeon"), 0, 100);
        addRequiredCandidate(candidates,
                ColorCodeUtil.AQUA + "ダンジョン" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.toLegacyText(info.dungeonDisplayName(), "ダンジョン"),
                10);
        addWaitingStatusCandidate(candidates, info.waitingStatus(), 20);
        addOptionalCandidate(candidates,
                ColorCodeUtil.RED + "デス" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.WHITE + info.deathCount() + "/" + info.deathLimit(),
                30, 20);
        addOptionalCandidate(candidates,
                ColorCodeUtil.GOLD + "部屋" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.WHITE + info.clearedRooms() + "/" + info.totalRooms(),
                35, 25);
        if (info.timeLimitSeconds() != null) {
            addOptionalCandidate(candidates,
                    ColorCodeUtil.GOLD + "時間" + ColorCodeUtil.GRAY + ": "
                            + ColorCodeUtil.WHITE + info.elapsedSeconds() + "/" + info.timeLimitSeconds() + "s",
                    40, 30);
        }
        addRequiredParticipantCandidates(
                candidates,
                formatParticipantInfoLines(info.participantNames(), info.waitingParticipantNames()),
                50
        );
        addOptionalCandidate(candidates, info.returnRemainingSeconds() >= 0L
                ? ColorCodeUtil.YELLOW + "帰還まで" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.WHITE + info.returnRemainingSeconds() + "s"
                : ColorCodeUtil.GRAY + "攻略進行中", 60,
                info.returnRemainingSeconds() >= 0L ? 15 : 40);
        appendFittingChallengeLines(lines, candidates);
    }

    private void addWaitingStatusCandidate(
            List<SidebarLineCandidate> candidates,
            ChallengeWaitingStatus status,
            int order
    ) {
        if (!status.isVisible() || status.messageId() == null) {
            return;
        }
        addOptionalCandidate(candidates,
                ColorCodeUtil.YELLOW + "状態" + ColorCodeUtil.GRAY + ": "
                        + ColorCodeUtil.WHITE + PlayerMsgResource.getMessage(status.messageId().getId()),
                order,
                10);
    }

    private List<String> formatParticipantInfoLines(
            List<String> participantNames,
            Set<String> waitingParticipantNames
    ) {
        List<String> formattedLines = formatParticipantNames(participantNames, waitingParticipantNames);
        List<String> result = new ArrayList<>(formattedLines.size());
        for (int index = 0; index < formattedLines.size(); index++) {
            String prefix = index == 0
                    ? ColorCodeUtil.LIGHT_PURPLE + "参加者" + ColorCodeUtil.GRAY + ": "
                    : ColorCodeUtil.GRAY + PARTICIPANT_CONTINUATION_PREFIX + ColorCodeUtil.WHITE;
            result.add(prefix + formattedLines.get(index));
        }
        return result;
    }

    private List<String> formatParticipantNames(List<String> participantNames, Set<String> waitingParticipantNames) {
        return ParticipantNameLineFormatter.wrap(
                        participantNames,
                        ParticipantNameLineFormatter.MAX_SIDEBAR_PARTICIPANT_LINES
                ).stream()
                .map(line -> ColorCodeUtil.WHITE + String.join("、", line.stream()
                        .map(name -> {
                            String displayName = ColorCodeUtil.translateAlternateColorCodes(name);
                            return waitingParticipantNames.contains(name)
                                    ? ColorCodeUtil.GRAY + displayName + ColorCodeUtil.WHITE
                                    : displayName;
                        })
                        .toList()))
                .toList();
    }

    private void addRequiredCandidate(
            List<SidebarLineCandidate> candidates,
            String text,
            int order
    ) {
        candidates.add(new SidebarLineCandidate(text, order, 0, true));
    }

    private void addRequiredParticipantCandidates(
            List<SidebarLineCandidate> candidates,
            List<String> lines,
            int firstOrder
    ) {
        for (int index = 0; index < lines.size(); index++) {
            addRequiredCandidate(candidates, lines.get(index), firstOrder + index);
        }
    }

    private void addOptionalCandidate(
            List<SidebarLineCandidate> candidates,
            String text,
            int order,
            int priority
    ) {
        candidates.add(new SidebarLineCandidate(text, order, priority, false));
    }

    private void appendFittingChallengeLines(
            List<String> lines,
            List<SidebarLineCandidate> candidates
    ) {
        int availableLines = Math.max(0, SIDEBAR_LINE_LIMIT - lines.size());
        List<SidebarLineCandidate> selected = candidates.stream()
                .filter(SidebarLineCandidate::required)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int optionalLineCount = Math.max(0, availableLines - selected.size());
        candidates.stream()
                .filter(candidate -> !candidate.required())
                .sorted(Comparator.comparingInt(SidebarLineCandidate::priority)
                        .thenComparingInt(SidebarLineCandidate::order))
                .limit(optionalLineCount)
                .forEach(selected::add);
        selected.sort(Comparator.comparingInt(SidebarLineCandidate::order));
        for (SidebarLineCandidate candidate : selected) {
            if (lines.size() >= SIDEBAR_LINE_LIMIT) {
                break;
            }
            lines.add(candidate.text());
        }
    }

    private record SidebarLineCandidate(String text, int order, int priority, boolean required) {
    }

    private List<String> buildBuffLines(
            List<ActiveBuff> activeBuffs,
            boolean showBuffInfo,
            int challengeLineCount,
            int baseLineCount
    ) {
        if (!showBuffInfo || activeBuffs.isEmpty()) {
            return List.of();
        }

        int availableEntries = Math.max(
                0,
                SIDEBAR_LINE_LIMIT - baseLineCount - challengeLineCount - 1
        );
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

    private void setManaBar(Player player, double ratio) {
        var maxAbsorption = player.getAttribute(Attribute.MAX_ABSORPTION);
        if (maxAbsorption != null && maxAbsorption.getBaseValue() != 20.0D) {
            maxAbsorption.setBaseValue(20.0D);
        }
        double absorption = Math.clamp(ratio * 20.0D, 0.0D, 20.0D);
        if (Double.compare(player.getAbsorptionAmount(), absorption) != 0) {
            player.setAbsorptionAmount(absorption);
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

    private Component shieldActionText(StatusSnapshot snapshot, ShieldRechargeState rechargeState) {
        double maxShield = snapshot.getMaxValue(StatusType.MAX_SHIELD);
        if (maxShield <= 0.0D) {
            return Component.empty();
        }
        Component shield = Component.text("  ", NamedTextColor.DARK_GRAY)
            .append(statText("SH", snapshot.getCurrentShield(), maxShield, NamedTextColor.BLUE));
        if (rechargeState != null) {
            double remainingSeconds = rechargeState.remainingMs(System.currentTimeMillis()) / 1000.0D;
            return shield
                .append(Component.text(" (RC ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(String.format("%.1fs", remainingSeconds), NamedTextColor.WHITE))
                .append(Component.text(")", NamedTextColor.GOLD, TextDecoration.BOLD));
        }
        return shield;
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

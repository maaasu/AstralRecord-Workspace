package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.playerclass.view.ClassLevelDisplay;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** アカウントとクラスの情報をプレイヤー本人だけに表示します。 */
public final class PlayerProgressHudView {
    private static final Component SEPARATOR = Component.text("  |  ", NamedTextColor.GRAY);
    private final Map<UUID, ProgressBars> visibleBars = new HashMap<>();

    /**
     * 画面上部のアカウント行とクラス行を表示・更新します。メインスレッド専用です。
     *
     * @param player 表示対象
     * @param playerLevel 現在のプレイヤーレベル
     * @param rebirthOriginalLevel 転生前レベル。転生中でなければ null
     * @param playerExperienceProgress プレイヤーレベル内の経験値進捗
     * @param goldAmount 所持Gold
     * @param className 現在のクラス表示名
     * @param classLevel 現在のクラスレベル
     * @param classLevelMax 現在クラスが最大レベルへ到達済みか
     * @param classExperienceProgress クラスレベル内の経験値進捗
     * @param showSkillTreePoints スキルツリーワールド用の残高を表示するか
     * @param classPoints 現在クラスの未使用CP
     * @param passivePoints 未使用PP
     */
    public void render(
        Player player,
        int playerLevel,
        @Nullable Integer rebirthOriginalLevel,
        double playerExperienceProgress,
        long goldAmount,
        String className,
        int classLevel,
        boolean classLevelMax,
        double classExperienceProgress,
        boolean showSkillTreePoints,
        int classPoints,
        int passivePoints
    ) {
        Component playerTitle = Component.text("Lv.", NamedTextColor.GOLD)
            .append(Component.text(playerLevel,
                rebirthOriginalLevel == null ? NamedTextColor.YELLOW : NamedTextColor.AQUA));
        if (rebirthOriginalLevel != null) {
            playerTitle = playerTitle.append(Component.text(" (" + rebirthOriginalLevel + ")", NamedTextColor.GRAY));
        }
        playerTitle = playerTitle.append(SEPARATOR)
            .append(Component.text("Gold ", NamedTextColor.GOLD))
            .append(Component.text(String.format(Locale.ROOT, "%,d", Math.max(0L, goldAmount)), NamedTextColor.WHITE))
            .append(Component.text(" G", NamedTextColor.YELLOW));

        float playerProgress = (float) Math.clamp(playerExperienceProgress, 0.0D, 1.0D);
        float classProgress = (float) Math.clamp(classExperienceProgress, 0.0D, 1.0D);
        Component classTitle = Component.empty()
            .append(ColorCodeUtil.toComponent(className, "不明", NamedTextColor.AQUA))
            .append(Component.space())
            .append(ClassLevelDisplay.component(classLevel, classLevelMax, NamedTextColor.GRAY, NamedTextColor.YELLOW))
            .append(SEPARATOR)
            .append(Component.text("EXP " + Math.round(classProgress * 100.0F) + "%", NamedTextColor.AQUA));
        if (showSkillTreePoints) {
            classTitle = classTitle.append(SEPARATOR)
                .append(Component.text(SkillTreePointType.CLASS_POINT.displayName() + " ", NamedTextColor.AQUA))
                .append(Component.text(Math.max(0, classPoints), NamedTextColor.WHITE))
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(SkillTreePointType.PASSIVE_POINT.displayName() + " ", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(Math.max(0, passivePoints), NamedTextColor.WHITE));
        }

        ProgressBars bars = visibleBars.get(player.getUniqueId());
        if (bars == null) {
            BossBar accountBar = BossBar.bossBar(playerTitle, playerProgress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            BossBar classBar = BossBar.bossBar(classTitle, classProgress, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            player.showBossBar(accountBar);
            player.showBossBar(classBar);
            visibleBars.put(player.getUniqueId(), new ProgressBars(accountBar, classBar));
        } else {
            bars.account().name(playerTitle).progress(playerProgress);
            bars.playerClass().name(classTitle).progress(classProgress);
        }
    }

    /**
     * プレイヤー本人の成長情報の2行を解除します。
     * @param player 表示対象
     */
    public void hide(Player player) {
        ProgressBars bars = visibleBars.remove(player.getUniqueId());
        if (bars != null) {
            player.hideBossBar(bars.account());
            player.hideBossBar(bars.playerClass());
        }
    }

    /** プラグイン停止時に全プレイヤーの成長情報を解除します。 */
    public void clear() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player);
        }
        visibleBars.clear();
    }

    private record ProgressBars(BossBar account, BossBar playerClass) {
    }
}

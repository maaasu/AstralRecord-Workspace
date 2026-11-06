package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.playerclass.view.ClassLevelDisplay;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Java版と統合版で共用する個人情報HUDの2行です。 */
public record ProfileHudText(Component playerLine, Component classLine) {
    private static final Component GAP = Component.text("   ");
    private static final Component LEVEL = Component.text("\ue940 ", NamedTextColor.AQUA);
    private static final Component GOLD = Component.text("\ue941 ", NamedTextColor.GOLD);
    private static final Component CLASS = Component.text("\ue942 ", NamedTextColor.LIGHT_PURPLE);
    private static final Component EXPERIENCE = Component.text("\ue943 ", NamedTextColor.AQUA);
    private static final Component POINTS = Component.text("\ue944 ", NamedTextColor.YELLOW);

    /**
     * 個人情報をレベル・所持Goldと、クラス・進捗の2行へ整形します。
     * @param playerLevel 現在のプレイヤーレベル
     * @param originalLevel 転生前レベル。転生中でなければnull
     * @param goldAmount 所持Gold
     * @param className クラスの短縮表示名
     * @param classLevel 現在のクラスレベル
     * @param classLevelMax クラスが最大レベルに達した場合true
     * @param classProgress 現在のクラスレベル内の経験値進捗
     * @param showPoints スキルツリーワールドの未使用ポイントを表示する場合true
     * @param classPoints 現在クラスの未使用CP
     * @param passivePoints 未使用PP
     * @return 本人へ表示する個人情報の2行
     */
    public static ProfileHudText create(
            int playerLevel,
            @Nullable Integer originalLevel,
            long goldAmount,
            String className,
            int classLevel,
            boolean classLevelMax,
            double classProgress,
            boolean showPoints,
            int classPoints,
            int passivePoints
    ) {
        Component level = Component.text("Lv." + playerLevel, NamedTextColor.WHITE);
        if (originalLevel != null) {
            level = level.append(Component.text(" (" + originalLevel + ")", NamedTextColor.GRAY));
        }
        Component playerLine = Component.empty()
                .append(LEVEL).append(level).append(GAP)
                .append(GOLD)
                .append(Component.text(String.format(Locale.ROOT, "%,d", Math.max(0L, goldAmount)),
                        NamedTextColor.GOLD))
                .append(Component.text(" G", NamedTextColor.YELLOW));
        int percent = (int) Math.round(Math.clamp(classProgress, 0.0D, 1.0D) * 100.0D);
        Component classLine = Component.empty()
                .append(CLASS)
                .append(Component.text(className + " ", NamedTextColor.WHITE))
                .append(ClassLevelDisplay.component(classLevel, classLevelMax, NamedTextColor.WHITE))
                .append(GAP).append(EXPERIENCE)
                .append(Component.text(percent + "%", NamedTextColor.AQUA));
        if (showPoints) {
            classLine = classLine.append(GAP).append(POINTS)
                    .append(Component.text(SkillTreePointType.CLASS_POINT.displayName() + " "
                            + Math.max(0, classPoints), NamedTextColor.AQUA))
                    .append(Component.text(" / ", NamedTextColor.GRAY))
                    .append(Component.text(SkillTreePointType.PASSIVE_POINT.displayName() + " "
                            + Math.max(0, passivePoints), NamedTextColor.LIGHT_PURPLE));
        }
        return new ProfileHudText(playerLine, classLine);
    }

    /** @return 統合版のTIPに渡す色付きの2行 */
    public String bedrockMessage() {
        return LegacyComponentSerializer.legacySection().serialize(playerLine.append(Component.newline())
                .append(classLine));
    }
}

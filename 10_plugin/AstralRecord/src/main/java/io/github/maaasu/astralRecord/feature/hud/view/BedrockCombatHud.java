package io.github.maaasu.astralRecord.feature.hud.view;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** 統合版の通常アクションバーを、画像と数値のHUDへ渡す形式に変換します。 */
final class BedrockCombatHud {
    private static final String PREFIX = "arhud2|";
    private static final int PAIR_WIDTH = 12;
    private static final int DETAIL_WIDTH = 222;

    private BedrockCombatHud() {}

    /**
     * @return 各リソースの現在値と最大値、通常の追加表示を含むデータ。画像HUDの幅に収まらなければnull
     */
    static @Nullable Component encode(
            double hp, double maxHp, double mp, double maxMp, double energy, double maxEnergy, Component extra
    ) {
        String hpPair = pair(hp, maxHp);
        String mpPair = pair(mp, maxMp);
        String energyPair = pair(energy, maxEnergy);
        if (hpPair.length() > PAIR_WIDTH || mpPair.length() > PAIR_WIDTH || energyPair.length() > PAIR_WIDTH) {
            return null;
        }
        String plainDetails = PlainTextComponentSerializer.plainText().serialize(extra);
        if (plainDetails.indexOf('\n') >= 0 || plainDetails.codePoints()
                .map(codePoint -> codePoint == ' ' ? 4 : codePoint < 128 ? 7 : 11)
                .sum() > DETAIL_WIDTH) {
            return null;
        }
        String additionalText = LegacyComponentSerializer.legacySection().serialize(extra);
        return Component.text(PREFIX + "|x|" + pad(hpPair) + pad(mpPair) + pad(energyPair) + additionalText);
    }

    private static String pair(double current, double max) {
        return String.format(Locale.ROOT, "%.0f/%.0f", current, max);
    }

    /** 数値欄の幅と埋め文字はBedrockパックのUI定義に対応します。 */
    private static String pad(String pair) {
        return pair + "~".repeat(PAIR_WIDTH - pair.length());
    }
}

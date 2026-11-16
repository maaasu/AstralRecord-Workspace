package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.skill.model.ActionRingCooldownEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** ActionBarの幅を変えずに画面左下のアクションリングへ字形を送ります。 */
public final class ActionRingCooldownGlyphs {
    private static final Key FONT = Key.key("astralrecord", "action_ring_cooldown");
    private static final int RED_MARKER = 226;
    private static final int END_OF_RING = 8;
    private static final int START_GLYPH = 0xE100;
    private static final int SPACER = 0xE118;
    private static final int READY_GLYPH = 11;
    private static final int HUB_GLYPH = 12;
    private static final int FIRST_DIGIT = 13;
    private static final int MANY_SECONDS = 23;
    private static final int FRACTION_STEPS = 10;

    private ActionRingCooldownGlyphs() {
    }

    /**
     * 有効な装備枠だけを元の角度で描画する文字列を組み立てます。
     * 管理パックの専用版が適用されていると確認できた接続にのみ送信します。
     *
     * @param entries 装備済みのリング枠と、1 tick単位で得た残り時間
     * @return 既存のActionBarの幅を変えないオーバーレイ
     */
    public static @NotNull Component render(@NotNull List<ActionRingCooldownEntry> entries) {
        TextComponent.Builder result = HudOverlayGlyphs.builder();
        int seen = 0;
        for (ActionRingCooldownEntry entry : entries) {
            int slot = entry.index();
            if (slot < 0 || slot >= SkillBindPreset.ACTION_RING_SLOT_COUNT || (seen & (1 << slot)) != 0) {
                continue;
            }
            if (seen == 0) {
                append(result, HUB_GLYPH, END_OF_RING, 0);
            }
            seen |= 1 << slot;
            if (entry.remainingTicks() <= 0) {
                append(result, entry.available() ? READY_GLYPH : 0, slot, 0);
                continue;
            }
            long duration = Math.max(entry.totalCooldownTicks(), entry.remainingTicks());
            int step = Math.clamp((int) Math.round(FRACTION_STEPS * (entry.remainingTicks() / (double) duration)),
                1, FRACTION_STEPS);
            append(result, step, slot, 0);
            long seconds = 1 + (entry.remainingTicks() - 1) / 20;
            if (seconds >= 100) {
                append(result, MANY_SECONDS, slot, 1);
            } else if (seconds >= 10) {
                append(result, FIRST_DIGIT + (int) (seconds / 10), slot, 2);
                append(result, FIRST_DIGIT + (int) (seconds % 10), slot, 3);
            } else {
                append(result, FIRST_DIGIT + (int) seconds, slot, 1);
            }
        }
        return seen == 0 ? Component.empty() : result.build();
    }

    /**
     * 色へ枠番号と数字位置を符号化し、幅15のビットマップを負の空白で相殺します。
     *
     * @param result 描画文字列
     * @param glyph フォント内の字形番号
     * @param slot 上から時計回りの枠。8は中央飾り
     * @param layer 台座または数字の位置
     */
    private static void append(TextComponent.Builder result, int glyph, int slot, int layer) {
        result.append(Component.text(String.valueOf((char) (START_GLYPH + glyph)))
            .font(FONT).color(TextColor.color(RED_MARKER, slot, layer)));
        result.append(Component.text(String.valueOf((char) SPACER)).font(FONT).color(TextColor.color(0xFFFFFF)));
    }
}

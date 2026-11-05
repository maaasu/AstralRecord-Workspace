package io.github.maaasu.astralRecord.shared.display;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.stream.IntStream;

/** Java版の combat_hud フォントに収録する字形です。 */
public final class CombatHudGlyphs {
    private static final Key FONT = Key.key("astralrecord", "combat_hud");
    public static final Component HEALTH = glyph(0xE900);
    public static final Component MANA = glyph(0xE901);
    public static final Component ENERGY = glyph(0xE902);
    static final Component DIVIDER = glyph(0xE923);
    private static final List<Component> METERS = glyphRange(0xE910, 11);
    private static final List<Component> CRESTS = glyphRange(0xE920, 3);

    private CombatHudGlyphs() {}

    /**
     * @param fill 0〜10の充填段階
     * @return 着色可能なゲージ
     */
    public static Component meter(int fill) {
        return METERS.get(fill);
    }

    /**
     * @param frame 0〜2の登場フレーム
     * @return 討伐リザルトの紋章
     */
    static Component crest(int frame) {
        return CRESTS.get(frame);
    }

    private static List<Component> glyphRange(int first, int count) {
        return IntStream.range(first, first + count).mapToObj(CombatHudGlyphs::glyph).toList();
    }

    private static Component glyph(int codePoint) {
        return Component.text(Character.toString(codePoint), NamedTextColor.WHITE).font(FONT);
    }
}

package io.github.maaasu.astralrecordgeyser;

import java.util.Locale;
import java.util.UUID;

/** PaperがBedrock向けのTextDisplay spawnに載せるダメージ表示情報。 */
record DamageNumber(long amount, int style, int size, boolean sidecar) {
    static final int MAX_GLYPHS = 17;
    static final int BLANK = 14;
    private static final long UUID_TAG = 0xa57ad00d00000000L;
    private static final long AMOUNT_MASK = (1L << 40) - 1;
    private static final long SIDECAR = 1L << 52;
    private static final long VALID_BITS = AMOUNT_MASK | (15L << 48) | SIDECAR;

    static DamageNumber decode(UUID uuid, int entityId) {
        if (uuid == null || (uuid.getMostSignificantBits() & 0xffffffff00000000L) != UUID_TAG) {
            return null;
        }
        long bits = uuid.getLeastSignificantBits();
        long amount = bits & AMOUNT_MASK;
        int style = (int) ((bits >>> 48) & 3);
        int size = (int) ((bits >>> 50) & 3);
        boolean sidecar = (bits & SIDECAR) != 0;
        int originalId = (int) uuid.getMostSignificantBits();
        if ((bits & ~VALID_BITS) != 0 || amount > 999_999_999_999L || style > 2 || size > 2
            || sidecar == (originalId == entityId)) {
            return null;
        }
        return new DamageNumber(amount, style, size, sidecar);
    }

    float scale() {
        return switch (size) {
            case 0 -> 0.75f;
            case 2 -> 1.35f;
            default -> 1f;
        };
    }

    int[] glyphs() {
        String digits = String.format(Locale.ROOT, "%,d", amount);
        int prefixLength = style == 2 ? 2 : 1;
        int[] glyphs = new int[prefixLength + digits.length()];
        glyphs[0] = 11 + style;
        if (prefixLength == 2) glyphs[1] = 11;
        for (int i = 0; i < digits.length(); i++) {
            char digit = digits.charAt(i);
            glyphs[prefixLength + i] = digit == ',' ? 10 : digit - '0';
        }
        return glyphs;
    }
}

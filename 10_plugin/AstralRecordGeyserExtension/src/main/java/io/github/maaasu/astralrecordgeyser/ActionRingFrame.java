package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;

/** Paper の SkillActionRingDisplay が送る輪郭の初期状態と差分です。 */
record ActionRingFrame(int targetId, boolean update, float yaw, float pitch, float roll,
                       float size, int selected, int cooldowns) {
    private static final long TAG = 0xa57a710900000000L;
    private static final long ICON_TAG = 0xa57a710a00000000L;
    // 同じ半径・テクスチャ比を使う Paper の OUTLINE_SIZE。
    private static final float OUTLINE_SIZE = 1.12f * 128f / 42f;

    static ActionRingFrame decode(UUID uuid, int entityId) {
        if (uuid == null || (uuid.getMostSignificantBits() & 0xffffffff00000000L) != TAG) {
            return null;
        }
        long bits = uuid.getLeastSignificantBits();
        int targetId = (int) uuid.getMostSignificantBits();
        int selected = (int) ((bits >>> 48) & 0xf);
        float yaw = angle(bits, 0, 13);
        float pitch = angle(bits, 13, 12);
        float roll = angle(bits, 25, 13);
        float size = ((bits >>> 38) & 0x3ff) / 128f;
        if (targetId <= 0 || (bits >>> 60) != 0 || selected > 8
            || Math.abs(yaw) > 180 || Math.abs(pitch) > 90 || Math.abs(roll) > 180 || size > 4) {
            return null;
        }
        int cooldowns = (int) ((bits >>> 52) & 0xff);
        return new ActionRingFrame(targetId, entityId != targetId, yaw, pitch, roll, size, selected, cooldowns);
    }

    private static float angle(long bits, int shift, int width) {
        int value = (int) (bits >>> shift);
        int signShift = Integer.SIZE - width;
        return ((value << signShift) >> signShift) / 16f;
    }

    static boolean isIcon(UUID uuid) {
        return uuid != null && (uuid.getMostSignificantBits() & 0xffffffff00000000L) == ICON_TAG
            && (int) uuid.getMostSignificantBits() > 0 && uuid.getLeastSignificantBits() >= 0
            && uuid.getLeastSignificantBits() < 8;
    }

    UUID iconUuid(int slot) {
        return new UUID(ICON_TAG | Integer.toUnsignedLong(targetId), slot);
    }

    float iconScale(int slot) {
        float scale = 1f;
        if (selected == slot + 1) {
            scale = 1.12f;
        } else if ((cooldowns & (1 << slot)) != 0) {
            scale = 0.8f;
        }
        return scale * Math.min(1f, size / OUTLINE_SIZE);
    }
}

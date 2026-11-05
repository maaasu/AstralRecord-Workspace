package io.github.maaasu.astralRecord.feature.skill.service;

import org.bukkit.util.Vector;

import java.util.UUID;

/** 統合版の輪郭の表示状態を、閲覧者専用 TextDisplay の UUID へ載せます。 */
final class ActionRingFrame {
    private static final long TAG = 0xa57a710900000000L;
    private static final long ICON_TAG = 0xa57a710a00000000L;
    private static final long PANEL_ICONS_TAG = 0xa57a710b00000000L;
    private static final long PANEL_GAUGES_TAG = 0xa57a710c00000000L;
    private static final long FALLBACK_TAG = 0xa57a710d00000000L;
    private static final int ANGLE_PRECISION = 16;
    private static final int SIZE_PRECISION = 128;

    private ActionRingFrame() {
    }

    /**
     * 既存のリング面の向き、倍率、選択・CD 状態を Geyser 拡張と共有する形式へ変換します。
     *
     * @param normal 視点からリングへ向かう単位法線
     * @param up リング面の上方向
     * @param size モデルの表示幅（ブロック単位）
     * @param selected 1〜8 の選択番号。0 は選択なし
     * @param cooldowns クールダウン中の枠を表すビットマスク
     * @return パック側で使用する表示状態
     */
    static long pack(Vector normal, Vector up, float size, int selected, int cooldowns) {
        double yaw = Math.atan2(-normal.getX(), normal.getZ());
        double pitch = Math.atan2(-normal.getY(), Math.hypot(normal.getX(), normal.getZ()));
        Vector levelRight = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        double roll = Math.atan2(up.dot(levelRight), up.dot(levelRight.clone().crossProduct(normal)));
        // 13 bit の符号付き yaw、12 bit の pitch、13 bit の roll に続いて倍率と枠状態を格納します。
        return angle(yaw, 0x1fff) | (angle(pitch, 0xfff) << 13) | (angle(roll, 0x1fff) << 25)
            | ((long) Math.round(size * SIZE_PRECISION) << 38) | ((long) selected << 48)
            | ((long) cooldowns << 52);
    }

    static UUID marker(int targetId, long state) {
        return new UUID(TAG | Integer.toUnsignedLong(targetId), state);
    }

    static UUID icon(int targetId, int slot) {
        return new UUID(ICON_TAG | Integer.toUnsignedLong(targetId), slot);
    }

    static UUID panelIcons(int targetId, long icons) {
        return new UUID(PANEL_ICONS_TAG | Integer.toUnsignedLong(targetId), icons);
    }

    static UUID panelGauges(int targetId, long gauges) {
        return new UUID(PANEL_GAUGES_TAG | Integer.toUnsignedLong(targetId), gauges);
    }

    static UUID fallback(int targetId, int part) {
        return new UUID(FALLBACK_TAG | Integer.toUnsignedLong(targetId), part);
    }

    private static long angle(double radians, int mask) {
        return Math.round(Math.toDegrees(radians) * ANGLE_PRECISION) & mask;
    }
}

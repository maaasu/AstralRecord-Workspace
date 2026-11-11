package io.github.maaasu.astralRecord.feature.boss.service;

/** ボスの姿勢・無敵・サーバー上の向きを、Geyser と共有する通知値へまとめます。 */
final class BossBedrockMotion {
    private BossBedrockMotion() { }

    /**
     * @param motion 姿勢番号（0～255） @param invulnerable 無敵状態 @param yaw Java本体のyaw（度）
     * @return 下位8bitが姿勢、bit8が無敵、bit9～20がyawの十分の一度値+1。yawの0は旧通知です
     */
    static long state(int motion, boolean invulnerable, float yaw) {
        // 先に一周へ正規化し、大きなyawでも丸め時の整数オーバーフローを避ける。
        int angle = Float.isFinite(yaw) ? Math.floorMod(Math.round((yaw % 360.0F) * 10.0F), 3600) + 1 : 0;
        return (motion & 0xffL) | (invulnerable ? 0x100L : 0L) | ((long) angle << 9);
    }

    /** @param entityId 戦闘実体ID @param state 姿勢・無敵・yaw @return UUIDの下位64bit */
    static long payload(int entityId, long state) {
        return Integer.toUnsignedLong(entityId) | (state << 32);
    }
}

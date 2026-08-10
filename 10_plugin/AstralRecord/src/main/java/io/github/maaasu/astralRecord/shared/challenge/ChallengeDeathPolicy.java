package io.github.maaasu.astralRecord.shared.challenge;

/** Boss と Dungeon が共有するパーティー死亡許容回数の境界判定です。 */
public final class ChallengeDeathPolicy {
    private ChallengeDeathPolicy() {
    }

    /**
     * 設定回数までは死亡可能とし、次の死亡で終了するかを返します。
     *
     * @param deathCount 現在の共有死亡回数
     * @param deathLimit 設定された死亡許容回数
     * @return 共有死亡回数が許容回数を超えた場合 {@code true}
     */
    public static boolean isExceeded(int deathCount, int deathLimit) {
        return Math.max(0, deathCount) > Math.max(0, deathLimit);
    }
}

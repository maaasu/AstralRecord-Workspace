package io.github.maaasu.astralRecord.shared.effect;

/** 最大密度の候補点から、設定に比例する有限個の描画点を選びます。 */
final class ParticlePointSampling {

    static final double MAX_DENSITY = 2.0D;
    static final int MAX_POINTS = 4096;

    private ParticlePointSampling() { }

    /**
     * 最大密度の点群から採用する点数を計算します。非表示以外では最小一点を残します。
     * @param candidates 重複を除いた候補点数
     * @param density 標準を1とする密度。非有限値・負値は非表示
     * @return 0から4096まで、かつ候補点数以下の採用数
     */
    static int count(int candidates, double density) {
        if (candidates <= 0 || !Double.isFinite(density) || density <= 0.0D) {
            return 0;
        }
        return Math.min(Math.min(candidates, MAX_POINTS),
                Math.max(1, (int) Math.round(candidates * Math.min(MAX_DENSITY, density) / MAX_DENSITY)));
    }

    /**
     * 候補点の順番を保ちながら、両端を含む等間隔のインデックスを返します。
     * @param ordinal 採用点の番号。0以上selected未満
     * @param candidates 候補点数。1以上
     * @param selected 採用点数。1以上candidates以下
     * @return 候補点群内のインデックス
     */
    static int index(int ordinal, int candidates, int selected) {
        return selected == 1 ? candidates / 2
                : (int) Math.round((double) ordinal * (candidates - 1) / (selected - 1));
    }
}

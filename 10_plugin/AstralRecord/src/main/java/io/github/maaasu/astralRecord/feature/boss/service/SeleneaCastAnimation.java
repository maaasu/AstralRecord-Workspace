package io.github.maaasu.astralRecord.feature.boss.service;

/** サーバー実体を持たず、3冊の魔導書の位置・姿勢だけを決定する演出計算です。 */
final class SeleneaCastAnimation {
    static final int BOOK_COUNT = 3;

    /** 攻撃の詠唱、封頁、解呪後の開放、無害な休止を区別します。 */
    enum Mood { INTRO, CAST, SEALS, EXPOSURE, RECOVERY }

    /** 中心からの移動、Euler回転、等方scale。角度はradianです。 */
    record Pose(double x, double y, double z, float yaw, float pitch, float roll, float scale) { }

    private SeleneaCastAnimation() { }

    /**
     * 共通時計を基準に、波を切り替えても周回位相を巻き戻さない3冊の姿勢を返します。
     * @param mood 呪文の状態 @param index 0以上3未満の本番号 @param tick 共通tick時計
     * @param progress 詠唱率 @param release 最後20tickの解放率（無害状態は0）
     * @return 有限な中心相対位置と姿勢。表示Entityの生成・更新は行いません
     */
    static Pose pose(Mood mood, int index, long tick, double progress, double release) {
        if (index < 0 || index >= BOOK_COUNT) throw new IllegalArgumentException("book index");
        double angle = tick * 0.025D + index * Math.PI * 2.0D / BOOK_COUNT;
        double charge = Math.clamp(progress, 0.0D, 1.0D);
        double open = Math.clamp(release, 0.0D, 1.0D);
        double radius, height;
        float pitch, scale;
        switch (mood) {
            case INTRO -> { radius = 1.7D; height = 2.7D; pitch = 0.2F; scale = 0.7F; }
            case SEALS -> { radius = 1.15D; height = 3.0D; pitch = -1.1F; scale = 0.7F; }
            case EXPOSURE -> { radius = 2.1D; height = 1.7D; pitch = 0.6F; scale = 0.8F; }
            case RECOVERY -> { radius = 1.3D; height = 2.15D; pitch = 0.2F; scale = 0.65F; }
            default -> {
                radius = 1.5D - 0.3D * charge + 0.7D * open;
                height = 2.4D + 0.35D * open;
                pitch = (float) (-0.3D - 0.4D * open);
                scale = (float) (0.7D + 0.15D * open);
            }
        }
        double bob = mood == Mood.CAST ? 0.25D : 0.1D;
        return new Pose(Math.cos(angle) * radius, height + Math.sin(angle * 2.0D) * bob,
            Math.sin(angle) * radius, (float) (-angle + Math.PI / 2.0D), pitch,
            (float) (Math.sin(angle) * (mood == Mood.EXPOSURE ? 0.3D : 0.1D)), scale);
    }

    /**
     * 次の5tick補間が着弾tickで床へ届く落下率を返します。
     * @param tick 現在tick @param ends 着弾tick @return 0から1の落下率
     */
    static double release(long tick, long ends) {
        return Math.clamp((tick + 5L - (ends - 20L)) / 20.0D, 0.0D, 1.0D);
    }
}

package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 苔むしたゾンビの技が要求する攻撃姿勢を、外観表示へ受け渡します。
 *
 * <p>Bukkit メインスレッドだけで読み書きします。姿勢は期限付きで、期限切れ・未設定の個体は
 * {@link Pose#NONE} として通常の待機・歩行表示に戻ります。当たり判定や AI には影響しません。</p>
 */
public final class MossZombieMotion {

    /** 外観表示が使う攻撃姿勢です。番号は統合版の {@code astralrecord:motion} と共有します。 */
    public enum Pose {
        /** 技を使っていない状態。待機・歩行の通常表示を使います。 */
        NONE(0, null),
        /** 両腕を頭上へ振り上げる叩きつけの予兆です。 */
        RAISE(1, "raise"),
        /** 両腕を前方の地面へ振り下ろした発動姿勢です。 */
        SLAM(2, "slam"),
        /** 両腕を左右へ広げ、胞子を吐く咆哮の姿勢です。 */
        ROAR(3, "roar");

        private final int bedrock;
        private final @Nullable String javaCase;

        Pose(int bedrock, @Nullable String javaCase) {
            this.bedrock = bedrock;
            this.javaCase = javaCase;
        }

        /** @return 統合版へ送る姿勢番号（0～3） */
        public int bedrock() {
            return bedrock;
        }

        /** @return Java 版アイテム定義の {@code custom_model_data} 文字列。通常姿勢は null */
        public @Nullable String javaCase() {
            return javaCase;
        }
    }

    private final Map<UUID, Entry> poses = new HashMap<>();

    /**
     * 指定期間だけ攻撃姿勢を表示させます。
     *
     * @param mobInstanceId 対象 Mob
     * @param pose 表示する姿勢。{@link Pose#NONE} は解除と同じです
     * @param durationTicks 表示期間（1以上）
     */
    public void set(@NotNull UUID mobInstanceId, @NotNull Pose pose, long durationTicks) {
        if (pose == Pose.NONE || durationTicks <= 0L) {
            poses.remove(mobInstanceId);
            return;
        }
        poses.put(mobInstanceId, new Entry(pose, Bukkit.getCurrentTick() + durationTicks));
    }

    /** @param mobInstanceId 技の中断・終了・破棄で姿勢を戻す Mob */
    public void clear(@NotNull UUID mobInstanceId) {
        poses.remove(mobInstanceId);
    }

    /**
     * @param mobInstanceId 表示中の Mob
     * @return 期限内の攻撃姿勢。期限切れは削除して {@link Pose#NONE}
     */
    public @NotNull Pose current(@NotNull UUID mobInstanceId) {
        Entry entry = poses.get(mobInstanceId);
        if (entry == null) {
            return Pose.NONE;
        }
        if (Bukkit.getCurrentTick() >= entry.untilTick()) {
            poses.remove(mobInstanceId);
            return Pose.NONE;
        }
        return entry.pose();
    }

    /** Plugin 停止時にすべての姿勢を破棄します。 */
    public void clearAll() {
        poses.clear();
    }

    private record Entry(@NotNull Pose pose, long untilTick) { }
}

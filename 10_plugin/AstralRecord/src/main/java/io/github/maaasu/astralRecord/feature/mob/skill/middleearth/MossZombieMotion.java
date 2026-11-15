package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
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

    /**
     * 専用パックの演出形状です。番号とモデル名は両版のパックと Geyser 拡張の {@code astralrecord:style} で共有します。
     * すべて長さ方向に対称なので、扇形などの向きは複数の形状を並べて表します。
     */
    public enum EffectStyle {
        /** 苔の予告輪（地鳴り）。 */
        RING(0, "warning_ring"),
        /** 胞子の予告輪（胞子だまり）。 */
        SPORE_RING(1, "warning_spore"),
        /** 這う苔の帯（叩きつけの扇形の縁）。 */
        VINE(2, "warning_vine"),
        /** 地面から伸びる苔の棘（叩きつけ・地鳴りの着弾）。 */
        SPIKES(3, "impact_spikes"),
        /** 胞子の破裂（胞子だまりの着弾）。 */
        SPORE_BURST(4, "impact_spores");

        private final int id;
        private final String model;

        EffectStyle(int id, String suffix) {
            this.id = id;
            this.model = "midgard_moss_zombie_" + suffix;
        }

        /** @return 統合版へ送る形状番号 */
        public int id() {
            return id;
        }

        /** @return Java 版アイテム定義の名前 */
        public @NotNull String model() {
            return model;
        }

        /** @return 着弾演出なら true。フレームを一度だけ進めて保持します */
        public boolean impact() {
            return this == SPIKES || this == SPORE_BURST;
        }
    }

    /**
     * 表示専用の演出1つ。当たり判定には使いません。
     *
     * @param style 形状
     * @param origin 床上の中心。yaw は長さ方向
     * @param width 幅（ブロック）
     * @param height 高さ倍率
     * @param length 長さ（ブロック）
     */
    public record Effect(@NotNull EffectStyle style, @NotNull Location origin, float width, float height, float length) {
        /** 呼び出し元による変更を防ぐため、位置を複製して保持します。 */
        public Effect {
            origin = origin.clone();
        }

        /**
         * @param style 形状 @param center 床上の中心 @param radius 半径 @param height 高さ倍率
         * @return 円形の演出
         */
        public static @NotNull Effect circle(@NotNull EffectStyle style, @NotNull Location center, double radius,
                                             double height) {
            Location location = center.clone().add(0.0D, 0.08D, 0.0D);
            location.setYaw(0.0F);
            location.setPitch(0.0F);
            float diameter = (float) (radius * 2.0D);
            return new Effect(style, location, diameter, (float) height, diameter);
        }

        /**
         * @param style 形状 @param first 始点 @param last 終点 @param width 幅 @param height 高さ倍率
         * @return 二点を結ぶ帯状の演出
         */
        public static @NotNull Effect segment(@NotNull EffectStyle style, @NotNull Location first,
                                              @NotNull Location last, double width, double height) {
            Vector direction = last.toVector().subtract(first.toVector()).setY(0.0D);
            Location center = first.clone().add(direction.clone().multiply(0.5D)).add(0.0D, 0.08D, 0.0D);
            if (direction.lengthSquared() > 0.0001D) {
                center.setDirection(direction);
            }
            center.setPitch(0.0F);
            return new Effect(style, center, (float) width, (float) height, (float) direction.length());
        }
    }

    /** 外観表示側が実装する演出の受け口です。 */
    @FunctionalInterface
    public interface EffectSink {
        /**
         * @param mobInstanceId 所有 Mob
         * @param effects 表示する演出
         * @param durationTicks 表示期間
         * @return この呼び出しで作った演出だけを解除する処理
         */
        @NotNull Runnable show(@NotNull UUID mobInstanceId, @NotNull List<Effect> effects, long durationTicks);
    }

    private static final Runnable NO_EFFECT = () -> { };
    private final Map<UUID, Entry> poses = new HashMap<>();
    private @Nullable EffectSink effectSink;

    /** @param sink 外観表示。null ならパック演出を出しません（予告粒子と判定は変わりません） */
    public void setEffectSink(@Nullable EffectSink sink) {
        this.effectSink = sink;
    }

    /**
     * 専用パックの演出を表示します。パック非対応の閲覧者には何も出ません。
     *
     * @param mobInstanceId 所有 Mob
     * @param effects 表示する演出
     * @param durationTicks 表示期間
     * @return この呼び出しの演出だけを解除する処理。表示先がなければ何もしない処理
     */
    public @NotNull Runnable showEffects(@NotNull UUID mobInstanceId, @NotNull List<Effect> effects,
                                         long durationTicks) {
        EffectSink sink = effectSink;
        if (sink == null || effects.isEmpty() || durationTicks <= 0L) {
            return NO_EFFECT;
        }
        // 演出の失敗で技の進行（通常AIの復帰・ダメージ）を止めないよう、表示側の例外はここで止める。
        Runnable remove;
        try {
            remove = sink.show(mobInstanceId, effects, durationTicks);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6515, exception);
            return NO_EFFECT;
        }
        return () -> {
            try {
                remove.run();
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_6515, exception);
            }
        };
    }

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

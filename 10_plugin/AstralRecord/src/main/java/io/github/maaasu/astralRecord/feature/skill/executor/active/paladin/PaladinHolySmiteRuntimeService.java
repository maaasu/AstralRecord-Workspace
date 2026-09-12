package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 実行中のホーリースマイト聖柱の位置と残り持続時間を管理します。
 * <p>
 * 柱の表示と攻撃は executor の task が所有し、このサービスはホーリーフィールド更新時に
 * 範囲内の柱を生成時の持続時間へ戻すための寿命だけを保持します。
 */
public final class PaladinHolySmiteRuntimeService {

    private final Map<UUID, PillarRuntime> pillarsById = new HashMap<>();

    /** 依存を持たない聖柱実行時状態サービスを初期化します。 */
    public PaladinHolySmiteRuntimeService() {
    }

    /**
     * 新しい聖柱を登録します。
     *
     * @param pillarId 発動単位の一意ID
     * @param center 聖柱の中心
     * @param durationTicks 生成時の持続tick
     */
    public void register(@NotNull UUID pillarId, @NotNull Location center, int durationTicks) {
        int safeDurationTicks = Math.max(1, durationTicks);
        pillarsById.put(pillarId, new PillarRuntime(center.clone(), safeDurationTicks, safeDurationTicks));
    }

    /**
     * 指定した聖柱の実行済みtickを消費します。
     *
     * @param pillarId 対象聖柱ID
     * @return 消費後も次tickへ継続する場合は true
     */
    public boolean consumeTick(@NotNull UUID pillarId) {
        PillarRuntime runtime = pillarsById.get(pillarId);
        if (runtime == null) {
            return false;
        }
        runtime.remainingTicks--;
        return runtime.remainingTicks > 0;
    }

    /**
     * 指定した水平範囲内にある全ホーリースマイト聖柱を、各柱の生成時持続時間へ戻します。
     * world が異なる柱と範囲外の柱は更新しません。
     *
     * @param center 更新前のホーリーフィールド中心
     * @param radius ホーリーフィールドの水平半径
     */
    public void refreshWithin(@NotNull Location center, double radius) {
        if (center.getWorld() == null) {
            return;
        }
        double radiusSquared = Math.max(0.0D, radius) * Math.max(0.0D, radius);
        for (PillarRuntime runtime : pillarsById.values()) {
            if (runtime.center.getWorld() == null || !center.getWorld().equals(runtime.center.getWorld())) {
                continue;
            }
            double deltaX = runtime.center.getX() - center.getX();
            double deltaZ = runtime.center.getZ() - center.getZ();
            if (deltaX * deltaX + deltaZ * deltaZ <= radiusSquared) {
                runtime.remainingTicks = runtime.durationTicks;
            }
        }
    }

    /**
     * 終了した聖柱を登録から除外します。
     *
     * @param pillarId 対象聖柱ID
     */
    public void unregister(@NotNull UUID pillarId) {
        pillarsById.remove(pillarId);
    }

    /** Plugin停止時に全聖柱の実行時状態を破棄します。 */
    public void clearAll() {
        pillarsById.clear();
    }

    private static final class PillarRuntime {
        private final Location center;
        private final int durationTicks;
        private int remainingTicks;

        private PillarRuntime(@NotNull Location center, int durationTicks, int remainingTicks) {
            this.center = center;
            this.durationTicks = durationTicks;
            this.remainingTicks = remainingTicks;
        }
    }
}

package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 実行中のホーリースマイト聖柱の表示状態と残り持続時間を管理します。
 * <p>
 * 柱の表示と攻撃は executor の task が所有し、このサービスはホーリーフィールドによる
 * 寿命更新とホーリーコントロールによる検索・移動を仲介します。
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
     * @param state 表示中の聖柱状態
     * @param durationTicks 生成時の持続tick
     */
    public void register(
            @NotNull UUID pillarId,
            @NotNull PaladinHolySmiteExecutor.HolyPillarState state,
            int durationTicks
    ) {
        int safeDurationTicks = Math.max(1, durationTicks);
        pillarsById.put(pillarId, new PillarRuntime(state, safeDurationTicks, safeDurationTicks));
    }

    /**
     * 指定した聖柱の実行済みtickを消費します。
     *
     * @param pillarId 対象聖柱ID
     * @return 消費後も次tickへ継続する場合は true
     */
    public boolean consumeTick(@NotNull UUID pillarId) {
        PillarRuntime runtime = pillarsById.get(pillarId);
        if (runtime == null || !runtime.state.isActive()) {
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
            Location pillarCenter = runtime.state.center();
            if (!runtime.state.isActive()
                    || pillarCenter.getWorld() == null
                    || !center.getWorld().equals(pillarCenter.getWorld())) {
                continue;
            }
            double deltaX = pillarCenter.getX() - center.getX();
            double deltaZ = pillarCenter.getZ() - center.getZ();
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

    /**
     * 指定地点と同じworldにある、水平半径内で最も近い有効な聖柱を返します。
     *
     * @param location 検索中心
     * @param radius 水平検索半径
     * @return 最寄りの聖柱。存在しない場合は null
     */
    public @Nullable PaladinHolySmiteExecutor.HolyPillarState findNearest(
            @NotNull Location location,
            double radius
    ) {
        if (location.getWorld() == null) {
            return null;
        }
        double safeRadius = Math.max(0.0D, radius);
        double maximumDistanceSquared = safeRadius * safeRadius;
        PaladinHolySmiteExecutor.HolyPillarState nearest = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        for (PillarRuntime runtime : pillarsById.values()) {
            if (!runtime.state.isActive()) {
                continue;
            }
            Location center = runtime.state.center();
            if (center.getWorld() != location.getWorld()) {
                continue;
            }
            double deltaX = center.getX() - location.getX();
            double deltaZ = center.getZ() - location.getZ();
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared <= maximumDistanceSquared && distanceSquared < nearestDistanceSquared) {
                nearest = runtime.state;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    /** 聖柱を指定地点へ移動します。 */
    public void moveTo(
            @NotNull PaladinHolySmiteExecutor.HolyPillarState pillar,
            @NotNull Location destination
    ) {
        if (isActive(pillar)) {
            pillar.moveTo(destination);
        }
    }

    /** 指定した聖柱が現在も存在するか判定します。 */
    public boolean isActive(@NotNull PaladinHolySmiteExecutor.HolyPillarState pillar) {
        return pillarsById.values().stream().anyMatch(runtime -> runtime.state == pillar && pillar.isActive());
    }

    /** Plugin停止時に全聖柱の実行時状態を破棄します。 */
    public void clearAll() {
        for (PillarRuntime runtime : new ArrayList<>(pillarsById.values())) {
            runtime.state.destroy();
        }
        pillarsById.clear();
    }

    private static final class PillarRuntime {
        private final PaladinHolySmiteExecutor.HolyPillarState state;
        private final int durationTicks;
        private int remainingTicks;

        private PillarRuntime(
                @NotNull PaladinHolySmiteExecutor.HolyPillarState state,
                int durationTicks,
                int remainingTicks
        ) {
            this.state = state;
            this.durationTicks = durationTicks;
            this.remainingTicks = remainingTicks;
        }
    }
}

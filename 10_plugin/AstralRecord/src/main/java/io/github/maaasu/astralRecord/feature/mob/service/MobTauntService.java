package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Mob ごとの一時挑発対象を、通常の脅威値とは独立して管理します。 */
public final class MobTauntService {

    private final Map<UUID, TauntState> taunts = new ConcurrentHashMap<>();
    private final LongSupplier currentTick;

    /** Bukkit のサーバー tick を有効期限の基準にして初期化します。 */
    public MobTauntService() {
        this(Bukkit::getCurrentTick);
    }

    MobTauntService(@NotNull LongSupplier currentTick) {
        this.currentTick = currentTick;
    }

    /**
     * 対象 Mob を指定プレイヤーへ一時的に固定します。同じ Mob への後発挑発は先発を上書きします。
     *
     * @return 挑発を適用できた場合は {@code true}
     */
    public boolean apply(@NotNull MobInstance mob, @NotNull UUID taunterId, long durationTicks) {
        if (durationTicks <= 0L
                || mob.state() == MobState.DEAD
                || mob.state() == MobState.LEASHED
                || mob.template().category() == MobCategory.NPC
                || mob.template().targeting() == null) {
            return false;
        }
        long expiresAtTick = saturatingAdd(currentTick.getAsLong(), durationTicks);
        taunts.put(mob.instanceId(), new TauntState(mob, taunterId, expiresAtTick));
        mob.targetId(taunterId);
        if (mob.state() == MobState.IDLE) {
            mob.state(MobState.AGGRO);
        }
        return true;
    }

    /** 有効な挑発者 UUID を返し、期限切れなら通常選定へ戻せるよう現在対象を解除します。 */
    public @Nullable UUID activeTaunter(@NotNull MobInstance mob) {
        TauntState state = taunts.get(mob.instanceId());
        if (state == null) {
            return null;
        }
        if (currentTick.getAsLong() < state.expiresAtTick()) {
            return state.taunterId();
        }
        removeState(state);
        return null;
    }

    /** 対象 Mob の挑発を解除します。 */
    public void clear(@NotNull MobInstance mob) {
        TauntState state = taunts.remove(mob.instanceId());
        clearForcedTarget(state);
    }

    /** 破棄される Mob の挑発状態を解放します。 */
    public void clearMob(@NotNull UUID mobInstanceId) {
        taunts.remove(mobInstanceId);
    }

    /** 指定プレイヤーが発生させた挑発をすべて解除します。 */
    public void clearByTaunter(@NotNull UUID taunterId) {
        taunts.values().stream()
                .filter(state -> state.taunterId().equals(taunterId))
                .toList()
                .forEach(this::removeState);
    }

    /** 全挑発状態を解除します。 */
    public void clearAll() {
        taunts.values().stream().toList().forEach(this::removeState);
    }

    private void removeState(@NotNull TauntState state) {
        if (taunts.remove(state.mob().instanceId(), state)) {
            clearForcedTarget(state);
        }
    }

    private static void clearForcedTarget(@Nullable TauntState state) {
        if (state != null && state.taunterId().equals(state.mob().targetId())) {
            state.mob().targetId(null);
        }
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record TauntState(
            @NotNull MobInstance mob,
            @NotNull UUID taunterId,
            long expiresAtTick
    ) {
    }
}

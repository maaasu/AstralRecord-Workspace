package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ガードコンバートが生成する専用リソース「ガード」を管理します。 */
public final class PaladinGuardRuntimeService {

    private static final long DECAY_DELAY_MS = 10_000L;
    private static final long DECAY_INTERVAL_MS = 1_000L;
    private static final double DECAY_RATIO_PER_SECOND = 0.10D;
    private final Map<UUID, GuardState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Integer>> activeLevelsByPlayer = new ConcurrentHashMap<>();

    /** バインドされたガードコンバートのレベルに合わせてガード状態を有効化します。 */
    public void activate(@NotNull AstPlayer player, @NotNull UUID learnedSkillId, int skillLevel) {
        UUID playerId = player.getBukkit().getUniqueId();
        Map<UUID, Integer> levels = activeLevelsByPlayer.computeIfAbsent(
                playerId, ignored -> new ConcurrentHashMap<>()
        );
        levels.put(learnedSkillId, Math.max(1, skillLevel));
        double maximum = maximumGuard(levels);
        long nowMs = System.currentTimeMillis();
        states.compute(playerId, (ignored, current) -> current == null
                ? new GuardState(0.0D, maximum, nowMs, false, 0.0D, nowMs)
                : current.withMaximum(maximum));
    }

    /** 指定個体のバインドを解除し、最後の個体ならプレイヤーのガードを破棄します。 */
    public void deactivate(@NotNull AstPlayer player, @NotNull UUID learnedSkillId) {
        UUID playerId = player.getBukkit().getUniqueId();
        Map<UUID, Integer> levels = activeLevelsByPlayer.get(playerId);
        if (levels == null) {
            states.remove(playerId);
            return;
        }
        levels.remove(learnedSkillId);
        if (levels.isEmpty()) {
            activeLevelsByPlayer.remove(playerId, levels);
            states.remove(playerId);
            return;
        }
        double maximum = maximumGuard(levels);
        states.computeIfPresent(playerId, (ignored, state) -> state.withMaximum(maximum));
    }

    /**
     * 防御適用前後のダメージからガード獲得量を算出して現在値へ加算します。
     *
     * @param player 被弾したプレイヤー
     * @param rawDamage 防御力計算を無視したダメージ
     * @param finalHealthDamage 最終的にHPへ適用されたダメージ
     * @param maximumHealth 被弾時の最大HP
     */
    public void recordDamage(
            @NotNull AstPlayer player,
            double rawDamage,
            double finalHealthDamage,
            double maximumHealth
    ) {
        UUID playerId = player.getBukkit().getUniqueId();
        GuardState current = states.get(playerId);
        if (current == null) {
            return;
        }
        double gained = calculateGain(rawDamage, finalHealthDamage, maximumHealth);
        if (!(gained > 0.0D)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        states.computeIfPresent(playerId, (ignored, state) -> state.increase(gained, nowMs));
    }

    /** 10秒の猶予後、減少開始時点の値の10%を1秒ごとに減らします。 */
    public void advanceDecay(@NotNull AstPlayer player) {
        UUID playerId = player.getBukkit().getUniqueId();
        long nowMs = System.currentTimeMillis();
        states.computeIfPresent(playerId, (ignored, state) -> state.decay(nowMs));
    }

    /** 現在ガードが消費量以上なら消費して true を返します。 */
    public boolean consume(@NotNull AstPlayer player, double amount) {
        if (!(amount > 0.0D)) {
            return true;
        }
        UUID playerId = player.getBukkit().getUniqueId();
        boolean[] consumed = {false};
        states.computeIfPresent(playerId, (ignored, state) -> {
            if (state.current + 1.0E-6D < amount) {
                return state;
            }
            consumed[0] = true;
            return state.withCurrent(state.current - amount);
        });
        return consumed[0];
    }

    /** バインド中のプレイヤーについて現在値と最大値を返します。 */
    public @NotNull GuardSnapshot snapshot(@NotNull AstPlayer player) {
        GuardState state = states.get(player.getBukkit().getUniqueId());
        return state == null
                ? GuardSnapshot.inactive()
                : new GuardSnapshot(true, state.current, state.maximum);
    }

    /** ガード獲得量を「軽減率×10 + 最大HPに対する実被ダメージ率×10」で返します。 */
    static double calculateGain(double rawDamage, double finalHealthDamage, double maximumHealth) {
        double safeRaw = Math.max(0.0D, rawDamage);
        double safeFinal = Math.max(0.0D, finalHealthDamage);
        double mitigation = safeRaw <= 0.0D
                ? 0.0D
                : Math.max(0.0D, 1.0D - safeFinal / safeRaw) * 10.0D;
        double healthRatio = maximumHealth <= 0.0D
                ? 0.0D
                : safeFinal / maximumHealth * 10.0D;
        return Math.max(0.0D, mitigation + healthRatio);
    }

    private double maximumGuard(@NotNull Map<UUID, Integer> levels) {
        int maximumLevel = levels.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        return maximumLevel * 10.0D;
    }

    /** HUDと共通リソース消費へ公開するガード値です。 */
    public record GuardSnapshot(boolean active, double current, double maximum) {
        private static @NotNull GuardSnapshot inactive() {
            return new GuardSnapshot(false, 0.0D, 0.0D);
        }
    }

    private record GuardState(
            double current,
            double maximum,
            long lastIncreaseAtMs,
            boolean decaying,
            double decayAmount,
            long lastDecayAtMs
    ) {
        private @NotNull GuardState withMaximum(double nextMaximum) {
            return new GuardState(
                    Math.min(current, nextMaximum), nextMaximum, lastIncreaseAtMs,
                    decaying, decayAmount, lastDecayAtMs
            );
        }

        private @NotNull GuardState withCurrent(double nextCurrent) {
            return new GuardState(
                    Math.clamp(nextCurrent, 0.0D, maximum), maximum,
                    lastIncreaseAtMs, decaying, decayAmount, lastDecayAtMs
            );
        }

        private @NotNull GuardState increase(double amount, long nowMs) {
            return new GuardState(
                    Math.min(maximum, current + amount), maximum, nowMs, false, 0.0D, nowMs
            );
        }

        private @NotNull GuardState decay(long nowMs) {
            if (current <= 0.0D
                    || nowMs - lastIncreaseAtMs < DECAY_DELAY_MS
                    || nowMs - lastDecayAtMs < DECAY_INTERVAL_MS) {
                return this;
            }
            double perSecond = decaying ? decayAmount : current * DECAY_RATIO_PER_SECOND;
            return new GuardState(
                    Math.max(0.0D, current - perSecond), maximum,
                    lastIncreaseAtMs, true, perSecond, nowMs
            );
        }
    }
}

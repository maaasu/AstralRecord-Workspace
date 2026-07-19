package io.github.maaasu.astralRecord.shared.interaction;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 1回の入力に対して実行可能な処理候補です。
 * resolverは候補生成時に副作用を発生させず、executorへ遅延させる必要があります。
 *
 * @param id 候補種別を識別する安定したID
 * @param tier 大分類優先度
 * @param hitDistance 視点から実際の命中位置までの距離。非空間候補は0を使用
 * @param stableOrder 同tier・同距離候補の固定順。小さい値を優先
 * @param targetKey 対象を識別する安定したキー
 * @param claimPolicy 元イベントへ反映するclaim/cancel方針
 * @param executionGuard 実行直前の副作用なし再検証。falseなら敗者へfallbackせず実行を見送る
 * @param executor 勝者に選ばれた場合だけ実行する処理
 */
public record PlayerInputCandidate(
    String id,
    InteractionTier tier,
    double hitDistance,
    int stableOrder,
    String targetKey,
    InputClaimPolicy claimPolicy,
    BooleanSupplier executionGuard,
    Runnable executor
) {
    /** 実行直前再検証を必要としない候補を生成します。 */
    public PlayerInputCandidate(
        String id,
        InteractionTier tier,
        double hitDistance,
        int stableOrder,
        String targetKey,
        InputClaimPolicy claimPolicy,
        Runnable executor
    ) {
        this(id, tier, hitDistance, stableOrder, targetKey, claimPolicy, () -> true, executor);
    }

    /**
     * 入力候補を生成し、決定的比較に必要な値を検証します。
     */
    public PlayerInputCandidate {
        id = requireText(id, "id");
        tier = Objects.requireNonNull(tier, "tier");
        if (!Double.isFinite(hitDistance) || hitDistance < 0.0D) {
            throw new IllegalArgumentException("hitDistance must be finite and zero or greater");
        }
        if (stableOrder < 0) {
            throw new IllegalArgumentException("stableOrder must be zero or greater");
        }
        targetKey = requireText(targetKey, "targetKey");
        claimPolicy = Objects.requireNonNull(claimPolicy, "claimPolicy");
        executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
        executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * 実行直前条件が成立する場合だけexecutorを実行します。
     * 条件不成立でも既に確定した勝者から敗者へfallbackはしません。
     *
     * @return executorを実行した場合はtrue
     */
    public boolean executeIfValid() {
        if (!executionGuard.getAsBoolean()) {
            return false;
        }
        executor.run();
        return true;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

package io.github.maaasu.astralRecord.feature.status.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * HP回復の発生元を表します。
 *
 * @param healer 回復を行ったプレイヤー。自己回復またはプレイヤー以外の回復では {@code null}
 * @param sourceName 回復手段のプレイヤー向け表示名
 * @param followUpBonusAllowed 回復を受けたときの追撃回復効果を適用してよい場合 true
 */
public record HealthRecoveryContext(
        @Nullable AstPlayer healer,
        @NotNull String sourceName,
        boolean followUpBonusAllowed
) {

    /**
     * 回復手段だけを持つ自己回復コンテキストを作成します。
     *
     * @param sourceName 回復手段の表示名
     * @return 自己回復コンテキスト
     */
    public static @NotNull HealthRecoveryContext self(@NotNull String sourceName) {
        return new HealthRecoveryContext(null, sourceName, true);
    }

    /**
     * プレイヤーが行った回復コンテキストを作成します。
     *
     * @param healer 回復を行ったプレイヤー
     * @param sourceName 回復手段の表示名
     * @return プレイヤー回復コンテキスト
     */
    public static @NotNull HealthRecoveryContext by(
            @NotNull AstPlayer healer,
            @NotNull String sourceName
    ) {
        return new HealthRecoveryContext(healer, sourceName, true);
    }

    /**
     * 発動元スキル自身の回復など、追撃回復を適用しないコンテキストを返します。
     * @return 追撃回復を抑止したコンテキスト
     */
    public @NotNull HealthRecoveryContext withoutFollowUpBonus() {
        return new HealthRecoveryContext(healer, sourceName, false);
    }

    public HealthRecoveryContext {
        sourceName = sourceName == null || sourceName.isBlank() ? "HP回復" : sourceName;
    }
}

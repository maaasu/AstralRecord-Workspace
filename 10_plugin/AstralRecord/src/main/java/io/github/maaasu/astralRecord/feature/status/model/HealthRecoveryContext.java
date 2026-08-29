package io.github.maaasu.astralRecord.feature.status.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * HP回復の発生元を表します。
 *
 * @param healer 回復を行ったプレイヤー。自己回復またはプレイヤー以外の回復では {@code null}
 * @param sourceName 回復手段のプレイヤー向け表示名
 */
public record HealthRecoveryContext(
        @Nullable AstPlayer healer,
        @NotNull String sourceName
) {

    /**
     * 回復手段だけを持つ自己回復コンテキストを作成します。
     *
     * @param sourceName 回復手段の表示名
     * @return 自己回復コンテキスト
     */
    public static @NotNull HealthRecoveryContext self(@NotNull String sourceName) {
        return new HealthRecoveryContext(null, sourceName);
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
        return new HealthRecoveryContext(healer, sourceName);
    }

    public HealthRecoveryContext {
        sourceName = sourceName == null || sourceName.isBlank() ? "HP回復" : sourceName;
    }
}

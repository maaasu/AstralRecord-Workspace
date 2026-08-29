package io.github.maaasu.astralRecord.feature.status.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 実際に増加したプレイヤーHPの通知情報です。
 *
 * @param target 回復したプレイヤー
 * @param amount 上限適用後の実回復量
 * @param healer 回復を行ったプレイヤー。自己回復またはプレイヤー以外の回復では {@code null}
 * @param sourceName 回復手段の表示名
 */
public record HealthRecoveryNotification(
        @NotNull AstPlayer target,
        double amount,
        @Nullable AstPlayer healer,
        @NotNull String sourceName
) {
}

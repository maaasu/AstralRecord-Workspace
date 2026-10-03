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
 * @param encounterHealing 挑戦中のヒール量へ加算する通常回復なら {@code true}。死亡復帰などの全回復は除外します
 */
public record HealthRecoveryNotification(
        @NotNull AstPlayer target,
        double amount,
        @Nullable AstPlayer healer,
        @NotNull String sourceName,
        boolean encounterHealing
) {
    /**
     * 通常のHP回復として通知を作成します。
     * @param target 回復対象
     * @param amount 上限適用後の実回復量
     * @param healer 回復者。自己回復は {@code null}
     * @param sourceName 回復手段の表示名
     */
    public HealthRecoveryNotification(AstPlayer target, double amount, AstPlayer healer, String sourceName) {
        this(target, amount, healer, sourceName, true);
    }
}

package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 共通制御と個別ロジックの境界で返すスキル実行結果。
 * <p>
 * 失敗時は {@link #messageId} に発動者向け通知 ID を載せ、共通制御層から
 * {@link SkillCaster#notify(PlayerMsgId, Object...)} 経由で送信する。
 *
 * @param success              発動成功フラグ
 * @param consumedMana         実際に消費した MP
 * @param startedCooldownTicks 実際に開始したクールダウン
 * @param messageId            プレイヤー向け通知 ID。不要なら {@code null}
 */
public record SkillCastResult(
        boolean success,
        double consumedMana,
        long startedCooldownTicks,
        @Nullable PlayerMsgId messageId
) {

    /**
     * 失敗結果を生成します。
     *
     * @param messageId 失敗理由を伝える通知 ID（{@code null} 可）
     * @return 失敗を表す結果
     */
    @NotNull
    public static SkillCastResult failure(@Nullable PlayerMsgId messageId) {
        return new SkillCastResult(false, 0.0, 0L, messageId);
    }

    /**
     * 成功結果を生成します。
     *
     * @param consumedMana         実際の MP 消費量
     * @param startedCooldownTicks 実際に開始した cooldown
     * @return 成功を表す結果
     */
    @NotNull
    public static SkillCastResult success(double consumedMana, long startedCooldownTicks) {
        return new SkillCastResult(true, consumedMana, startedCooldownTicks, null);
    }
}

package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 共通制御と個別ロジックの境界で返すスキル実行結果。
 * <p>
 * 失敗時は {@link #messageId} に発動者向け通知 ID を載せ、共通制御層から
 * {@link SkillCaster#notify(PlayerMsgId, Object...)} 経由で送信する。
 * リソース消費量はスキル定義を正本として共通制御層が適用します。クールダウンは
 * 通常はスキル定義を使いますが、命中などの実行結果で短縮するスキルだけは、成功結果へ
 * 固定tickの上書きを指定できます。
 *
 * @param success   発動成功フラグ
 * @param messageId プレイヤー向け通知 ID。不要なら {@code null}
 * @param cooldownTicksOverride 成功時に採用するクールダウンtick。通常は {@code null}
 */
public record SkillCastResult(
        boolean success,
        @Nullable PlayerMsgId messageId,
        @Nullable Long cooldownTicksOverride
) {

    /** 既存の成功・失敗結果形式から初期化します。 */
    public SkillCastResult(
            boolean success,
            @Nullable PlayerMsgId messageId
    ) {
        this(success, messageId, null);
    }

    /**
     * 失敗結果を生成します。
     *
     * @param messageId 失敗理由を伝える通知 ID（{@code null} 可）
     * @return 失敗を表す結果
     */
    @NotNull
    public static SkillCastResult failure(@Nullable PlayerMsgId messageId) {
        return new SkillCastResult(false, messageId);
    }

    /**
     * 成功結果を生成します。
     *
     * @return 成功を表す結果
     */
    @NotNull
    public static SkillCastResult succeeded() {
        return new SkillCastResult(true, null);
    }

    /**
     * 成功時のクールダウンtickを上書きする結果を生成します。
     *
     * @param cooldownTicks 成功時に採用するクールダウンtick（1以上）
     * @return クールダウン上書きを含む成功結果
     * @throws IllegalArgumentException tickが1未満の場合
     */
    @NotNull
    public static SkillCastResult succeededWithCooldownTicks(long cooldownTicks) {
        if (cooldownTicks < 1L) {
            throw new IllegalArgumentException("cooldownTicks must be positive");
        }
        return new SkillCastResult(true, null, cooldownTicks);
    }
}

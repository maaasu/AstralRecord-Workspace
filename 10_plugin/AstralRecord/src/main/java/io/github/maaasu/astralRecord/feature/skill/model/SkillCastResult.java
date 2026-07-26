package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 共通制御と個別ロジックの境界で返すスキル実行結果。
 * <p>
 * 失敗時は {@link #messageId} に発動者向け通知 ID を載せ、共通制御層から
 * {@link SkillCaster#notify(PlayerMsgId, Object...)} 経由で送信する。
 * リソース消費量とクールダウンはスキル定義を正本として共通制御層が適用するため、
 * executor はこの結果へ含めない。
 *
 * @param success   発動成功フラグ
 * @param messageId プレイヤー向け通知 ID。不要なら {@code null}
 */
public record SkillCastResult(
        boolean success,
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
}

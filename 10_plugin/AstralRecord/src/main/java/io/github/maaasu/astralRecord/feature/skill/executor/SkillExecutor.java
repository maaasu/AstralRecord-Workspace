package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillStatusModifier;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code implementationId} に紐づく個別スキルロジックの共通契約。
 * <p>
 * 共通制御層は {@link io.github.maaasu.astralRecord.feature.skill.service.SkillService}
 * が要求レベル、リソース消費、クールダウンを担うため、実装側は {@code params} を解釈して
 * 個別演出・当たり判定・倍率計算等に集中する。
 * 同一実装クラスが複数のスキル定義から呼び出される前提のため、状態は持たず、
 * 入力 {@link SkillCastContext} だけから結果を導出すること。
 */
public interface SkillExecutor {

    /**
     * 担当する {@code implementationId} を返します。
     * 値はレジストリのキーとして使用されるため、実装ごとに一意である必要があります。
     *
     * @return implementationId
     */
    @NotNull
    String implementationId();

    /**
     * スキル実装の種別を返します。
     *
     * @return スキル種別
     */
    default @NotNull SkillKind kind() {
        return SkillKind.ACTIVE;
    }

    /**
     * スキルを実行します。共通検証（要求レベル・消費リソース・cooldown）は呼び出し前に通過済みです。
     * 実装は効果の適用結果だけを成功／失敗で返し、成功時の実消費と cooldown 開始は
     * {@code SkillService} がスキル定義から一元適用します。
     *
     * @param context 実行コンテキスト
     * @return 実行結果。失敗時は {@link SkillCastResult#failure} を返す
     */
    @NotNull
    SkillCastResult cast(@NotNull SkillCastContext context);

    /**
     * パッシブスキルが有効化された直後に呼ばれます。
     *
     * @param context パッシブコンテキスト
     */
    default void onActivate(@NotNull PassiveSkillContext context) {
    }

    /**
     * パッシブスキルが無効化される直前に呼ばれます。
     *
     * @param context パッシブコンテキスト
     */
    default void onDeactivate(@NotNull PassiveSkillContext context) {
    }

    /**
     * パッシブスキルが有効な間、定期 tick ごとに呼ばれます。
     *
     * @param context パッシブコンテキスト
     */
    default void onTick(@NotNull PassiveSkillContext context) {
    }

    /**
     * パッシブスキルが定期 tick 呼び出しを必要とするかを返します。
     * <p>
     * ステータス補正だけのパッシブは {@code false} のままにし、時間経過で処理が必要な executor だけが
     * {@code true} を返します。
     *
     * @return 定期 tick が必要な場合は {@code true}
     */
    default boolean requiresPassiveTick() {
        return false;
    }

    /**
     * パッシブスキルの定期 tick 呼び出し間隔を返します。
     * <p>
     * {@link #requiresPassiveTick()} が {@code false} の場合、この値は使用されません。
     * 1 未満の値を返した場合は呼び出し側で 1 tick として扱います。
     *
     * @return 定期 tick 呼び出し間隔
     */
    default long passiveTickIntervalTicks() {
        return 1L;
    }

    /**
     * パッシブスキルが付与するステータス補正を返します。
     *
     * @param context パッシブコンテキスト
     * @return ステータス補正一覧
     */
    default @NotNull List<PassiveSkillStatusModifier> passiveStatusModifiers(@NotNull PassiveSkillContext context) {
        return List.of();
    }

    /**
     * 起動時または reload 時に {@code params} の妥当性を検証します。
     * 既定では何も検証しません。実装側で必要キー・型の検証を行う場合にオーバーライドしてください。
     *
     * @param skill 検証対象スキル定義
     * @throws SkillParameterException 検証失敗時
     */
    default void validateParams(@NotNull SkillDefinition skill) {
        // 既定は no-op。実装ごとに必要キー・型を検証する。
    }
}

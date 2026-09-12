package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import org.jetbrains.annotations.NotNull;

/** スキル定義を伴う命中結果を受け取る listener です。 */
@FunctionalInterface
public interface SkillHitListener {

    /**
     * スキルの一撃が共通ダメージ処理を通過した後に呼び出します。
     *
     * @param skill 命中を発生させたスキル定義
     * @param attacker 攻撃者
     * @param target 命中対象
     * @param result 共通ダメージ処理の結果
     */
    void onSkillHit(
            @NotNull SkillDefinition skill,
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull DamageResult result
    );
}

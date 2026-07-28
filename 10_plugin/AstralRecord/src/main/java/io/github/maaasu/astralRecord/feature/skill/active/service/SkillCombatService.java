package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * 発動スキルから custom combat と状態異常へ接続する共通サービスです。
 */
public final class SkillCombatService {

    private final DamageService damageService;
    private final ConditionService conditionService;
    private final MobKnockbackService knockbackService;

    /**
     * 戦闘サービスで初期化します。
     *
     * @param damageService custom combat ダメージサービス
     * @param conditionService 状態異常サービス
     * @param knockbackService ノックバック耐性を適用する共通サービス
     */
    public SkillCombatService(
            @NotNull DamageService damageService,
            @NotNull ConditionService conditionService,
            @NotNull MobKnockbackService knockbackService
    ) {
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.knockbackService = knockbackService;
    }

    /**
     * 単一属性のスキルダメージと、命中時状態異常を適用します。
     */
    public @NotNull DamageResult hit(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull AttackType attackType,
            @NotNull DamageElement element,
            double ratio,
            @NotNull ActiveSkillCondition... conditions
    ) {
        return hit(attacker, target, attackType, List.of(new DamageComponent(element, ratio)), conditions);
    }

    /**
     * 複数属性成分のスキルダメージと、命中時状態異常を適用します。
     */
    public @NotNull DamageResult hit(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull ActiveSkillCondition... conditions
    ) {
        DamageResult result = damageService.attack(attacker, target, attackType, components, DamageSource.SKILL);
        if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
            Arrays.stream(conditions).forEach(condition -> applyCondition(attacker, target, condition));
        }
        return result;
    }

    /** 対象 Mob を発動者へ向け、脅威値を加算します。 */
    public void provoke(@NotNull AstEntity attacker, @NotNull AstEntity target, double threat) {
        if (!attacker.isPlayer() || target.mob() == null) {
            return;
        }
        double highestThreat = target.mob().threatTable().snapshot().values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0D);
        double currentThreat = target.mob().threatTable().snapshot()
                .getOrDefault(attacker.id(), 0.0D);
        double guaranteedLead = highestThreat + Math.max(0.0D, threat);
        target.mob().threatTable().add(attacker.id(), Math.max(0.0D, guaranteedLead - currentThreat));
        target.mob().targetId(attacker.id());
        if (target.mob().state() == MobState.IDLE) {
            target.mob().state(MobState.AGGRO);
        }
    }

    /** 対象 Mob を指定地点から押し出します。 */
    public void knockback(
            @NotNull AstEntity target,
            @NotNull Location source,
            double horizontalStrength,
            double verticalStrength
    ) {
        knockbackService.applyWithStrength(target, source, horizontalStrength, verticalStrength);
    }

    private void applyCondition(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull ActiveSkillCondition condition
    ) {
        conditionService.applyCondition(new ConditionApplyRequest(
                target,
                attacker,
                condition.type(),
                condition.durationTicks(),
                condition.chance(),
                condition.strength(),
                null,
                null,
                null,
                null,
                ConditionApplyReason.SKILL
        ));
    }
}

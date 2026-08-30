package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** HP攻撃を抑え、シールドブレイクを強化するハンターの水色の矢です。 */
public final class HunterCrashArrowExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_crash_arrow";

    /** 共有発動スキルサービスで初期化します。 */
    public HunterCrashArrowExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "damageRatio");
        requirePositive(params, "shieldBreakMultiplier");
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 14.0D);
        double damageRatio = params.getDouble("damageRatio", 0.45D);
        double shieldBreakMultiplier = params.getDouble("shieldBreakMultiplier", 3.0D);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.35D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.45D);
        AstEntity attacker = context.attacker();

        context.services().projectiles().launch(
                context.player(),
                context.eyeLocation(),
                context.direction(),
                crashArrowProjectile(range, projectileSpeed, projectileHitRadius),
                (target, ignored) -> context.services().combat().hit(
                        attacker,
                        target,
                        AttackType.RANGED,
                        DamageElement.NONE,
                        damageRatio,
                        shieldBreakMultiplier
                ),
                ignored -> { }
        );
        context.services().effects().sound(
                context.eyeLocation(),
                Sound.ENTITY_ARROW_SHOOT,
                1.15F,
                0.75F
        );
        return context.success();
    }

    /**
     * クラッシュアローの重力なし・非貫通仮想飛翔体仕様を返します。
     *
     * @param range 射程
     * @param speed 1 tickあたりの速度
     * @param hitRadius 命中判定半径
     * @return 水色の軌跡と着弾粒子を持つ飛翔体仕様
     */
    static @NotNull SkillProjectileSpec crashArrowProjectile(
            double range,
            double speed,
            double hitRadius
    ) {
        return new SkillProjectileSpec(
                range,
                speed,
                hitRadius,
                false,
                1,
                SharedParticleDefinitions.SKILL_HUNTER_CRASH_ARROW_TRAIL,
                SharedParticleDefinitions.SKILL_HUNTER_CRASH_ARROW_IMPACT
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "クラッシュアローの params[" + key + "] は正数が必要です");
        }
    }
}
